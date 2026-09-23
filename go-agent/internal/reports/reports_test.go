package reports

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

func env(now time.Time) reactor.Env {
	n := 0
	return reactor.Env{Now: now, NewID: func() string { n++; return "id-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d }, Limits: msg.Limits{ReportInterval: time.Minute}}
}

func obs(gen uint64) Observation {
	return Observation{BindingID: "b1", ReplicaRef: "java1", Incarnation: "abc123", PersistedGeneration: gen,
		EffectiveHash: "h", VersionObservation: msg.VersionObservation{Mechanism: "image-digest", Value: "sha256:x", Confidence: "low"}}
}

func newState() State {
	return State{
		Cursor:   Cursor{SchemaVersion: 1, Sequences: map[string]uint64{}},
		Pending:  Pending{SchemaVersion: 1},
		Registry: Registry{SchemaVersion: 1, ReplicaIDs: map[string]string{}, IncarnationIDs: map[string]string{}},
	}
}

func sentBatch(t *testing.T, effs []reactor.Effect) msg.ActualStateReportBatch {
	t.Helper()
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if b, ok := s.Msg.(msg.ActualStateReportBatch); ok {
				return b
			}
		}
	}
	t.Fatal("no batch sent")
	panic("unreachable")
}

func persisted(effs []reactor.Effect) []string {
	var out []string
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			out = append(out, p.Rec.Rel())
		}
	}
	return out
}

func TestObservationMintsItemAndPersistsCursorBeforeSending(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(3)}, env(t0))
	rels := persisted(effs)
	if len(rels) < 2 || rels[0] != relCursor || rels[1] != relPending {
		t.Fatalf("cursor then pending, before the send: %v", rels)
	}
	b := sentBatch(t, effs)
	if len(b.Items) != 1 {
		t.Fatal(b.Items)
	}
	it := b.Items[0]
	if it.ItemID == "" || it.Sequence == 0 || it.BindingID != "b1" || it.PersistedGeneration != 3 {
		t.Fatalf("%+v", it)
	}
	if s.Cursor.Sequences["b1|java1"] != it.Sequence || len(s.Pending.Items) != 1 {
		t.Fatalf("%+v", s)
	}
}

func TestSequenceIsMonotonicPerReplica(t *testing.T) {
	s := newState()
	var last uint64
	for i := 0; i < 3; i++ {
		var effs []reactor.Effect
		s, effs = Reduce(s, reactor.Observed{At: t0, Obs: obs(uint64(i))}, env(t0))
		it := sentBatch(t, effs).Items[len(sentBatch(t, effs).Items)-1]
		if it.Sequence <= last {
			t.Fatalf("sequence went backwards: %d after %d", it.Sequence, last)
		}
		last = it.Sequence
	}
	other := obs(1)
	other.ReplicaRef = "java2"
	s2, effs := Reduce(s, reactor.Observed{At: t0, Obs: other}, env(t0))
	if s2.Cursor.Sequences["b1|java2"] == 0 {
		t.Fatal("a second replica has its own counter")
	}
	_ = effs
}

func TestItemIsImmutableAcrossResends(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	first := sentBatch(t, effs).Items[0]
	// a reconnect re-sends the pending item unchanged
	s2, effs2 := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.SessionAccept{Limits: msg.Limits{ReportInterval: time.Minute}}}, env(t0.Add(time.Minute)))
	resent := sentBatch(t, effs2).Items[0]
	if resent.ItemID != first.ItemID || resent.Sequence != first.Sequence || resent.PersistedGeneration != first.PersistedGeneration {
		t.Fatalf("resend must be byte-identical:\n%+v\n%+v", first, resent)
	}
	if b := sentBatch(t, effs2); b.MessageID == sentBatch(t, effs).MessageID {
		t.Fatal("the batch message_id is fresh per send; only the item is stable")
	}
	_ = s2
}

func TestAckClosesItemsAndStoresMintedIDs(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	b := sentBatch(t, effs)
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{{
		InReplyToItemID: b.Items[0].ItemID, Sequence: b.Items[0].Sequence, ReplicaID: "rep-1", IncarnationID: "inc-1",
		Trust: msg.TrustTrusted, Disposition: msg.DispApplied}}}
	s2, effs2 := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.PendingCount() != 0 {
		t.Fatal("acked items must close")
	}
	if s2.ReplicaID("b1", "java1") != "rep-1" || s2.IncarnationID("b1", "java1", "abc123") != "inc-1" {
		t.Fatal("DP-minted ids must be stored")
	}
	rels := persisted(effs2)
	if len(rels) < 2 {
		t.Fatalf("registry and pending must be persisted: %v", rels)
	}
}

