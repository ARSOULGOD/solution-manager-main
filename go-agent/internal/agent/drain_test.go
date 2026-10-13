package agent

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/reports"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// AGW-D18 planned drain: the agent keeps its connection open while work is in
// flight, takes no new work, answers DrainComplete once quiet (or at 80% of the
// deadline), and reconnects at the gateway-assigned time, measured from the
// Drain, not from completion.

func activeState() State {
	g := msg.LeaseGrant{CnfThumbprint: "leaf", IssuedAt: t0.Add(-time.Minute), ExpiresAt: t0.Add(time.Hour), Nonce: "n",
		Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}, BindingIDs: []string{"b"},
		VerbCeilings: map[msg.Operation]time.Duration{msg.OpApply: time.Minute}}
	return State{
		Session:   SessionState{Phase: PhaseActive, Limits: msg.Limits{HeartbeatInterval: time.Hour}},
		Authority: authority.State{Phase: authority.PhaseActive, Grant: &g, LeafThumbprint: "leaf"},
		Commands:  commands.State{Journal: commands.Journal{Records: map[string]commands.Record{}, Watermark: map[string]uint64{}}},
		Reports:   reports.State{},
	}
}

func drainMsg(reconnect, deadline time.Duration) msg.Drain {
	return msg.Drain{Envelope: msg.Envelope{MessageID: "drain-1"}, Reason: "planned-restart", ReconnectAfter: reconnect, Deadline: deadline}
}

func TestDrainWithDeadlineWhenIdleCompletesAtOnceAndKeepsTheStagger(t *testing.T) {
	env := testEnv()
	next, effs := Reduce(cfg, activeState(), reactor.WireIn{At: t0, Msg: drainMsg(7*time.Second, 20*time.Second)}, env)
	dc := sentMsg[msg.DrainComplete](t, effs)
	if dc.InReplyTo != "drain-1" || dc.AbandonedCommands != 0 {
		t.Fatalf("%+v", dc)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("an idle agent closes after DrainComplete")
	}
	var armed time.Duration = -1
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReconnectDue {
			armed = a.After
		}
	}
	if armed != 7*time.Second {
		t.Fatalf("reconnect must follow the assigned stagger, got %v", armed)
	}
	if next.Session.Quiescing || next.Session.Phase != PhaseClosed {
		t.Fatalf("a completed drain leaves the session Closed so the reconnect timer can dial: %+v", next.Session)
	}
}

func TestDrainWaitsForInFlightWorkThenAnswersAndSubtractsElapsedFromTheStagger(t *testing.T) {
	st := activeState()
	st.Commands.InFlight = "cmd-1"
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-1", BindingID: "b", RolloutGeneration: 1, Step: msg.StepStaged, Operation: msg.OpApply})

	env := testEnv()
	st, effs := Reduce(cfg, st, reactor.WireIn{At: t0, Msg: drainMsg(10*time.Second, 30*time.Second)}, env)
	if !st.Session.Quiescing {
		t.Fatal("must keep the connection open while a command is in flight")
	}
	if _, sent := has[reactor.Send](effs); sent {
		t.Fatalf("nothing is sent while work is in flight: %+v", effs)
	}
	if _, closed := has[reactor.Close](effs); closed {
		t.Fatal("the connection must stay open so the in-flight ack can reach the gateway")
	}
	var forced time.Duration
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.DrainDeadline {
			forced = a.After
		}
	}
	if forced != 24*time.Second {
		t.Fatalf("the agent forces completion at 80%% of the deadline, got %v", forced)
	}

	// the worker finishes 4s in; the ack is sent by the commands reducer, then the drain completes
	env.Now = t0.Add(4 * time.Second)
	res := runtime.OpResult{Outcome: msg.OutcomeApplied}
	st, effs = Reduce(cfg, st, reactor.OpFinished{At: env.Now, CommandID: "cmd-1", Result: res}, env)
	ack := sentMsg[msg.CommandAck](t, effs)
	if ack.CommandID != "cmd-1" || ack.Outcome != msg.OutcomeApplied {
		t.Fatalf("%+v", ack)
	}
	sentMsg[msg.DrainComplete](t, effs)
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReconnectDue && a.After != 6*time.Second {
			t.Fatalf("the 4s spent finishing counts toward the 10s stagger; got %v", a.After)
		}
	}
	if st.Session.Quiescing {
		t.Fatal("drain complete")
	}
}

