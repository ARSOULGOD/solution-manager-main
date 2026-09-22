package agent

import (
	"errors"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

func testEnv() reactor.Env {
	n := 0
	return reactor.Env{
		Now:    t0,
		NewID:  func() string { n++; return "id-" + string(rune('0'+n)) },
		Jitter: func(d time.Duration) time.Duration { return d },
		Limits: msg.Limits{HeartbeatInterval: 10 * time.Second},
	}
}

var cfg = Config{BootstrapEndpoint: "boot:443", AgentVersion: "0.1.0", Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}}

func has[T reactor.Effect](effs []reactor.Effect) (T, bool) {
	for _, e := range effs {
		if v, ok := e.(T); ok {
			return v, true
		}
	}
	var zero T
	return zero, false
}

func TestResumedNotEnrolledEnrolls(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseIdle}, reactor.Resumed{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseEnrolling {
		t.Fatal(s.Phase)
	}
	if e, ok := has[reactor.Enroll](effs); !ok || e.Endpoint != "boot:443" {
		t.Fatalf("want Enroll{boot:443}, got %+v", effs)
	}
}

func TestResumedEnrolledDials(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseIdle, Enrolled: true, Endpoint: "dp:443"}, reactor.Resumed{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseConnecting {
		t.Fatal(s.Phase)
	}
	if e, ok := has[reactor.Dial](effs); !ok || e.Endpoint != "dp:443" {
		t.Fatalf("want Dial{dp:443}, got %+v", effs)
	}
}

func TestEnrollResultPersistsEndpointAndDials(t *testing.T) {
	ev := reactor.EnrollResult{At: t0, Resp: &msg.EnrollResponse{AgentID: "a", ConnectEndpoint: "dp:443"}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseEnrolling, Endpoint: "boot:443"}, ev, testEnv(), SessionInputs{})
	if s.Phase != PhaseConnecting || !s.Enrolled || s.Endpoint != "dp:443" {
		t.Fatalf("%+v", s)
	}
	if p, ok := has[reactor.Persist](effs); !ok || p.Rec.Rel() != "session/endpoint" {
		t.Fatalf("endpoint not persisted: %+v", effs)
	}
	if _, ok := has[reactor.Dial](effs); !ok {
		t.Fatal("no Dial")
	}
}

func TestEnrollResultHaltCodeHalts(t *testing.T) {
	ev := reactor.EnrollResult{At: t0, Err: &msg.ProtocolError{Code: msg.ErrTokenConsumed}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseEnrolling}, ev, testEnv(), SessionInputs{})
	if s.Phase != PhaseHalted || s.HaltCode != msg.ErrTokenConsumed {
		t.Fatalf("%+v", s)
	}
	if h, ok := has[reactor.Halt](effs); !ok || h.Code != msg.ErrTokenConsumed {
		t.Fatal("no Halt effect")
	}
}

func TestEnrollResultTransportErrorRetriesWithBackoff(t *testing.T) {
	ev := reactor.EnrollResult{At: t0, Err: errors.New("dial tcp: connection refused")}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseEnrolling, Attempt: 2}, ev, testEnv(), SessionInputs{})
	if s.Phase != PhaseEnrolling || s.Attempt != 3 {
		t.Fatalf("%+v", s)
	}
	if a, ok := has[reactor.ArmTimer](effs); !ok || a.Kind != reactor.ReconnectDue || a.After != 4*time.Second {
		t.Fatalf("want ReconnectDue in 4s (1s·2^2), got %+v", effs)
	}
	s2, effs2 := reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.ReconnectDue}, testEnv(), SessionInputs{})
	if s2.Phase != PhaseEnrolling {
		t.Fatal(s2.Phase)
	}
	if _, ok := has[reactor.Enroll](effs2); !ok {
		t.Fatal("retry must re-enroll")
	}
}

func TestConnectedSendsSessionOpen(t *testing.T) {
	in := SessionInputs{Resume: msg.Resume{LastAckedGenerationByBinding: map[string]uint64{"b1": 4}}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseConnecting, Enrolled: true}, reactor.Connected{At: t0}, testEnv(), in)
	if s.Phase != PhaseOpening {
		t.Fatal(s.Phase)
	}
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no Send")
	}
	so, ok := snd.Msg.(msg.SessionOpen)
	if !ok || so.ProtocolVersion != 1 || so.AgentVersion != "0.1.0" || so.Resume.LastAckedGenerationByBinding["b1"] != 4 || so.AgentClock != t0 || so.MessageID == "" {
		t.Fatalf("%+v", so)
	}
}

func TestSessionAcceptActivatesAndArmsHeartbeat(t *testing.T) {
	acc := msg.SessionAccept{SessionID: "s1", AssignedTargetID: "tgt", Limits: msg.Limits{HeartbeatInterval: 7 * time.Second}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseOpening, Attempt: 5}, reactor.WireIn{At: t0, Msg: acc}, testEnv(), SessionInputs{})
	if s.Phase != PhaseActive || s.SessionID != "s1" || s.TargetID != "tgt" || s.Attempt != 0 || s.Limits.HeartbeatInterval != 7*time.Second {
		t.Fatalf("%+v", s)
	}
	if a, ok := has[reactor.ArmTimer](effs); !ok || a.Kind != reactor.HeartbeatDue || a.After != 7*time.Second {
		t.Fatalf("heartbeat not armed: %+v", effs)
	}
}

