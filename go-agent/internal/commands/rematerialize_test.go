package commands

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// AG-D27 item 4: a REMATERIALIZE offer without its precondition is a protocol
// error, never an unconditional write.
func TestRematerializeWithoutPreconditionIsRefused(t *testing.T) {
	o := offer(1, "a: 1\n", msg.OpRematerialize)
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("must not run")
	}
}

// The precondition travels with the dispatched step (and the journal record, so
// a replay after a crash re-evaluates it).
func TestRematerializeCarriesItsPreconditionToTheWorkerAndTheJournal(t *testing.T) {
	want := sha("old")
	o := offer(1, "a: 1\n", msg.OpRematerialize)
	o.ExpectedCurrentSHA256 = &want
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	op, ok := has[reactor.RunOp](effs)
	if !ok || op.Op != msg.OpRematerialize || op.ExpectedCurrentSHA256 == nil || *op.ExpectedCurrentSHA256 != want {
		t.Fatalf("%+v", op)
	}
	r, _ := s.Journal.Get("b1", 1)
	if r.ExpectedCurrentSHA256 == nil || *r.ExpectedCurrentSHA256 != want {
		t.Fatalf("the journal record keeps the precondition: %+v", r)
	}
}

// An empty expectation ("must be absent") is present, not absent.
func TestRematerializeEmptyExpectationIsAPresentField(t *testing.T) {
	empty := ""
	o := offer(1, "a: 1\n", msg.OpRematerialize)
	o.ExpectedCurrentSHA256 = &empty
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	if op, ok := has[reactor.RunOp](effs); !ok || op.ExpectedCurrentSHA256 == nil {
		t.Fatalf("%+v", effs)
	}
}

// AG-D27 item 2, 5: the ack of an APPLY, a REMATERIALIZE and a RESTART carries the
// container's StartedAt (host clock, untouched); PREFLIGHT and STOP carry none; a
// never-started container carries none rather than 0001-01-01.
func TestAckCarriesContainerStartedAtOnApplyRematerializeAndRestart(t *testing.T) {
	started := time.Date(2026, 10, 10, 8, 0, 0, 0, time.UTC)
	cases := []struct {
		op      msg.Operation
		started time.Time
		want    bool
	}{
		{msg.OpApply, started, true},
		{msg.OpRematerialize, started, true},
		{msg.OpRestart, started, true},
		{msg.OpPreflight, started, false},
		{msg.OpStop, started, false},
		{msg.OpApply, time.Time{}, false},
	}
	for _, c := range cases {
		s := newState()
		s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: c.op, Step: msg.StepPersisted})
		s.InFlight = "c1"
		res := runtime.OpResult{Outcome: msg.OutcomeApplied, Facts: &runtime.ReplicaFacts{ReplicaRef: "r", StartedAt: c.started, Running: true}}
		s2, effs := Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: res}, env(t0), okAuth)
		ack := sent[msg.CommandAck](t, effs)
		if got := ack.ContainerStartedAt != nil; got != c.want {
			t.Errorf("%s started=%v: ack carries it = %v, want %v", c.op, c.started, got, c.want)
		}
		if c.want && !ack.ContainerStartedAt.Equal(started) {
			t.Errorf("%s: the host clock value must be reported untouched: %v", c.op, ack.ContainerStartedAt)
		}
		// a replay of the stored outcome says the same thing
		r, _ := s2.Journal.Get("b1", 1)
		if c.want && (r.ContainerStartedAt == nil || !r.ContainerStartedAt.Equal(started)) {
			t.Errorf("%s: the journal must keep the value for a replayed ack: %+v", c.op, r)
		}
	}
}

// AG-D29 item 5: ERROR_CODE_RENDER_CHANGED answering a CommandPull is a terminal
// refusal of that command.
func TestRenderChangedAnsweringAPullIsATerminalRefusal(t *testing.T) {
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpApply}}, env(t0), okAuth)
	_ = sent[msg.CommandPull](t, effs)
	_, effs = Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrRenderChanged, CorrelationID: "c1"}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrRenderChanged) {
		t.Fatalf("%+v", ack)
	}
}
