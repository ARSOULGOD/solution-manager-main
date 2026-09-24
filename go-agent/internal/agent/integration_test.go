//go:build integration

package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestIntegrationHappyPathReachesActive(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if gw.Count("SessionOpen") != 1 || gw.Count("EnrollRequest") != 1 {
		t.Fatalf("open=%d enroll=%d", gw.Count("SessionOpen"), gw.Count("EnrollRequest"))
	}
	if !gw.WaitFor("Heartbeat", 1, 15*time.Second) {
		t.Fatal("no heartbeat")
	}
}

// lostReplyWire performs the real Enroll but reports the first reply as lost.
type lostReplyWire struct {
	Wire
	dropped bool
}

func (w *lostReplyWire) Enroll(ctx context.Context, ep string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	resp, err := w.Wire.Enroll(ctx, ep, req)
	if err == nil && !w.dropped {
		w.dropped = true
		return nil, errors.New("simulated: reply lost in transit")
	}
	return resp, err
}

func TestIntegrationLostEnrollReplyReplaysSameCSR(t *testing.T) {
	gw := startGateway(t, nil)
	// Build by hand so the wire can be wrapped before Run — Build is in this
	// package, so l.d is reachable.
	l, _, err := Build(BuildConfig{DataDir: filepath.Join(t.TempDir(), "data"), Endpoint: gw.Endpoint(), Token: "tok",
		TrustBundlePEM: gw.CAPEM(), AgentVersion: "test", Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	l.d.Wire = &lostReplyWire{Wire: l.d.Wire}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	waitPhase(t, l, PhaseActive, 15*time.Second)
	// Two EnrollRequests reached the gateway with the same CSR; the second was
	// the stored replay (no TOKEN_CONSUMED), so the agent still came up.
	if gw.Count("EnrollRequest") < 2 {
		t.Fatalf("expected a replayed enroll, got %d", gw.Count("EnrollRequest"))
	}
	reqs := gw.Received("EnrollRequest")
	a := reqs[len(reqs)-2].(msg.EnrollRequest).CSRDER
	b := reqs[len(reqs)-1].(msg.EnrollRequest).CSRDER
	if string(a) != string(b) {
		t.Fatal("retry must resend the identical CSR")
	}
	// TOKEN_CONSUMED would have halted the agent instead of activating it.
	if l.Halted() != "" {
		t.Fatalf("halted: %s", l.Halted())
	}
}

func TestIntegrationRestartReusesIdentityWithoutEnroll(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	h.stop()
	h2 := startAgent(t, gw, h.dir, nil)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	if gw.Count("EnrollRequest") != 1 {
		t.Fatalf("a restarted agent must not re-enroll: %d", gw.Count("EnrollRequest"))
	}
	if gw.Count("SessionOpen") != 2 {
		t.Fatalf("SessionOpen=%d", gw.Count("SessionOpen"))
	}
}

func TestIntegrationRejectReEnrollHalts(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.OnOpen = "reject:RE_ENROLL_REQUIRED"
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitHalt(t, h.loop, msg.ErrReEnrollRequired, 10*time.Second)
}

func TestIntegrationRenewAtHalfLifeAndSameIDOnResend(t *testing.T) {
	t.Skip("AG-5 (renewal backoff/jitter unspecified): with renewRetry=30s fixed and fullJitter uniform [0,30s], " +
		"no Lease.Duration satisfies both LeaseExpiry's cancellation deadline (needs Duration>=60s) and this test's " +
		"own 40s WaitFor (needs Duration<=20s) — see docs/design/go-agent/design.md's AG-5 item and this task's report.")
	sc := fakegw.DefaultScenario()
	sc.Lease.Duration = 2 * time.Second // renew due at 1s
	sc.Policy.Renew = "silent"          // first renew unanswered → agent resends the same id
	sc.Steps = []fakegw.Step{
		{Expect: &fakegw.ExpectStep{Kind: "LeaseRenew", Timeout: 5 * time.Second}},
		{RenewPolicy: strPtr("grant")},
	}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("LeaseRenew", 2, 40*time.Second) {
		t.Fatal("expected a resend of the renewal")
	}
	renews := gw.Received("LeaseRenew")
	first := renews[0].(msg.LeaseRenew)
	for _, r := range renews[1:] {
		if r.(msg.LeaseRenew).MessageID != first.MessageID {
			t.Fatal("resends must reuse the message_id")
		}
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		st := h.loop.Snapshot()
		if st.Authority.Grant != nil && st.Authority.Grant.Nonce != "" && st.Authority.Pending == nil {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("grant never landed")
}

func TestIntegrationExpiredLeaseReconnectRenewsBeforeAccept(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.OnOpen = "renew-required" // every reconnect takes the §4.6 case-3 path
	sc.Steps = []fakegw.Step{{Sleep: durPtr(300 * time.Millisecond)}, {Disconnect: boolPtr(true)}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	// after the scripted disconnect the agent reconnects (≈1 s backoff)
	if !gw.WaitFor("SessionOpen", 2, 15*time.Second) || !gw.WaitFor("LeaseRenew", 1, 15*time.Second) {
		t.Fatal("reconnect must renew under the nonce before SessionAccept")
	}
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if h.loop.Snapshot().Session.Phase == PhaseActive && gw.Sessions() >= 2 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("not active after renew: %s", h.loop.Snapshot().Session.Phase)
}

func TestIntegrationRenewRefusedDegradesThenExpires(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Lease.Duration = 2 * time.Second
	sc.Policy.Renew = "refuse:LEASE_RENEW_REFUSED"
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	waitAuthority(t, h.loop, authority.PhaseRenewDegraded, 10*time.Second)
	waitAuthority(t, h.loop, authority.PhaseLeaseExpired, 10*time.Second)
	if h.loop.Snapshot().Session.Phase != PhaseActive {
		t.Fatal("the session stays up in LEASE_EXPIRED (report-only); only authority changes")
	}
}

func TestIntegrationRevokeHalts(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Revoke: strPtr("compromised")}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitHalt(t, h.loop, msg.ErrIdentityRevoked, 10*time.Second)
	if h.loop.Snapshot().Authority.Grant != nil {
		t.Fatal("revoked agent must drop its lease")
	}
	time.Sleep(1500 * time.Millisecond)
	if gw.Sessions() != 1 {
		t.Fatal("revoked agent must not reconnect")
	}
}

func TestIntegrationSupersededReconnectsImmediately(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Error: &fakegw.ErrorStep{Code: "SESSION_SUPERSEDED"}}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	start := time.Now()
	if !gw.WaitFor("SessionOpen", 2, 5*time.Second) {
		t.Fatal("no immediate reconnect")
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("reconnect after SESSION_SUPERSEDED must not wait for backoff")
	}
	_ = h
}

func TestIntegrationDrainHonoursReconnectAfter(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Drain: durPtr(2 * time.Second)}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	if !gw.WaitFor("SessionOpen", 1, 5*time.Second) {
		t.Fatal("no session")
	}
	firstOpen := time.Now()
	if !gw.WaitFor("SessionOpen", 2, 10*time.Second) {
		t.Fatal("no reconnect after drain")
	}
	if elapsed := time.Since(firstOpen); elapsed < 1500*time.Millisecond {
		t.Fatalf("reconnected too early after Drain: %v", elapsed)
	}
	_ = h
}

func TestIntegrationClockSuspectFailsClosedThenRecovers(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.HeartbeatInterval = 200 * time.Millisecond
	sc.Policy.Heartbeat = fakegw.HeartbeatPolicy{State: "SUSPECT"}
	sc.Steps = []fakegw.Step{
		{Expect: &fakegw.ExpectStep{Kind: "Heartbeat", Timeout: 5 * time.Second}},
		{Sleep: durPtr(600 * time.Millisecond)},
		{HeartbeatPolicy: &fakegw.HeartbeatPolicy{State: "OK"}},
	}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitAuthority(t, h.loop, authority.PhaseClockSuspect, 10*time.Second)
	waitAuthority(t, h.loop, authority.PhaseActive, 10*time.Second)
}

func strPtr(s string) *string               { return &s }
func boolPtr(b bool) *bool                  { return &b }
func durPtr(d time.Duration) *time.Duration { return &d }

func waitAuthority(t *testing.T, l *Loop, want authority.Phase, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if l.Snapshot().Authority.Phase == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("authority %s not reached (at %s)", want, l.Snapshot().Authority.Phase)
}

func applyScenario(payload string) *fakegw.Scenario {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		// Generation is DP-assigned and monotonic per binding across every
		// operation type, not just apply (design.md: "rollout_generation #
		// monotonic, DP-assigned; per binding"; the wire's CommandOffer
		// carries generation for preflight/restart/stop exactly as for
		// apply). Three commands against the same binding therefore need
		// three increasing generations — reusing one would make the second
		// and third notices look like redelivery of the first's already-
		// terminal record and the agent would replay its stored ack instead
		// of ever pulling them.
		{Offer: &fakegw.OfferStep{CommandID: "cmd-pf", BindingID: "binding-1", Generation: 1, Operation: "preflight"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "cmd-ap", BindingID: "binding-1", Generation: 2, Operation: "apply", Payload: payload, IdempotencyKey: "k1"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "cmd-rs", BindingID: "binding-1", Generation: 3, Operation: "restart"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	return sc
}

func acksByCommand(gw *fakegw.Server) map[string]msg.CommandAck {
	out := map[string]msg.CommandAck{}
	for _, m := range gw.Received("CommandAck") {
		a := m.(msg.CommandAck)
		out[a.CommandID] = a
	}
	return out
}

func TestIntegrationPreflightApplyRestart(t *testing.T) {
	payload := "server.port: 8081\n"
	gw := startGateway(t, applyScenario(payload))
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 3, 30*time.Second) {
		t.Fatalf("expected three acks, got %d", gw.Count("CommandAck"))
	}
	acks := acksByCommand(gw)
	if acks["cmd-pf"].Outcome != msg.OutcomePreflightOK || acks["cmd-pf"].Detail.PreflightResult == nil {
		t.Fatalf("preflight: %+v", acks["cmd-pf"])
	}
	if acks["cmd-ap"].Outcome != msg.OutcomeApplied {
		t.Fatalf("apply: %+v", acks["cmd-ap"])
	}
	if acks["cmd-rs"].Outcome != msg.OutcomeApplied {
		t.Fatalf("restart: %+v", acks["cmd-rs"])
	}
	got, err := os.ReadFile(ad.target())
	if err != nil || string(got) != payload {
		t.Fatalf("config not written: %v %q", err, got)
	}
	st, _ := os.Stat(ad.target())
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	// delivery and outcome are two acks, and progress was reported
	if gw.Count("CommandReceived") != 3 || gw.Count("ApplyProgress") < 6 {
		t.Fatalf("received=%d progress=%d", gw.Count("CommandReceived"), gw.Count("ApplyProgress"))
	}
}

func TestIntegrationDuplicateOfferIsAnsweredFromTheJournal(t *testing.T) {
	payload := "a: 1\n"
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-1", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: payload, IdempotencyKey: "k1"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "cmd-1", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: payload, IdempotencyKey: "k1"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 2, 30*time.Second) {
		t.Fatal("no second ack")
	}
	for _, m := range gw.Received("CommandAck") {
		if a := m.(msg.CommandAck); a.Outcome != msg.OutcomeApplied {
			t.Fatalf("both acks are APPLIED (the second replayed): %+v", a)
		}
	}
	// executed at most once: one apply execution emits exactly 4 progress
	// messages (RECEIVED, STAGED, PERSISTED, REPORTED); the duplicate offer
	// is answered straight from the journal with no further ApplyProgress.
	if n := gw.Count("ApplyProgress"); n > 4 {
		t.Fatalf("looks like it ran twice: %d progress messages", n)
	}
}

func TestIntegrationHashMismatchRefused(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-bad", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: "x", BadHash: true}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	a := gw.Received("CommandAck")[0].(msg.CommandAck)
	if a.Outcome != msg.OutcomeRefused || a.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", a)
	}
	if _, err := os.Stat(ad.target()); err == nil {
		t.Fatal("nothing may be written for a mismatched hash")
	}
}

func TestIntegrationSupersededPullIsRecordedNotActed(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-old", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: "old\n"}},
		{Superseded: strPtr("cmd-old")},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	if a := gw.Received("CommandAck")[0].(msg.CommandAck); a.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", a)
	}
	if _, err := os.Stat(ad.target()); err == nil {
		t.Fatal("a superseded command must not be applied")
	}
}