func TestRefusedAckClosesItemWithoutIDs(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	b := sentBatch(t, effs)
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{{
		InReplyToItemID: b.Items[0].ItemID, Trust: msg.TrustTrusted, Disposition: msg.DispRefused, RefusedReason: msg.ErrReplicaCapExceeded}}}
	s2, _ := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.PendingCount() != 0 {
		t.Fatal("a refused item is closed, not retried forever")
	}
	if s2.ReplicaID("b1", "java1") != "" {
		t.Fatal("a refusal mints no ids")
	}
}

func TestUntrustedAckIsRecordedNotRetried(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	b := sentBatch(t, effs)
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{{
		InReplyToItemID: b.Items[0].ItemID, Trust: msg.TrustUntrusted, Disposition: msg.DispApplied}}}
	s2, _ := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.PendingCount() != 0 {
		t.Fatal("the agent never retries to change a trust label")
	}
}

func TestAckMatchesByItemIDNotPosition(t *testing.T) {
	s := newState()
	// One env is reused across both observations, as a real process would
	// (one NewID generator per process): a fresh env(t0) per call would reset
	// the fake generator's counter and mint the same id-1ns twice, which
	// would collapse the ack-matching map below onto one key regardless of
	// whether matching is truly by item id. Sharing e is what lets this test
	// prove match-by-id rather than pass by accident.
	e := env(t0)
	s, _ = Reduce(s, reactor.Observed{At: t0, Obs: obs(1)}, e)
	second := obs(2)
	second.ReplicaRef = "java2"
	s, effs := Reduce(s, reactor.Observed{At: t0, Obs: second}, e)
	b := sentBatch(t, effs)
	if len(b.Items) != 2 {
		t.Fatalf("both pending items travel in one batch: %d", len(b.Items))
	}
	// answer them in reverse order
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{
		{InReplyToItemID: b.Items[1].ItemID, ReplicaID: "rep-2", Trust: msg.TrustTrusted, Disposition: msg.DispApplied},
		{InReplyToItemID: b.Items[0].ItemID, ReplicaID: "rep-1", Trust: msg.TrustTrusted, Disposition: msg.DispApplied},
	}}
	s2, _ := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.ReplicaID("b1", "java1") != "rep-1" || s2.ReplicaID("b1", "java2") != "rep-2" {
		t.Fatal("results must be matched by item id, never by position")
	}
}

func TestReportTimerFiresEvenWithNothingNew(t *testing.T) {
	s := newState()
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.SessionAccept{Limits: msg.Limits{ReportInterval: 30 * time.Second}}}, env(t0))
	_, effs := Reduce(s, reactor.TimerFired{At: t0.Add(30 * time.Second), Kind: reactor.ReportDue}, env(t0.Add(30*time.Second)))
	var armed bool
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReportDue {
			armed = true
		}
	}
	if !armed {
		t.Fatal("AG-D18: the cadence must re-arm even when nothing changed")
	}
}

func TestSessionAcceptArmsReportTimer(t *testing.T) {
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.SessionAccept{Limits: msg.Limits{ReportInterval: 45 * time.Second}}}, env(t0))
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReportDue && a.After == 45*time.Second {
			return
		}
	}
	t.Fatalf("report timer not armed: %+v", effs)
}

func TestCursorSurvivesRestartAndReseedsWhenLost(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	s := newState()
	s, effs := Reduce(s, reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			_ = st.Save(p.Rec)
		}
	}
	loaded, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Cursor.Sequences["b1|java1"] != s.Cursor.Sequences["b1|java1"] || loaded.PendingCount() != 1 {
		t.Fatalf("%+v", loaded)
	}
	// a lost data dir reseeds from DP time, so the new counter is above any old one
	fresh, _ := Load(store.MustOpen(t.TempDir()))
	f2, effs2 := Reduce(fresh, reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	seq := sentBatch(t, effs2).Items[0].Sequence
	if seq < uint64(t0.UnixNano()) {
		t.Fatalf("reseed must exceed any plausible previous counter: %d", seq)
	}
	_ = f2
}
