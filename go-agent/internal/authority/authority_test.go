package authority

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

const leaf = "leafthumb"

func env(now time.Time, offset time.Duration) reactor.Env {
	n := 0
	return reactor.Env{Now: now, Offset: offset, NewID: func() string { n++; return "mid-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d }, Limits: msg.Limits{SkewTolerance: 30 * time.Second}}
}

func grant(issued time.Time, nonce string) msg.LeaseGrant {
	return msg.LeaseGrant{AgentID: "a", CnfThumbprint: leaf, TargetID: "t", BindingIDs: []string{"b1"},
		AgentRevocationEpoch: 1, FleetRevocationEpoch: 1, IssuedAt: issued, ExpiresAt: issued.Add(time.Hour), Audience: "dp", Nonce: nonce,
		Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle},
		VerbCeilings: map[msg.Operation]time.Duration{msg.OpApply: 2 * time.Minute, msg.OpRestart: 5 * time.Minute}, AdmissionMargin: 30 * time.Second,
		ServerClock: issued}
}

func has[T reactor.Effect](effs []reactor.Effect) (T, bool) {
	for _, e := range effs {
		if v, ok := e.(T); ok {
			return v, true
		}
	}
	var zero T
	return zero, false
}

func timer(effs []reactor.Effect, kind reactor.TimerKind) (time.Duration, bool) {
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == kind {
			return a.After, true
		}
	}
	return 0, false
}

func TestSessionAcceptActivatesPersistsAndArmsTimers(t *testing.T) {
	g := grant(t0, "n1")
	s, effs := Reduce(State{LeafThumbprint: leaf}, reactor.WireIn{At: t0, Msg: msg.SessionAccept{Lease: g}}, env(t0, 0))
	if s.Phase != PhaseActive || s.Grant == nil || s.Grant.Nonce != "n1" {
		t.Fatalf("%+v", s)
	}
	if p, ok := has[reactor.Persist](effs); !ok || p.Rec.Rel() != "session/lease.json" {
		t.Fatal("lease must be persisted")
	}
	if d, ok := timer(effs, reactor.RenewDue); !ok || d != 30*time.Minute {
		t.Fatalf("renew at 50%%: %v %v", d, ok)
	}
	if d, ok := timer(effs, reactor.LeaseExpiry); !ok || d != time.Hour {
		t.Fatalf("expiry timer: %v %v", d, ok)
	}
}

func TestTimersUseDPTime(t *testing.T) {
	g := grant(t0, "n1")
	// agent clock is 10 minutes behind DP: offset +10m; the lease is 30m old in DP time
	s, effs := Reduce(State{LeafThumbprint: leaf}, reactor.WireIn{At: t0.Add(20 * time.Minute), Msg: msg.SessionAccept{Lease: g}}, env(t0.Add(20*time.Minute), 10*time.Minute))
	if s.Phase != PhaseActive {
		t.Fatal(s.Phase)
	}
	if d, _ := timer(effs, reactor.RenewDue); d != 0 {
		t.Fatalf("renew already due in DP time, got %v", d)
	}
	if d, _ := timer(effs, reactor.LeaseExpiry); d != 30*time.Minute {
		t.Fatalf("expiry in 30m DP time, got %v", d)
	}
}

func TestRenewDueSendsRenewAndResendsSameID(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	s, effs := Reduce(s, reactor.TimerFired{At: t0.Add(30 * time.Minute), Kind: reactor.RenewDue}, env(t0.Add(30*time.Minute), 0))
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no LeaseRenew")
	}
	lr := snd.Msg.(msg.LeaseRenew)
	if lr.NonceConsumed != "n1" || lr.MessageID == "" || s.Pending == nil || s.Pending.MessageID != lr.MessageID {
		t.Fatalf("%+v %+v", lr, s.Pending)
	}
	if _, ok := has[reactor.Persist](effs); !ok {
		t.Fatal("pending renew must be persisted")
	}
	if d, ok := timer(effs, reactor.RenewDue); !ok || d != renewRetry {
		t.Fatal("unanswered renew must be retried")
	}
	s2, effs2 := Reduce(s, reactor.TimerFired{At: t0.Add(31 * time.Minute), Kind: reactor.RenewDue}, env(t0.Add(31*time.Minute), 0))
	lr2 := has2[msg.LeaseRenew](t, effs2)
	if lr2.MessageID != lr.MessageID || s2.Pending.MessageID != lr.MessageID {
		t.Fatal("resend must reuse the same message_id")
	}
}

