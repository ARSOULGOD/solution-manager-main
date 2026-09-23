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

func newState() State { return State{Journal: Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}} }

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
	base := newState()
	base.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepStaged})
	base.InFlight = "c1"

	// lease still ACTIVE → a blown deadline is an ordinary failure
	s1, effs1 := Reduce(base, reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs1); ack.Outcome != msg.OutcomeFailedNoEffect {
		t.Fatalf("%+v", ack)
	}
	_ = s1

	// lease left ACTIVE with a step in flight → APPLY_INDETERMINATE (AG-D5)
	s2, effs2 := Reduce(base, reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), auth{detail: msg.DetailLeaseNotActive})
	if ack := sent[msg.CommandAck](t, effs2); ack.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if r.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", r)
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
		{step: msg.StepReceived, wantDelete: true},               // discard; the DP redelivers
		{step: msg.StepStaged, wantRunOp: true},                  // verify temp and complete
		{step: msg.StepPersisted, wantRunOp: true},               // re-read, verify, re-report — never rewrite
		{step: msg.StepReported, wantAck: false},                 // closed
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
