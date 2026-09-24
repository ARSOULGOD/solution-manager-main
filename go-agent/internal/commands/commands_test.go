package commands

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

type auth struct {
	detail   msg.DetailCode
	deadline time.Time
	active   bool
}

func (a auth) RefusalDetail(msg.Operation, string, time.Time) msg.DetailCode { return a.detail }
func (a auth) StepDeadline(_ msg.Operation, start time.Time) time.Time {
	if a.deadline.IsZero() {
		return start.Add(2 * time.Minute)
	}
	return a.deadline
}
func (a auth) Active() bool { return a.active }

var okAuth = auth{detail: msg.DetailNone, active: true}

func env(now time.Time) reactor.Env {
	n := 0
	return reactor.Env{Now: now, NewID: func() string { n++; return "mid-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d }, Limits: msg.Limits{MaxMessageBytes: 1024}}
}

func newState() State {
	return State{Journal: Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}}
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

func sent[M msg.Outbound](t *testing.T, effs []reactor.Effect) M {
	t.Helper()
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if m, ok := s.Msg.(M); ok {
				return m
			}
		}
	}
	t.Fatalf("no %T sent in %+v", *new(M), effs)
	panic("unreachable")
}

func offer(gen uint64, payload string, op msg.Operation) msg.CommandOffer {
	return msg.CommandOffer{CommandID: "c1", Generation: gen, IdempotencyKey: "k1", BindingID: "b1", Operation: op,
		Payload: []byte(payload), ConfigSHA256: sha(payload), Deadline: t0.Add(5 * time.Minute)}
}

func sha(s string) string { return sha256Hex([]byte(s)) }

func TestNoticePullsWhenAdmissible(t *testing.T) {
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpApply}}, env(t0), okAuth)
	p := sent[msg.CommandPull](t, effs)
	if p.CommandID != "c1" {
		t.Fatal(p)
	}
	_ = s
}

func TestNoticeRefusedByLocalGate(t *testing.T) {
	a := auth{detail: msg.DetailOf(msg.ErrCapabilityMissing), active: true}
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpRestart}}, env(t0), a)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("%+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("a refused notice must not run anything")
	}
}

func TestNoticeBelowWatermarkRefuses(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 5, Step: msg.StepReported})
	_, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c9", BindingID: "b1", Generation: 4, Operation: msg.OpApply}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrStaleGeneration) {
		t.Fatalf("%+v", ack)
	}
}

func TestOfferJournalsReceivedBeforeAckAndRuns(t *testing.T) {
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: offer(1, "server.port: 1\n", msg.OpApply)}, env(t0), okAuth)
	// effect order is enforced by the loop, but Persist must be present with
	// the payload blob and the RECEIVED record.
	var persists []string
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			persists = append(persists, p.Rec.Rel())
		}
	}
	if len(persists) != 2 || persists[0] != PayloadRel("b1", 1) || persists[1] != "apply/b1/1.json" {
		t.Fatalf("payload then record: %v", persists)
	}
	if r := sent[msg.CommandReceived](t, effs); r.CommandID != "c1" {
		t.Fatal(r)
	}
	pr := sent[msg.ApplyProgress](t, effs)
	if pr.Step != msg.StepReceived {
		t.Fatal(pr)
	}
	op, ok := has[reactor.RunOp](effs)
	if !ok || op.PayloadRel != PayloadRel("b1", 1) || op.SHA256 != sha("server.port: 1\n") || !op.Deadline.Equal(t0.Add(2*time.Minute)) {
		t.Fatalf("%+v", op)
	}
	if s.InFlight != "c1" {
		t.Fatal("in flight not recorded")
	}
	rec, _ := s.Journal.Get("b1", 1)
	if rec.Step != msg.StepReceived || !rec.ReceivedAck {
		t.Fatalf("%+v", rec)
	}
}

func TestOfferHashMismatchRefuses(t *testing.T) {
	o := offer(1, "a", msg.OpApply)
	o.ConfigSHA256 = "deadbeef"
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("must not run")
	}
}

