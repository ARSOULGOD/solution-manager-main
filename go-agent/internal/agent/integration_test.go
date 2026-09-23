//go:build integration

package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
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

func strPtr(s string) *string              { return &s }
func boolPtr(b bool) *bool                 { return &b }
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