func has2[M msg.Outbound](t *testing.T, effs []reactor.Effect) M {
	t.Helper()
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatalf("no Send")
	}
	m, ok := snd.Msg.(M)
	if !ok {
		t.Fatalf("Send is %T", snd.Msg)
	}
	return m
}

func TestLeaseGrantReplacesAndClearsPending(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}, LeafThumbprint: leaf}
	g2 := grant(t0.Add(30*time.Minute), "n2")
	s, effs := Reduce(s, reactor.WireIn{At: t0.Add(30 * time.Minute), Msg: g2}, env(t0.Add(30*time.Minute), 0))
	if s.Grant.Nonce != "n2" || s.Pending != nil || s.Phase != PhaseActive {
		t.Fatalf("%+v", s)
	}
	if d, _ := timer(effs, reactor.RenewDue); d != 30*time.Minute {
		t.Fatalf("renew re-armed from the new grant: %v", d)
	}
}

func TestSessionAcceptWithSameLeaseKeepsPending(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseLeaseExpired, Grant: &g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}, LeafThumbprint: leaf}
	s, _ = Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.SessionAccept{Lease: g}}, env(t0.Add(time.Minute), 0))
	if s.Pending == nil || s.Pending.MessageID != "m" {
		t.Fatal("failover returned the same lease; the pending renewal is still owed under the same id")
	}
}

func TestRenewRefusedDegrades(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}, LeafThumbprint: leaf}
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrLeaseRenewRefused, CorrelationID: "m"}}, env(t0, 0))
	if s.Phase != PhaseRenewDegraded || s.Pending != nil {
		t.Fatalf("%+v", s)
	}
	if s.Active() {
		t.Fatal("degraded is not active")
	}
}

func TestExpiryTimerExpires(t *testing.T) {
	g := grant(t0, "n1")
	for _, from := range []Phase{PhaseActive, PhaseRenewDegraded} {
		s := State{Phase: from, Grant: &g, LeafThumbprint: leaf}
		s, effs := Reduce(s, reactor.TimerFired{At: t0.Add(time.Hour), Kind: reactor.LeaseExpiry}, env(t0.Add(time.Hour), 0))
		if s.Phase != PhaseLeaseExpired {
			t.Fatalf("from %s: %s", from, s.Phase)
		}
		if c, ok := has[reactor.CancelTimer](effs); !ok || c.Kind != reactor.RenewDue {
			t.Fatal("renew timer must be cancelled on expiry")
		}
	}
}

func TestRenewRequiredWhenExpiredSendsRenew(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseLeaseExpired, Grant: &g, LeafThumbprint: leaf}
	s, effs := Reduce(s, reactor.WireIn{At: t0.Add(2 * time.Hour), Msg: msg.Error{Code: msg.ErrLeaseRenewRequired}}, env(t0.Add(2*time.Hour), 0))
	lr := has2[msg.LeaseRenew](t, effs)
	if lr.NonceConsumed != "n1" || s.Pending == nil {
		t.Fatalf("%+v", lr)
	}
}

func TestInvalidGrantsAreIgnored(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	bad := grant(t0.Add(time.Minute), "n2")
	bad.CnfThumbprint = "someone-else"
	s2, effs := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: bad}, env(t0.Add(time.Minute), 0))
	if s2.Grant.Nonce != "n1" || len(effs) != 0 {
		t.Fatal("wrong thumbprint must be ignored")
	}
	older := grant(t0.Add(time.Minute), "n3")
	older.AgentRevocationEpoch = 0
	s3, _ := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: older}, env(t0.Add(time.Minute), 0))
	if s3.Grant.Nonce != "n1" {
		t.Fatal("lower epoch must be ignored")
	}
}

