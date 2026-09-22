package persist_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/persist"
)

func TestEnsureLayout(t *testing.T) {
	root := t.TempDir()
	
	err := persist.EnsureLayout(root)
	if err != nil {
		t.Fatal(err)
	}

	expectedDirs := []string{
		"identity",
		"session",
		filepath.Join("apply", "staging"),
		filepath.Join("telemetry", "buffer"),
	}

	for _, d := range expectedDirs {
		info, err := os.Stat(filepath.Join(root, d))
		if err != nil {
			t.Fatalf("expected dir %s to exist: %v", d, err)
		}
		if !info.IsDir() {
			t.Fatalf("expected %s to be a dir", d)
		}
		if info.Mode().Perm() != 0700 {
			t.Fatalf("expected %s to have 0700 perms, got %v", d, info.Mode().Perm())
		}
	}
}