func TestOfferTooLargeRefuses(t *testing.T) {
	big := make([]byte, 2048)
	o := msg.CommandOffer{CommandID: "c1", Generation: 1, IdempotencyKey: "k", BindingID: "b1", Operation: msg.OpApply, Payload: big, ConfigSHA256: sha256Hex(big)}
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs); ack.Detail.Code != msg.DetailOf(msg.ErrPayloadTooLarge) {
		t.Fatalf("%+v", ack)
	}
}

func TestRedeliveryOfTerminalRecordReplaysStoredAck(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", IdempotencyKey: "k1",
		ConfigHash: sha("x"), Step: msg.StepReported, Outcome: msg.OutcomeApplied, AckSent: true, EffectiveHash: sha("x")})
	o := offer(1, "x", msg.OpApply)
	s2, effs := Reduce(s, reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeApplied {
		t.Fatalf("stored outcome must be replayed: %+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("a completed command must never run twice")
	}
	if s2.InFlight != "" {
		t.Fatal("no new work")
	}
}

func TestSameKeyDifferentHashIsProtocolError(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", IdempotencyKey: "k1",
		ConfigHash: sha("x"), Step: msg.StepReported, Outcome: msg.OutcomeApplied, AckSent: true})
	o := offer(1, "different", msg.OpApply)
	_, effs := Reduce(s, reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", ack)
	}
}

func TestSupersededErrorClosesRecord(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Step: msg.StepReceived})
	s2, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "c1"}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if !r.Terminal() || r.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", r)
	}
}

// A command can be superseded between the agent's CommandPull and the
// CommandOffer that would have journaled it: offer() only writes the
// RECEIVED record once the payload arrives, so the gateway can answer the
// pull with an Error instead. msg.Error carries a CorrelationID and nothing
// else — no binding, no generation — so wireError must correlate through
// the pending-pull map, not the journal, or the DP never gets an ack for a
// command it will never redeliver under this id.
func TestSupersededDuringPullIsAckedWithNoJournalRecord(t *testing.T) {
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 7, Operation: msg.OpApply}}, env(t0), okAuth)
	if p := sent[msg.CommandPull](t, effs); p.CommandID != "c1" {
		t.Fatal(p)
	}
	if _, ok := s.Journal.ByCommand("c1"); ok {
		t.Fatal("nothing is journaled before the offer arrives")
	}

	s2, effs2 := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "c1"}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs2)
	if ack.Outcome != msg.OutcomeSuperseded || ack.CommandID != "c1" || ack.Generation != 7 {
		t.Fatalf("%+v", ack)
	}
	if _, ok := s2.Journal.ByCommand("c1"); ok {
		t.Fatal("still nothing to journal: the command was never received")
	}
	if _, pending := s2.Pulling["c1"]; pending {
		t.Fatal("the pending-pull entry must be cleared once resolved")
	}
}

func TestErrorForUnknownCorrelationIsIgnored(t *testing.T) {
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "never-pulled"}}, env(t0), okAuth)
	if len(effs) != 0 {
		t.Fatalf("no journal record and no pending pull means nothing to ack: %+v", effs)
	}
}

func TestOpProgressJournalsStep(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Step: msg.StepReceived})
	s.InFlight = "c1"
	s2, effs := Reduce(s, reactor.OpProgress{At: t0, CommandID: "c1", Step: msg.StepStaged}, env(t0), okAuth)
	if _, ok := has[reactor.Persist](effs); !ok {
		t.Fatal("step must be journaled")
	}
	if pr := sent[msg.ApplyProgress](t, effs); pr.Step != msg.StepStaged {
		t.Fatal(pr)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if r.Step != msg.StepStaged {
		t.Fatal(r.Step)
	}
}

func TestOpFinishedAcksAndSweeps(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c0", Step: msg.StepReported, Outcome: msg.OutcomeApplied})
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 2, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepPersisted})
	s.InFlight = "c1"
	res := runtimeResult(msg.OutcomeApplied, sha("x"))
	s2, effs := Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: res}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeApplied || ack.Generation != 2 {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 2)
	if !r.Terminal() || !r.AckSent || r.EffectiveHash != sha("x") {
		t.Fatalf("%+v", r)
	}
	var deleted []string
	for _, e := range effs {
		if d, ok := e.(reactor.Delete); ok {
			deleted = append(deleted, d.Rel)
		}
	}
	// the finished generation's payload, and the superseded generation 1's dir
	if len(deleted) < 2 {
		t.Fatalf("sweep: %v", deleted)
	}
	if s2.InFlight != "" {
		t.Fatal("in flight must clear")
	}
}

