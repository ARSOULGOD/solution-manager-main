package agent

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// A resume test drives the reducers directly: it writes a journal state to
// disk, reloads it, and asserts the AG-D4 table row.
func loadAfterCrash(t *testing.T, dir string) State {
	t.Helper()
	s, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	cs, err := commands.Load(s)
	if err != nil {
		t.Fatal(err)
	}
	return State{Commands: cs}
}

func TestResumeFromStagedCompletesTheWrite(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	hostDir := t.TempDir()
	target := filepath.Join(hostDir, "application.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	payload := []byte("new: 1\n")
	hash := configwriter.SHA256(payload)
	_ = s.Save(store.Blob{RelPath: commands.PayloadRel("b1", 2), Data: payload})
	_ = s.Save(store.JSON{RelPath: "apply/b1/2.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 2,
		IdempotencyKey: "k", CommandID: "c1", BindingID: "b1", Operation: msg.OpApply, TargetPath: target,
		ConfigHash: hash, Step: msg.StepStaged, StepDeadline: time.Now().Add(time.Minute)}})

	st := loadAfterCrash(t, dir)
	st.Authority = activeLease() // resume writes config only under an ACTIVE lease
	_, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	op, ok := has[runOpEffect](effs)
	if !ok || op.CommandID != "c1" {
		t.Fatalf("STAGED must re-enter the adapter: %+v", effs)
	}
	// the writer is idempotent: running it now lands the file exactly once
	res, err := configwriter.Write(target, payload, hash, filepath.Join(dir, "prev"), -1, -1, func(msg.ProgressStep) error { return nil })
	if err != nil || res.Hash != hash {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != string(payload) {
		t.Fatal("resume did not complete the write")
	}
}

func TestResumeFromPersistedDoesNotRewrite(t *testing.T) {
	hostDir := t.TempDir()
	target := filepath.Join(hostDir, "application.yml")
	payload := []byte("done\n")
	_ = os.WriteFile(target, payload, 0o600)
	before, _ := os.Stat(target)
	var steps []msg.ProgressStep
	_, err := configwriter.Write(target, payload, configwriter.SHA256(payload), filepath.Join(t.TempDir(), "prev"), -1, -1,
		func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	after, _ := os.Stat(target)
	if !before.ModTime().Equal(after.ModTime()) {
		t.Fatal("a PERSISTED target must be re-read, never rewritten")
	}
	if len(steps) != 1 || steps[0] != msg.StepPersisted {
		t.Fatalf("%v", steps)
	}
}

func TestResumeFromReceivedDiscardsThePartial(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	_ = s.Save(store.Blob{RelPath: commands.PayloadRel("b1", 4), Data: []byte("x")})
	_ = s.Save(store.JSON{RelPath: "apply/b1/4.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 4,
		CommandID: "c1", BindingID: "b1", Operation: msg.OpApply, Step: msg.StepReceived, ConfigHash: "h"}})
	st := loadAfterCrash(t, dir)
	next, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	var deleted []string
	for _, e := range effs {
		if d, ok := e.(deleteEffect); ok {
			deleted = append(deleted, d.Rel)
		}
	}
	if len(deleted) == 0 {
		t.Fatal("RECEIVED must be discarded; the DP redelivers")
	}
	// Both the payload/temp/previous directory AND the sibling journal
	// record file must go, or the record survives with ReceivedAck=true and
	// no payload behind it, permanently confusing offer()'s redelivery gate.
	wantDir := commands.DirRel("b1", 4)
	wantRecord := "apply/b1/4.json"
	if !slices.Contains(deleted, wantDir) {
		t.Fatalf("expected the partial directory %q deleted: %v", wantDir, deleted)
	}
	if !slices.Contains(deleted, wantRecord) {
		t.Fatalf("expected the orphaned journal record %q deleted too: %v", wantRecord, deleted)
	}
	// And the in-memory journal must drop it immediately, not only after the
	// next restart re-reads the (now absent) file from disk.
	if _, ok := next.Commands.Journal.ByCommand("c1"); ok {
		t.Fatal("the RECEIVED record must be removed from the in-memory journal immediately")
	}
}

func TestResumeResendsUnsentTerminalAck(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	_ = s.Save(store.JSON{RelPath: "apply/b1/6.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 6,
		CommandID: "c9", BindingID: "b1", Operation: msg.OpApply, Step: msg.StepReported, Outcome: msg.OutcomeApplied, AckSent: false}})
	st := loadAfterCrash(t, dir)
	_, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	ack := sentMsg[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeApplied || ack.CommandID != "c9" {
		t.Fatalf("%+v", ack)
	}
}

// activeLease is a lease that admits an apply on b1 at the test clock t0.
func activeLease() authority.State {
	g := msg.LeaseGrant{CnfThumbprint: "leaf", TargetID: "t", BindingIDs: []string{"b1"},
		IssuedAt: t0.Add(-10 * time.Minute), ExpiresAt: t0.Add(50 * time.Minute), Nonce: "n",
		Capabilities:    []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle},
		VerbCeilings:    map[msg.Operation]time.Duration{msg.OpApply: 2 * time.Minute},
		AdmissionMargin: 30 * time.Second}
	return authority.State{Phase: authority.PhaseActive, Grant: &g, LeafThumbprint: "leaf"}
}

// The composed reducers, as loaded from disk after a crash: with no lease (or
// an expired one) a STAGED step is held, not replayed — "LEASE_EXPIRED: no
// config write" — and not refused either, since the target may already have
// changed. Before the dispatch point existed, resume wrote regardless.
func TestResumeWithoutALeaseHoldsTheWrite(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	payload := []byte("new: 1\n")
	hash := configwriter.SHA256(payload)
	_ = s.Save(store.Blob{RelPath: commands.PayloadRel("b1", 2), Data: payload})
	_ = s.Save(store.JSON{RelPath: "apply/b1/2.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 2,
		IdempotencyKey: "k", CommandID: "c1", BindingID: "b1", Operation: msg.OpApply,
		ConfigHash: hash, Step: msg.StepStaged}})

	st := loadAfterCrash(t, dir) // no lease: the zero authority state is not ACTIVE
	next, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	if _, ok := has[runOpEffect](effs); ok {
		t.Fatal("resume must not write config without an ACTIVE lease")
	}
	if _, ok := sentMsgOK[msg.CommandAck](effs); ok {
		t.Fatal("a started step must be held, not refused")
	}
	if got := next.Commands.Queue; len(got) != 1 || got[0] != "c1" {
		t.Fatalf("the step should wait in the queue, got %v", got)
	}
}