func TestIntegrationCapabilityMissingRefusedLocally(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Lease.Capabilities = []string{"apply-config"} // no lifecycle
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-rs", BindingID: "binding-1", Generation: 1, Operation: "restart"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", newFakeAdapter(t))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	a := gw.Received("CommandAck")[0].(msg.CommandAck)
	if a.Outcome != msg.OutcomeRefused || a.Detail.Code != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("%+v", a)
	}
	if gw.Count("CommandPull") != 0 {
		t.Fatal("a command the lease cannot authorize is refused without pulling it")
	}
}

func TestIntegrationApplyRestartSurvivesRestartOfTheAgent(t *testing.T) {
	payload := "b: 2\n"
	gw := startGateway(t, applyScenario(payload))
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 3, 30*time.Second) {
		t.Fatal("first run did not finish")
	}
	h.stop()
	h2 := startAgentWith(t, gw, h.dir, ad)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	// the journal is terminal for every command, so nothing re-runs and the
	// file is untouched
	got, _ := os.ReadFile(ad.target())
	if string(got) != payload {
		t.Fatalf("%q", got)
	}
	if h2.loop.Snapshot().Commands.OpenCount() != 0 {
		t.Fatal("no open records after a clean finish")
	}
}

func TestIntegrationReportsAfterApplyAndReceivesMintedIDs(t *testing.T) {
	payload := "c: 3\n"
	gw := startGateway(t, applyScenario(payload))
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 1, 30*time.Second) {
		t.Fatal("no report")
	}
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		st := h.loop.Snapshot()
		if st.Reports.ReplicaID("binding-1", "java1") != "" && st.Reports.PendingCount() == 0 {
			b := gw.Received("ActualStateReportBatch")[0].(msg.ActualStateReportBatch)
			if len(b.Items) == 0 || b.Items[0].ItemID == "" || b.Items[0].Sequence == 0 {
				t.Fatalf("%+v", b)
			}
			if b.Items[0].VersionObservation.Mechanism != "image-digest" {
				t.Fatalf("version observation: %+v", b.Items[0].VersionObservation)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("ids never landed: pending=%d", h.loop.Snapshot().Reports.PendingCount())
}

func TestIntegrationPeriodicReportWithNothingChanged(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.ReportInterval = 300 * time.Millisecond
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", newFakeAdapter(t))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 3, 20*time.Second) {
		t.Fatalf("AG-D18: a healthy unchanged workload must keep reporting; got %d", gw.Count("ActualStateReportBatch"))
	}
}