func TestStepDeadlineWhileInFlightIsIndeterminateOnlyWhenLeaseLeftActive(t *testing.T) {
	// Each branch gets its own state: Journal.Records is a map, shared by
	// reference even across a by-value State copy, and terminate() mutates
	// it in place — reusing one `base` across two Reduce calls would let the
	// first call's terminate() silently close the record before the second
	// call ever ran, which the terminal guard (I1) now correctly no-ops on.
	newBase := func() State {
		s := newState()
		s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepStaged})
		s.InFlight = "c1"
		return s
	}

	// lease still ACTIVE → a blown deadline is an ordinary failure
	s1, effs1 := Reduce(newBase(), reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs1); ack.Outcome != msg.OutcomeFailedNoEffect {
		t.Fatalf("%+v", ack)
	}
	_ = s1

	// lease left ACTIVE with a step in flight → APPLY_INDETERMINATE (AG-D5)
	s2, effs2 := Reduce(newBase(), reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), auth{detail: msg.DetailLeaseNotActive})
	if ack := sent[msg.CommandAck](t, effs2); ack.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if r.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", r)
	}
}

// TestTerminateIsANoOpOnceTheRecordIsAlreadyTerminal drives the race I1
// describes: a step-deadline timer fires APPLY_INDETERMINATE (closing the
// record), and the operation it was timing out on finishes anyway and
// delivers its own OpFinished for the same command. Without a terminal
// guard, the second terminate() call would overwrite the record's outcome
// and send a second, contradictory ack (APPLIED after APPLY_INDETERMINATE)
// for a command already closed.
func TestTerminateIsANoOpOnceTheRecordIsAlreadyTerminal(t *testing.T) {
	base := newState()
	base.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepStaged})
	base.InFlight = "c1"

	// the deadline fires while the lease left ACTIVE: APPLY_INDETERMINATE closes the record
	s1, effs1 := Reduce(base, reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), auth{detail: msg.DetailLeaseNotActive})
	ack1 := sent[msg.CommandAck](t, effs1)
	if ack1.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", ack1)
	}

	// ...and then the same operation finishes anyway, racing the timer
	res := runtimeResult(msg.OutcomeApplied, sha("x"))
	s2, effs2 := Reduce(s1, reactor.OpFinished{At: t0, CommandID: "c1", Result: res}, env(t0), okAuth)
	if _, ok := has[reactor.Send](effs2); ok {
		t.Fatalf("terminate() must be a no-op once the record is already terminal, got effects: %+v", effs2)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if r.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("the already-terminal outcome must survive unchanged: %+v", r)
	}
}

func TestResumeTable(t *testing.T) {
	cases := []struct {
		step        msg.ProgressStep
		wantRunOp   bool
		wantDelete  bool
		wantAck     bool
		wantRecvAck bool
	}{
		{step: msg.StepReceived, wantDelete: true}, // discard; the DP redelivers
		{step: msg.StepStaged, wantRunOp: true},    // verify temp and complete
		{step: msg.StepPersisted, wantRunOp: true}, // re-read, verify, re-report — never rewrite
		{step: msg.StepReported, wantAck: false},   // closed
	}
	for _, c := range cases {
		s := newState()
		r := Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: c.step, ConfigHash: sha("x")}
		if c.step == msg.StepReported {
			r.Outcome, r.AckSent = msg.OutcomeApplied, true
		}
		s.Journal.Put(r)
		_, effs := Reduce(s, reactor.Resumed{At: t0}, env(t0), okAuth)
		_, gotRun := has[reactor.RunOp](effs)
		_, gotDel := has[reactor.Delete](effs)
		if gotRun != c.wantRunOp {
			t.Errorf("%s: RunOp=%v want %v", c.step, gotRun, c.wantRunOp)
		}
		if c.wantDelete && !gotDel {
			t.Errorf("%s: expected the partial to be discarded", c.step)
		}
	}
}

