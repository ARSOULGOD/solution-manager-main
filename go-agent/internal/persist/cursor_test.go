package persist_test

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/persist"
)

func TestCursor_RoundTrip(t *testing.T) {
	root := t.TempDir()
	persist.EnsureLayout(root)

	c := persist.Cursor{
		LastAppliedGeneration:     5,
		LastAppliedIdempotencyKey: "ik1",
		LastAppliedConfigSHA256:   "sha1",
		SupersededCleaned:         []uint64{3, 4},
		InFlight: &persist.InFlight{
			Generation:       6,
			IdempotencyKey:   "ik2",
			BindingID:        "b1",
			Phase:            "STAGING",
			StepDeadline:     time.Now().UTC().Truncate(time.Second),
			StagingPath:      "apply/staging/f1",
			TargetConfigPath: "/etc/config.yml",
		},
	}

	err := persist.SaveCursor(root, c)
	if err != nil {
		t.Fatal(err)
	}

	loaded, err := persist.LoadCursor(root)
	if err != nil {
		t.Fatal(err)
	}

	if loaded.LastAppliedGeneration != c.LastAppliedGeneration {
		t.Fatalf("expected %d, got %d", c.LastAppliedGeneration, loaded.LastAppliedGeneration)
	}
	if loaded.InFlight == nil || loaded.InFlight.Phase != c.InFlight.Phase {
		t.Fatalf("expected in flight phase %q", c.InFlight.Phase)
	}
}

func TestCursor_NotFound(t *testing.T) {
	root := t.TempDir()
	_, err := persist.LoadCursor(root)
	if err != persist.ErrNotFound {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func TestCursor_CorruptJSON(t *testing.T) {
	root := t.TempDir()
	persist.EnsureLayout(root)
	path := filepath.Join(root, "apply", "cursor.json")
	persist.WriteFileAtomic(path, []byte("{corrupt"), 0600)
	
	_, err := persist.LoadCursor(root)
	if err == nil || err == persist.ErrNotFound {
		t.Fatalf("expected JSON error, got %v", err)
	}
}