func TestSessionRejectReEnrollHalts(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseOpening}, reactor.WireIn{At: t0, Msg: msg.SessionReject{Code: msg.ErrReEnrollRequired}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseHalted {
		t.Fatal(s.Phase)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close")
	}
}

func TestErrorSupersededReconnectsNow(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSessionSuperseded}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatal(s.Phase)
	}
	a, ok := has[reactor.ArmTimer](effs)
	if !ok || a.Kind != reactor.ReconnectDue || a.After != 0 {
		t.Fatalf("want immediate reconnect, got %+v", effs)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close")
	}
}

func TestErrorRetryHonoursRetryAfter(t *testing.T) {
	ra := 42 * time.Second
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrInternalUnavailable, RetryAfter: &ra}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatal(s.Phase)
	}
	if a, _ := has[reactor.ArmTimer](effs); a.After != ra {
		t.Fatalf("retry_after not honoured: %+v", a)
	}
}

func TestErrorLeaseRenewRequiredIsNotSessionBusiness(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseOpening}, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrLeaseRenewRequired}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseOpening || len(effs) != 0 {
		t.Fatalf("session must leave LEASE_RENEW_REQUIRED to authority: %+v %+v", s, effs)
	}
}

func TestDisconnectedFromActiveBacksOff(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive, Attempt: 0}, reactor.Disconnected{At: t0, Cause: errors.New("eof")}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed || s.Attempt != 1 {
		t.Fatalf("%+v", s)
	}
	if a, _ := has[reactor.ArmTimer](effs); a.Kind != reactor.ReconnectDue || a.After != time.Second {
		t.Fatalf("%+v", a)
	}
	s2, effs2 := reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.ReconnectDue}, testEnv(), SessionInputs{})
	if s2.Phase != PhaseConnecting {
		t.Fatal(s2.Phase)
	}
	if _, ok := has[reactor.Dial](effs2); !ok {
		t.Fatal("no Dial on ReconnectDue")
	}
}

func TestDisconnectedInClosedIsIgnored(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseClosed, Attempt: 1}, reactor.Disconnected{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed || s.Attempt != 1 || len(effs) != 0 {
		t.Fatalf("a Close we issued must not re-arm: %+v %+v", s, effs)
	}
}

func TestDrainSchedulesReconnectAfter(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Drain{ReconnectAfter: 30 * time.Second}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseDraining {
		t.Fatal(s.Phase)
	}
	if a, _ := has[reactor.ArmTimer](effs); a.After != 30*time.Second {
		t.Fatalf("%+v", a)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close after draining sends")
	}
	s2, _ := reduceSession(cfg, s, reactor.Disconnected{At: t0}, testEnv(), SessionInputs{})
	if s2.Phase != PhaseClosed {
		t.Fatal(s2.Phase)
	}
}

func TestHeartbeatCadenceAndMissDetection(t *testing.T) {
	env := testEnv()
	s := SessionState{Phase: PhaseActive, Limits: env.Limits}
	var effs []reactor.Effect
	for i := 0; i < maxMissedHeartbeats; i++ {
		s, effs = reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env, SessionInputs{LeaseRemaining: time.Minute, JournalDepth: 2})
		snd, ok := has[reactor.Send](effs)
		if !ok {
			t.Fatalf("beat %d: no Send", i)
		}
		hb := snd.Msg.(msg.Heartbeat)
		if hb.LeaseRemainingAtSend != time.Minute || hb.JournalDepth != 2 {
			t.Fatalf("%+v", hb)
		}
		if a, _ := has[reactor.ArmTimer](effs); a.Kind != reactor.HeartbeatDue {
			t.Fatal("cadence not re-armed")
		}
	}
	s, effs = reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env, SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatalf("after %d unanswered beats the connection is dead: %s", maxMissedHeartbeats, s.Phase)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close")
	}
	s = SessionState{Phase: PhaseActive, MissedHeartbeats: 2}
	s, _ = reduceSession(cfg, s, reactor.WireIn{At: t0, Msg: msg.HeartbeatAck{}}, env, SessionInputs{})
	if s.MissedHeartbeats != 0 {
		t.Fatal("ack must reset the miss counter")
	}
}

func TestRevokeHalts(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Revoke{Reason: "compromised"}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseHalted || s.HaltCode != msg.ErrIdentityRevoked {
		t.Fatalf("%+v", s)
	}
	if _, ok := has[reactor.Halt](effs); !ok {
		t.Fatal("no Halt")
	}
}

func TestShutdownTearsDown(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.Shutdown{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatal(s.Phase)
	}
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no Teardown sent")
	}
	if _, ok := snd.Msg.(msg.Teardown); !ok {
		t.Fatal("not a Teardown")
	}
}

func TestBackoffCapsAtFiveMinutes(t *testing.T) {
	if backoffFor(0) != time.Second || backoffFor(3) != 8*time.Second || backoffFor(20) != 5*time.Minute {
		t.Fatal("backoff schedule wrong")
	}
}