// A resumed step gets a fresh deadline, counted from its dispatch in DP time
// (spec §5.2). The one written before the crash is long past after any real
// outage — arming it fired at once and failed a step AG-D5 says must be
// replayed. With the DP clock 30s ahead, a wall-clock bug lands 30s off and
// the stale-deadline bug 12m off; both are caught.
func TestResumeArmsStepDeadlineUsingDPClockNotWallClock(t *testing.T) {
	s := newState()
	stale := t0.Add(-10 * time.Minute) // written before the crash
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply,
		Step: msg.StepStaged, ConfigHash: sha("x"), StepDeadline: stale})
	e := env(t0)
	e.Offset = 30 * time.Second // DP clock is ahead of the agent's wall clock
	_, effs := Reduce(s, reactor.Resumed{At: t0}, e, okAuth)
	op, ok := has[reactor.RunOp](effs)
	if !ok {
		t.Fatalf("the resumed step should dispatch: %+v", effs)
	}
	if want := e.DPNow().Add(2 * time.Minute); !op.Deadline.Equal(want) {
		t.Fatalf("resumed deadline = %v, want %v — from DP-time dispatch, not the wall clock and not the stale pre-crash deadline", op.Deadline, want)
	}
	timer, ok := has[reactor.ArmTimer](effs)
	if !ok || timer.Kind != reactor.StepDeadline || timer.After != 2*time.Minute {
		t.Fatalf("want a StepDeadline timer for the full ceiling, got %+v", timer)
	}
}

func TestResumeResendsUnacknowledgedTerminalOutcome(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Step: msg.StepReported,
		Outcome: msg.OutcomeApplied, AckSent: false})
	_, effs := Reduce(s, reactor.Resumed{At: t0}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs); ack.Outcome != msg.OutcomeApplied {
		t.Fatalf("%+v", ack)
	}
}

func TestLoadAndPersistedGeneration(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	_ = st.Save(store.JSON{RelPath: "apply/b1/4.json", Value: Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 4,
		CommandID: "c", Step: msg.StepReported, Outcome: msg.OutcomeApplied}})
	_ = st.Save(store.JSON{RelPath: "apply/b1/5.json", Value: Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 5,
		CommandID: "c2", Step: msg.StepStaged}})
	s, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	if s.PersistedGeneration("b1") != 4 {
		t.Fatalf("only a persisted generation counts: %d", s.PersistedGeneration("b1"))
	}
	if s.OpenCount() != 1 {
		t.Fatal(s.OpenCount())
	}
}

func runtimeResult(o msg.Outcome, hash string) runtime.OpResult {
	return runtime.OpResult{Outcome: o, EffectiveHash: hash, Facts: &runtime.ReplicaFacts{ReplicaRef: "r1", Incarnation: "abc"}}
}

// A command refused before anything is journaled — the dominant refusal path
// — must still carry its operation, so the sm_agent_commands_total{op} label
// reports the real operation instead of bucketing it under "unknown".
// refuse() writes no state at all, so this can only come from the ack itself.
func TestRefusedAckCarriesItsOperation(t *testing.T) {
	cases := []struct {
		name string
		auth Authority
		ev   reactor.Event
		want msg.Operation
	}{
		{"capability missing at notice", auth{detail: msg.DetailOf(msg.ErrCapabilityMissing), active: true},
			reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpRestart}},
			msg.OpRestart},
		{"hash mismatch at offer", okAuth,
			func() reactor.Event {
				o := offer(1, "x", msg.OpApply)
				o.ConfigSHA256 = "deadbeef"
				return reactor.WireIn{At: t0, Msg: o}
			}(),
			msg.OpApply},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, effs := Reduce(newState(), c.ev, env(t0), c.auth)
			ack := sent[msg.CommandAck](t, effs)
			if ack.Outcome != msg.OutcomeRefused {
				t.Fatalf("expected a refusal, got %+v", ack)
			}
			if ack.Operation != c.want {
				t.Fatalf("ack.Operation = %q, want %q — the metrics label would read \"unknown\"", ack.Operation, c.want)
			}
		})
	}
}

// The pull-stage error path has no journal record either, and wireError
// deletes its Pulling entry in the same reduction, so the operation likewise
// has to ride on the ack.
func TestSupersededDuringPullAckCarriesItsOperation(t *testing.T) {
	s := newState()
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{
		CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpStop}}, env(t0), okAuth)
	_, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "c1"}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", ack)
	}
	if ack.Operation != msg.OpStop {
		t.Fatalf("ack.Operation = %q, want %q", ack.Operation, msg.OpStop)
	}
}

