package agent

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
)

func TestReduceFeedsLeaseRemainingIntoHeartbeat(t *testing.T) {
	env := testEnv()
	g := msg.LeaseGrant{CnfThumbprint: "leaf", IssuedAt: t0.Add(-10 * time.Minute), ExpiresAt: t0.Add(50 * time.Minute), Nonce: "n"}
	st := State{
		Session:   SessionState{Phase: PhaseActive, Limits: env.Limits},
		Authority: authority.State{Phase: authority.PhaseActive, Grant: &g, LeafThumbprint: "leaf"},
	}
	_, effs := Reduce(cfg, st, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env)
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no heartbeat")
	}
	if hb := snd.Msg.(msg.Heartbeat); hb.LeaseRemainingAtSend != 50*time.Minute {
		t.Fatalf("lease remaining %v", hb.LeaseRemainingAtSend)
	}
}

type runOpEffect = reactor.RunOp
type deleteEffect = reactor.Delete

func resumedAt(t time.Time) reactor.Event { return reactor.Resumed{At: t} }

func sentMsg[M msg.Outbound](t *testing.T, effs []reactor.Effect) M {
	t.Helper()
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if m, ok := s.Msg.(M); ok {
				return m
			}
		}
	}
	t.Fatalf("no %T sent", *new(M))
	panic("unreachable")
}

func TestReduceSessionAcceptReachesBothReducers(t *testing.T) {
	env := testEnv()
	g := msg.LeaseGrant{CnfThumbprint: "leaf", IssuedAt: t0, ExpiresAt: t0.Add(time.Hour), Nonce: "n"}
	st := State{Session: SessionState{Phase: PhaseOpening}, Authority: authority.State{LeafThumbprint: "leaf"}}
	next, effs := Reduce(cfg, st, reactor.WireIn{At: t0, Msg: msg.SessionAccept{Lease: g, Limits: msg.Limits{HeartbeatInterval: time.Second}}}, env)
	if next.Session.Phase != PhaseActive || next.Authority.Phase != authority.PhaseActive {
		t.Fatalf("%s %s", next.Session.Phase, next.Authority.Phase)
	}
	kinds := map[reactor.TimerKind]bool{}
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok {
			kinds[a.Kind] = true
		}
	}
	if !kinds[reactor.HeartbeatDue] || !kinds[reactor.RenewDue] || !kinds[reactor.LeaseExpiry] {
		t.Fatalf("timers: %v", kinds)
	}
}
