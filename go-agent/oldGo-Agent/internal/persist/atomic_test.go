package persist_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/persist"
)

func TestWriteFileAtomic_RoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "test.txt")
	data := []byte("hello world")

	err := persist.WriteFileAtomic(path, data, 0)
	if err != nil {
		t.Fatal(err)
	}

	readData, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	if string(readData) != string(data) {
		t.Fatalf("expected %q, got %q", string(data), string(readData))
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}

	if info.Mode().Perm() != 0600 {
		t.Fatalf("expected mode 0600, got %v", info.Mode().Perm())
	}
}

func TestWriteFileAtomic_MissingDir(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "missingdir", "test.txt")
	
	err := persist.WriteFileAtomic(path, []byte("data"), 0600)
	if err == nil {
		t.Fatal("expected error when writing to missing directory")
	}
}