func stopOffer(id string, gen uint64) msg.CommandOffer {
	return msg.CommandOffer{CommandID: id, Generation: gen, IdempotencyKey: "k-" + id, BindingID: "b1",
		Operation: msg.OpStop, Payload: []byte{}, ConfigSHA256: sha256Hex([]byte{})}
}

func runOps(effs []reactor.Effect) []string {
	var ids []string
	for _, x := range effs {
		if op, ok := x.(reactor.RunOp); ok {
			ids = append(ids, op.CommandID)
		}
	}
	return ids
}

func ackFor(effs []reactor.Effect, id string) (msg.CommandAck, bool) {
	for _, x := range effs {
		if snd, ok := x.(reactor.Send); ok {
			if a, ok := snd.Msg.(msg.CommandAck); ok && a.CommandID == id {
				return a, true
			}
		}
	}
	return msg.CommandAck{}, false
}

// §4.4 rule 1 lets a stop interleave with an outstanding apply, and the worker
// runs one op at a time. The second command must wait. It used to take over
// InFlight and the only StepDeadline timer, so the first command's deadline
// fired against the second — closing it before it had started — and its real
// result was then dropped by the terminal guard.
func TestInterleavedCommandWaitsForTheWorker(t *testing.T) {
	s := newState()
	s, effs := Reduce(s, reactor.WireIn{At: t0, Msg: offer(1, "x", msg.OpApply)}, env(t0), okAuth)
	if got := runOps(effs); len(got) != 1 || got[0] != "c1" {
		t.Fatalf("c1 should dispatch at once, got %v", got)
	}
	s, effs = Reduce(s, reactor.WireIn{At: t0, Msg: stopOffer("c2", 2)}, env(t0), okAuth)
	if got := runOps(effs); len(got) != 0 {
		t.Fatalf("c2 must not dispatch while c1 is running, got %v", got)
	}
	if _, ok := has[reactor.ArmTimer](effs); ok {
		t.Fatal("c2 must not re-arm the deadline timer c1 is running under")
	}
	if s.InFlight != "c1" {
		t.Fatalf("InFlight = %q, want the running command c1", s.InFlight)
	}

	// c1's deadline fires: it may only close c1, and closing a record does not free the worker
	s, effs = Reduce(s, reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), okAuth)
	if _, ok := ackFor(effs, "c2"); ok {
		t.Fatal("the deadline closed c2, a command that never started")
	}
	if _, ok := ackFor(effs, "c1"); !ok {
		t.Fatal("the deadline should close c1, the running command")
	}
	if got := runOps(effs); len(got) != 0 || s.InFlight != "c1" {
		t.Fatalf("c2 must wait until the worker reports c1 finished: runs=%v InFlight=%q", got, s.InFlight)
	}

	// only when the worker finishes c1 does c2 start, with a deadline from its own start
	later := t0.Add(3 * time.Minute)
	s, effs = Reduce(s, reactor.OpFinished{At: later, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect}}, env(later), okAuth)
	op, ok := has[reactor.RunOp](effs)
	if !ok || op.CommandID != "c2" || s.InFlight != "c2" {
		t.Fatalf("c2 should dispatch once the worker is free: %+v", effs)
	}
	if want := later.Add(2 * time.Minute); !op.Deadline.Equal(want) {
		t.Fatalf("c2 deadline = %v, want %v — counted from dispatch (spec §5.2), not from offer", op.Deadline, want)
	}

	// c2 really succeeds, and the DP is told so
	_, effs = Reduce(s, reactor.OpFinished{At: later, CommandID: "c2", Result: runtime.OpResult{Outcome: msg.OutcomeApplied}}, env(later), okAuth)
	if a, ok := ackFor(effs, "c2"); !ok || a.Outcome != msg.OutcomeApplied {
		t.Fatalf("c2's real outcome must reach the DP, got %+v", a)
	}
}

