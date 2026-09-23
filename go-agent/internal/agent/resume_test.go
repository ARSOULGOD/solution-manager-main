package agent

import (
	"os"
	"path/filepath"
	"testing"
	"time"

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
	_, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	var deleted []string
	for _, e := range effs {
		if d, ok := e.(deleteEffect); ok {
			deleted = append(deleted, d.Rel)
		}
	}
	if len(deleted) == 0 {
		t.Fatal("RECEIVED must be discarded; the DP redelivers")
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
