package commands

import (
	"os"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// Review finding 3: the crash window between Persist of an APPLIED generation M and the
// delete of an older generation N's partials. On restart N must NOT resume and write stale
// config over M.
func TestRestartAfterTheSupersededDeleteWasLostDropsTheOlderGeneration(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	putRecord(t, st, "b1", 5, msg.StepReported, "") // M: terminal APPLIED
	putRecord(t, st, "b1", 3, msg.StepStaged, secretPayload)
	putRecord(t, st, "b1", 4, msg.StepReceived, secretPayload)
	loaded, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	for _, gen := range []uint64{3, 4} {
		if st.Exists(PayloadRel("b1", gen)) || loaded.Spool[key("b1", gen)] {
			t.Errorf("generation %d is below the applied generation 5: its spool must be swept", gen)
		}
	}
	// and resume must not run it either: it is refused as STALE_GENERATION
	loaded.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 3, CommandID: "c-b1-3", Operation: msg.OpApply, Step: msg.StepStaged, ConfigHash: sha(secretPayload)})
	s, effs := Reduce(loaded, reactor.Resumed{At: t0}, env(t0), okAuth)
	if _, ran := has[reactor.RunOp](effs); ran {
		t.Fatal("a superseded generation must not write")
	}
	ack, ok := ackFor(effs, "c-b1-3")
	if !ok || ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrStaleGeneration) {
		t.Fatalf("%+v %v", ack, ok)
	}
	if r, _ := s.Journal.Get("b1", 3); !r.Terminal() {
		t.Fatal("the stale record is closed")
	}
}

// A generation ABOVE the applied one is unaffected.
func TestNewerThanAppliedGenerationSurvivesTheSweep(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	putRecord(t, st, "b1", 5, msg.StepReported, "")
	putRecord(t, st, "b1", 6, msg.StepStaged, secretPayload)
	loaded, _ := Load(st)
	if !loaded.Spool[key("b1", 6)] || !st.Exists(PayloadRel("b1", 6)) {
		t.Fatal("the newer generation is live")
	}
}

// Review finding 13: ownership is injectable; a foreign-owned live spool directory is not trusted.
func TestStartupSweepRemovesAForeignOwnedSpoolDirectory(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	putRecord(t, st, "b1", 1, msg.StepStaged, secretPayload)
	old := currentUID
	currentUID = func() int { return os.Getuid() + 1 }
	defer func() { currentUID = old }()
	loaded, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	if st.Exists(DirRel("b1", 1)) || loaded.Spool[key("b1", 1)] {
		t.Fatal("a spool not owned by the agent's user must be removed, not trusted")
	}
}

// Review finding 14: the host StartedAt rides only on an APPLIED ack.
func TestStartedAtOnlyOnAnAppliedAck(t *testing.T) {
	for _, outcome := range []msg.Outcome{msg.OutcomeFailedNoEffect, msg.OutcomeFailedRolledBack, msg.OutcomeApplyIndeterminate, msg.OutcomeRefused} {
		s := newState()
		s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepPersisted})
		s.InFlight = "c1"
		res := runtimeResult(outcome, "")
		res.Facts.StartedAt = t0
		_, effs := Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: res}, env(t0), okAuth)
		if ack := sent[msg.CommandAck](t, effs); ack.ContainerStartedAt != nil {
			t.Errorf("%s: a failed attempt carries no StartedAt baseline", outcome)
		}
	}
}