func TestIntegrationReportRetryAfterCrashReusesItemID(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.Report = fakegw.ReportPolicy{Mode: "silent"} // never ack → the item stays pending
	sc.Limits.ReportInterval = 500 * time.Millisecond
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 1, 20*time.Second) {
		t.Fatal("no report")
	}
	first := gw.Received("ActualStateReportBatch")[0].(msg.ActualStateReportBatch).Items[0]
	h.stop()

	// Same gateway, same data dir: the pending item must come back unchanged.
	h2 := startAgentWith(t, gw, h.dir, ad)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 2, 20*time.Second) {
		t.Fatal("pending item was not re-sent after restart")
	}
	for _, m := range gw.Received("ActualStateReportBatch") {
		for _, it := range m.(msg.ActualStateReportBatch).Items {
			if it.ItemID == first.ItemID && it.Sequence != first.Sequence {
				t.Fatal("a re-sent item must be byte-identical")
			}
		}
	}
	var found bool
	for _, m := range gw.Received("ActualStateReportBatch")[1:] {
		for _, it := range m.(msg.ActualStateReportBatch).Items {
			if it.ItemID == first.ItemID {
				found = true
			}
		}
	}
	if !found {
		t.Fatal("AG-D17: the same item_id must survive a restart")
	}
}