// Closing a command that is still queued — superseded while it waited — must
// not cancel the one deadline timer, which belongs to the running command.
func TestSupersedingAQueuedCommandLeavesTheRunningDeadlineAlone(t *testing.T) {
	s := newState()
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: offer(1, "x", msg.OpApply)}, env(t0), okAuth)
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: stopOffer("c2", 2)}, env(t0), okAuth)
	s, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "c2"}}, env(t0), okAuth)
	if a, ok := ackFor(effs, "c2"); !ok || a.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("c2 should be recorded SUPERSEDED, got %+v", a)
	}
	if _, ok := has[reactor.CancelTimer](effs); ok {
		t.Fatal("closing queued c2 cancelled the deadline timer c1 is running under")
	}
	_, effs = Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeApplied}}, env(t0), okAuth)
	if got := runOps(effs); len(got) != 0 {
		t.Fatalf("a superseded command must never be dispatched, got %v", got)
	}
}

// Admission is re-checked before each step (spec §5.2), not only at offer: a
// command can wait long enough behind another for the lease to stop admitting it.
func TestQueuedCommandIsReAdmittedAtDispatch(t *testing.T) {
	s := newState()
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: offer(1, "x", msg.OpApply)}, env(t0), okAuth)
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: stopOffer("c2", 2)}, env(t0), okAuth)
	blocked := auth{detail: msg.DetailAdmissionBlocked, active: true}
	_, effs := Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeApplied}}, env(t0), blocked)
	if got := runOps(effs); len(got) != 0 {
		t.Fatalf("c2 must not run once admission fails, got %v", got)
	}
	if a, ok := ackFor(effs, "c2"); !ok || a.Outcome != msg.OutcomeRefused || a.Detail.Code != msg.DetailAdmissionBlocked {
		t.Fatalf("c2 should be refused ADMISSION_BLOCKED at dispatch, got %+v", a)
	}
}

// A resumed STAGED step may already have changed the target, so it must not be
// refused (that would report no effect for an effect that may exist) and must
// not run without an ACTIVE lease (no config write outside ACTIVE). It is held
// and dispatched once the lease admits it — AG-D5's "resolved on the next
// ACTIVE session by replaying the journal".
func TestResumeHoldsAStartedStepUntilTheLeaseAdmitsIt(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1",
		Operation: msg.OpApply, Step: msg.StepStaged, ConfigHash: sha("x")})
	inactive := auth{detail: msg.DetailLeaseNotActive}
	s, effs := Reduce(s, reactor.Resumed{At: t0}, env(t0), inactive)
	if got := runOps(effs); len(got) != 0 {
		t.Fatalf("no config write without an ACTIVE lease, got %v", got)
	}
	if _, ok := ackFor(effs, "c1"); ok {
		t.Fatal("a started step must be held, not refused")
	}
	_, effs = Reduce(s, reactor.WireIn{At: t0, Msg: msg.SessionAccept{}}, env(t0), okAuth)
	if got := runOps(effs); len(got) != 1 || got[0] != "c1" {
		t.Fatalf("the held step should dispatch once the lease admits it, got %v", got)
	}
}

// Resume must not depend on map iteration order, and must hand the worker one
// step at a time — lowest generation first, so a binding's generations stay ordered.
func TestResumeDispatchesInGenerationOrderOneAtATime(t *testing.T) {
	s := newState()
	for _, r := range []Record{
		{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 3, CommandID: "c3", Operation: msg.OpApply, Step: msg.StepStaged, ConfigHash: sha("x")},
		{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 2, CommandID: "c2", Operation: msg.OpApply, Step: msg.StepPersisted, ConfigHash: sha("x")},
	} {
		s.Journal.Put(r)
	}
	s, effs := Reduce(s, reactor.Resumed{At: t0}, env(t0), okAuth)
	if got := runOps(effs); len(got) != 1 || got[0] != "c2" {
		t.Fatalf("want exactly the lowest generation (c2) dispatched, got %v", got)
	}
	_, effs = Reduce(s, reactor.OpFinished{At: t0, CommandID: "c2", Result: runtime.OpResult{Outcome: msg.OutcomeApplied}}, env(t0), okAuth)
	if got := runOps(effs); len(got) != 1 || got[0] != "c3" {
		t.Fatalf("c3 should follow once c2 finishes, got %v", got)
	}
}
