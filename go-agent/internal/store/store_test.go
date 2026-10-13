package store

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type rec struct {
	SchemaVersion int    `json:"schema_version"`
	Name          string `json:"name"`
}

func (r rec) Version() int { return r.SchemaVersion }

func TestWriteAtomicModeAndParentSync(t *testing.T) {
	dir := t.TempDir()
	var synced []string
	old := syncDir
	syncDir = func(d string) error { synced = append(synced, d); return nil }
	defer func() { syncDir = old }()

	p := filepath.Join(dir, "x.json")
	if err := WriteAtomic(p, []byte("hi"), 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	if len(synced) != 1 || synced[0] != dir {
		t.Fatalf("parent dir not fsynced: %v", synced)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("temp file left behind: %v", entries)
	}
}

func TestOpenLayout(t *testing.T) {
	root := filepath.Join(t.TempDir(), "data")
	s, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, d := range []string{"identity", "session", "apply", "reports"} {
		st, err := os.Stat(s.Path(d))
		if err != nil || !st.IsDir() || st.Mode().Perm() != 0o700 {
			t.Fatalf("%s: %v %v", d, err, st)
		}
	}
}

func TestJSONRoundTripAndVersion(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d"))
	if err := s.Save(JSON{RelPath: "session/r.json", Value: rec{SchemaVersion: 1, Name: "a"}}); err != nil {
		t.Fatal(err)
	}
	got, err := LoadJSON[rec](s, "session/r.json", 1)
	if err != nil || got.Name != "a" {
		t.Fatalf("%v %+v", err, got)
	}
	if _, err := LoadJSON[rec](s, "session/r.json", 2); !errors.Is(err, ErrSchemaVersion) {
		t.Fatalf("want ErrSchemaVersion, got %v", err)
	}
	if _, err := LoadJSON[rec](s, "session/missing.json", 1); !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestBlobAndDelete(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d"))
	if err := s.Save(Blob{RelPath: "apply/b/1/payload", Data: []byte("secret")}); err != nil {
		t.Fatal(err)
	}
	if !s.Exists("apply/b/1/payload") {
		t.Fatal("blob missing")
	}
	if err := s.Delete("apply/b/1/payload"); err != nil || s.Exists("apply/b/1/payload") {
		t.Fatalf("delete: %v", err)
	}
}