func TestDrainDeadlineForcesCompletionAndCountsAbandonedCommands(t *testing.T) {
	st := activeState()
	st.Commands.InFlight = "cmd-1"
	st.Commands.Queue = []string{"cmd-2"}
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-1", BindingID: "b", RolloutGeneration: 1, Step: msg.StepStaged, Operation: msg.OpApply})
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-2", BindingID: "b", RolloutGeneration: 2, Step: msg.StepReceived, Operation: msg.OpApply})

	env := testEnv()
	st, _ = Reduce(cfg, st, reactor.WireIn{At: t0, Msg: drainMsg(time.Second, 10*time.Second)}, env)
	env.Now = t0.Add(8 * time.Second)
	st, effs := Reduce(cfg, st, reactor.TimerFired{At: env.Now, Kind: reactor.DrainDeadline}, env)
	dc := sentMsg[msg.DrainComplete](t, effs)
	if dc.AbandonedCommands != 2 {
		t.Fatalf("abandoned %d, want 2", dc.AbandonedCommands)
	}
	if st.Session.Quiescing {
		t.Fatal("forced completion ends the quiesce")
	}
}

func TestDrainTakesNoNewWorkButStillAnswersHeartbeats(t *testing.T) {
	st := activeState()
	st.Commands.InFlight = "cmd-1"
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-1", BindingID: "b", RolloutGeneration: 1, Step: msg.StepStaged, Operation: msg.OpApply})
	env := testEnv()
	st, _ = Reduce(cfg, st, reactor.WireIn{At: t0, Msg: drainMsg(time.Second, 30*time.Second)}, env)

	st, effs := Reduce(cfg, st, reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "late", BindingID: "b", Generation: 9, Operation: msg.OpApply}}, env)
	if _, pulled := has[reactor.Send](effs); pulled {
		t.Fatalf("a draining agent must not pull new work: %+v", effs)
	}
	if _, ok := st.Commands.Pulling["late"]; ok {
		t.Fatal("no pull is recorded")
	}
	st, effs = Reduce(cfg, st, reactor.WireIn{At: t0, Msg: msg.CommandOffer{CommandID: "late", BindingID: "b", Generation: 9, Operation: msg.OpApply}}, env)
	if _, ok := st.Commands.Journal.ByCommand("late"); ok || len(effs) != 0 {
		t.Fatalf("a late offer is ignored; the DP re-notifies it on the next session: %+v", effs)
	}
	_, effs = Reduce(cfg, st, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env)
	sentMsg[msg.Heartbeat](t, effs)
}

func TestDrainWithoutDeadlineKeepsTheLegacyCloseNow(t *testing.T) {
	env := testEnv()
	next, effs := Reduce(cfg, activeState(), reactor.WireIn{At: t0, Msg: drainMsg(3*time.Second, 0)}, env)
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("legacy drain closes at once")
	}
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if _, isDC := s.Msg.(msg.DrainComplete); isDC {
				t.Fatal("legacy drain sends no DrainComplete")
			}
		}
	}
	if next.Session.Quiescing || next.Session.Phase != PhaseDraining {
		t.Fatalf("%+v", next.Session)
	}
}

func TestConnectionLostWhileQuiescingStillReconnectsOnSchedule(t *testing.T) {
	st := activeState()
	st.Commands.InFlight = "cmd-1"
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-1", BindingID: "b", RolloutGeneration: 1, Step: msg.StepStaged, Operation: msg.OpApply})
	env := testEnv()
	st, _ = Reduce(cfg, st, reactor.WireIn{At: t0, Msg: drainMsg(9*time.Second, 30*time.Second)}, env)
	env.Now = t0.Add(2 * time.Second)
	st, effs := Reduce(cfg, st, reactor.Disconnected{At: env.Now}, env)
	var armed time.Duration = -1
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReconnectDue {
			armed = a.After
		}
	}
	if armed != 7*time.Second || st.Session.Phase != PhaseClosed || st.Session.Quiescing {
		t.Fatalf("armed=%v session=%+v", armed, st.Session)
	}
}

