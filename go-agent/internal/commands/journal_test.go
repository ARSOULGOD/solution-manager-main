package commands

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func rec(binding string, gen uint64, step msg.ProgressStep) Record {
	return Record{SchemaVersion: 1, RolloutGeneration: gen, IdempotencyKey: "k", CommandID: "c", BindingID: binding,
		Operation: msg.OpApply, TargetPath: "/etc/app/application.yml", ConfigHash: "h", Step: step, StepDeadline: time.Now().Add(time.Minute)}
}

func TestRecordPaths(t *testing.T) {
	r := rec("b1", 7, msg.StepReceived)
	if r.Rel() != "apply/b1/7.json" || PayloadRel("b1", 7) != "apply/b1/7/payload" || PreviousRel("b1", 7) != "apply/b1/7/previous" {
		t.Fatalf("%s %s %s", r.Rel(), PayloadRel("b1", 7), PreviousRel("b1", 7))
	}
}

func TestLoadJournalWatermarkAndLookup(t *testing.T) {
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	for _, r := range []Record{rec("b1", 1, msg.StepReported), rec("b1", 3, msg.StepStaged), rec("b2", 9, msg.StepReported)} {
		r2 := r
		r2.CommandID = "cmd-" + r.BindingID + "-" + time.Duration(r.RolloutGeneration).String()
		if err := s.Save(store.JSON{RelPath: r2.Rel(), Value: r2}); err != nil {
			t.Fatal(err)
		}
	}
	j, err := LoadJournal(s)
	if err != nil {
		t.Fatal(err)
	}
	if j.Watermark["b1"] != 3 || j.Watermark["b2"] != 9 {
		t.Fatalf("watermarks %v", j.Watermark)
	}
	if _, ok := j.Get("b1", 3); !ok {
		t.Fatal("lookup by binding/gen")
	}
	if r, ok := j.ByCommand("cmd-b1-3ns"); !ok || r.RolloutGeneration != 3 {
		t.Fatalf("lookup by command id: %v %+v", ok, r)
	}
	if j.OpenCount() != 1 {
		t.Fatalf("open %d", j.OpenCount())
	}
}

func TestSupersededPartials(t *testing.T) {
	j := Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}
	j.Put(rec("b1", 1, msg.StepReported))
	j.Put(rec("b1", 2, msg.StepStaged))
	j.Put(rec("b1", 5, msg.StepPersisted))
	got := j.SupersededPartials("b1", 5)
	if len(got) != 2 {
		t.Fatalf("%v", got)
	}
	for _, g := range got {
		if g == DirRel("b1", 5) {
			t.Fatal("the current generation must never be swept")
		}
	}
}

func TestSchemaVersionMismatchIsFatal(t *testing.T) {
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	bad := rec("b1", 1, msg.StepReported)
	bad.SchemaVersion = 99
	_ = s.Save(store.JSON{RelPath: bad.Rel(), Value: bad})
	if _, err := LoadJournal(s); err == nil {
		t.Fatal("an unknown record version must refuse to load, not be migrated")
	}
}