func TestClockSuspectFailsClosedAndRecovers(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.HeartbeatAck{ClockState: msg.ClockSuspect}}, env(t0, 0))
	if s.Phase != PhaseClockSuspect || s.Active() {
		t.Fatal(s.Phase)
	}
	s, effs := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.HeartbeatAck{ClockState: msg.ClockOK}}, env(t0.Add(time.Minute), 0))
	if s.Phase != PhaseActive {
		t.Fatal(s.Phase)
	}
	if _, ok := timer(effs, reactor.RenewDue); !ok {
		t.Fatal("timers re-armed on recovery")
	}
	// offset beyond skew tolerance is suspect even if the DP says OK
	s, _ = Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.HeartbeatAck{ClockState: msg.ClockOK}}, env(t0.Add(time.Minute), 2*time.Minute))
	if s.Phase != PhaseClockSuspect {
		t.Fatal("offset > skew_tolerance must fail closed")
	}
}

func TestRevokeDropsLease(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	s, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Revoke{Reason: "x"}}, env(t0, 0))
	if s.Phase != PhaseRevoked || s.Grant != nil {
		t.Fatalf("%+v", s)
	}
	if d, ok := has[reactor.Delete](effs); !ok || d.Rel != "session/lease.json" {
		t.Fatal("lease record must be deleted")
	}
}

func TestAdmissionArithmetic(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	// apply needs 2m + 30s margin: admissible at T+57m29s, not at T+57m31s
	if !s.Admissible(msg.OpApply, t0.Add(57*time.Minute+29*time.Second)) {
		t.Fatal("should be admissible")
	}
	if s.Admissible(msg.OpApply, t0.Add(57*time.Minute+31*time.Second)) {
		t.Fatal("should be blocked")
	}
	if s.StepDeadline(msg.OpApply, t0.Add(59*time.Minute)) != t0.Add(time.Hour) {
		t.Fatal("deadline is capped by expires_at")
	}
	if s.StepDeadline(msg.OpApply, t0) != t0.Add(2*time.Minute) {
		t.Fatal("deadline is start + ceiling")
	}
	if s.RefusalDetail(msg.OpStop, "b1", t0) != msg.DetailNone || s.RefusalDetail(msg.OpApply, "nope", t0) != msg.DetailOf(msg.ErrBindingNotInLease) {
		t.Fatal("refusal detail")
	}
	s.Grant.Capabilities = []msg.Capability{msg.CapReportOnly}
	if s.RefusalDetail(msg.OpRestart, "b1", t0) != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatal("capability missing")
	}
	s.Phase = PhaseLeaseExpired
	if s.RefusalDetail(msg.OpApply, "b1", t0) != msg.DetailLeaseNotActive {
		t.Fatal("lease not active")
	}
}

func TestLoadAndResume(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	g := grant(t0, "n1")
	if err := st.Save(store.JSON{RelPath: "session/lease.json", Value: Record{SchemaVersion: 1, Grant: g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}}}); err != nil {
		t.Fatal(err)
	}
	s, err := Load(st, leaf)
	if err != nil || s.Grant == nil || s.Pending == nil {
		t.Fatalf("%v %+v", err, s)
	}
	live, effs := Reduce(s, reactor.Resumed{At: t0.Add(10 * time.Minute)}, env(t0.Add(10*time.Minute), 0))
	if live.Phase != PhaseActive {
		t.Fatal(live.Phase)
	}
	if d, _ := timer(effs, reactor.RenewDue); d != 20*time.Minute {
		t.Fatalf("renew from stored grant: %v", d)
	}
	dead, _ := Reduce(s, reactor.Resumed{At: t0.Add(2 * time.Hour)}, env(t0.Add(2*time.Hour), 0))
	if dead.Phase != PhaseLeaseExpired {
		t.Fatal(dead.Phase)
	}
	empty, _ := Load(store.MustOpen(t.TempDir()), leaf)
	none, _ := Reduce(empty, reactor.Resumed{At: t0}, env(t0, 0))
	if none.Phase != PhaseNone {
		t.Fatal(none.Phase)
	}
}