func TestRevokeDuringQuiesceHaltsAndSendsNothing(t *testing.T) {
	st := activeState()
	st.Commands.InFlight = "cmd-1"
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-1", BindingID: "b", RolloutGeneration: 1, Step: msg.StepStaged, Operation: msg.OpApply})
	env := testEnv()
	st, _ = Reduce(cfg, st, reactor.WireIn{At: t0, Msg: drainMsg(time.Second, 30*time.Second)}, env)
	st, effs := Reduce(cfg, st, reactor.WireIn{At: t0, Msg: msg.Revoke{Reason: "operator"}}, env)
	if st.Session.Phase != PhaseHalted || st.Session.Quiescing {
		t.Fatalf("%+v", st.Session)
	}
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if _, isDC := s.Msg.(msg.DrainComplete); isDC {
				t.Fatal("a revoked agent must not announce a completed drain")
			}
		}
	}
}

// The real-agent matrix (M08) found this: when finishing in-flight work took longer than the assigned stagger, the
// reconnect timer was armed at zero, the loop suppressed the close echo (it treats a zero-delay reconnect as an
// immediate redial), and a session left in DRAINING ignored the timer — the agent never came back.
func TestDrainThatOutlastsItsStaggerReconnectsAtOnceAndFromClosed(t *testing.T) {
	st := activeState()
	st.Commands.InFlight = "cmd-1"
	st.Commands.Journal.Put(commands.Record{CommandID: "cmd-1", BindingID: "b", RolloutGeneration: 1, Step: msg.StepStaged, Operation: msg.OpApply})
	env := testEnv()
	st, _ = Reduce(cfg, st, reactor.WireIn{At: t0, Msg: drainMsg(time.Second, 30*time.Second)}, env)

	env.Now = t0.Add(1400 * time.Millisecond) // the work took longer than the 1s stagger
	st, effs := Reduce(cfg, st, reactor.OpFinished{At: env.Now, CommandID: "cmd-1", Result: runtime.OpResult{Outcome: msg.OutcomeApplied}}, env)
	sentMsg[msg.DrainComplete](t, effs)
	var after time.Duration = -1
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReconnectDue {
			after = a.After
		}
	}
	if after != 0 {
		t.Fatalf("the stagger is already spent; reconnect at once, got %v", after)
	}
	if st.Session.Phase != PhaseClosed {
		t.Fatalf("must be Closed, not Draining, when the close echo is suppressed: %s", st.Session.Phase)
	}
	// the timer fires without any Disconnected echo having been delivered
	st, effs = Reduce(cfg, st, reactor.TimerFired{At: env.Now, Kind: reactor.ReconnectDue}, env)
	if _, ok := has[reactor.Dial](effs); !ok || st.Session.Phase != PhaseConnecting {
		t.Fatalf("the agent must dial again: phase=%s effects=%+v", st.Session.Phase, effs)
	}
}

// Two live agents with one identity supersede each other forever. The first supersession is a failover and is answered
// at once; a run of them backs off, and a quiet period resets it.
func TestRepeatedSupersessionBacksOffAndAQuietPeriodResetsIt(t *testing.T) {
	superseded := func(st State, at time.Time) (State, time.Duration) {
		env := testEnv()
		env.Now = at
		st, effs := Reduce(cfg, st, reactor.WireIn{At: at, Msg: msg.Error{Code: msg.ErrSessionSuperseded}}, env)
		for _, e := range effs {
			if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReconnectDue {
				return st, a.After
			}
		}
		t.Fatal("no reconnect timer")
		return st, 0
	}
	st := activeState()
	var d time.Duration
	st, d = superseded(st, t0)
	if d != 0 {
		t.Fatalf("a first supersession is a failover: reconnect at once, got %v", d)
	}
	st, d = superseded(st, t0.Add(time.Second))
	if d != 2*time.Second { // run 1: backoff 1s<<1 (the test jitter returns the bound)
		t.Fatalf("second in a run: %v", d)
	}
	st, d = superseded(st, t0.Add(3*time.Second))
	if d != 4*time.Second {
		t.Fatalf("third in a run: %v", d)
	}
	st, d = superseded(st, t0.Add(10*time.Minute))
	if d != 0 {
		t.Fatalf("a supersession after a long quiet period is a failover again: %v", d)
	}
	_ = st
}
