// Package store is the agent's durable state under $SM_AGENT_DATA_DIR.
// Every write goes through WriteAtomic; every record carries a schema
// version and is refused, not migrated, when it does not match.
package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

var (
	ErrNotFound      = errors.New("store: not found")
	ErrSchemaVersion = errors.New("store: schema version mismatch")
)

// syncDir is a variable so tests can observe the parent-directory fsync.
var syncDir = func(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// WriteAtomic writes data to path via a temp file in the same directory:
// write → fsync → chmod → rename → fsync parent. Partial writes are never
// visible and the rename is durable after return.
func WriteAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := func() { _ = tmp.Close(); _ = os.Remove(tmpPath) }
	if _, err := tmp.Write(data); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Sync(); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return syncDir(dir)
}

type Store struct{ Root string }

var layout = []string{"identity", "session", "apply", "reports"}

func Open(root string) (*Store, error) {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, err
	}
	if err := os.Chmod(root, 0o700); err != nil {
		return nil, err
	}
	for _, d := range layout {
		if err := os.MkdirAll(filepath.Join(root, d), 0o700); err != nil {
			return nil, err
		}
	}
	return &Store{Root: root}, nil
}

// MustOpen is for tests.
func MustOpen(root string) *Store {
	s, err := Open(root)
	if err != nil {
		panic(err)
	}
	return s
}

func (s *Store) Path(rel string) string { return filepath.Join(s.Root, filepath.FromSlash(rel)) }

func (s *Store) Exists(rel string) bool {
	_, err := os.Stat(s.Path(rel))
	return err == nil
}

type Record interface {
	Rel() string
	Marshal() ([]byte, error)
}

// JSON persists Value as indented JSON at RelPath.
type JSON struct {
	RelPath string
	Value   any
}

func (j JSON) Rel() string              { return j.RelPath }
func (j JSON) Marshal() ([]byte, error) { return json.MarshalIndent(j.Value, "", "  ") }

// Blob persists raw bytes (payloads, PEM) at RelPath.
type Blob struct {
	RelPath string
	Data    []byte
}

func (b Blob) Rel() string              { return b.RelPath }
func (b Blob) Marshal() ([]byte, error) { return b.Data, nil }

func (s *Store) Save(r Record) error {
	data, err := r.Marshal()
	if err != nil {
		return fmt.Errorf("store: marshal %s: %w", r.Rel(), err)
	}
	return WriteAtomic(s.Path(r.Rel()), data, 0o600)
}

func (s *Store) Delete(rel string) error {
	err := os.Remove(s.Path(rel))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return syncDir(filepath.Dir(s.Path(rel)))
}

// DeleteAll removes a directory and its contents, then fsyncs the parent.
func (s *Store) DeleteAll(rel string) error {
	if err := os.RemoveAll(s.Path(rel)); err != nil {
		return err
	}
	return syncDir(filepath.Dir(s.Path(rel)))
}

type Versioned interface{ Version() int }

func LoadJSON[T Versioned](s *Store, rel string, wantVersion int) (T, error) {
	var v T
	data, err := os.ReadFile(s.Path(rel))
	if errors.Is(err, os.ErrNotExist) {
		return v, ErrNotFound
	}
	if err != nil {
		return v, err
	}
	if err := json.Unmarshal(data, &v); err != nil {
		return v, fmt.Errorf("store: %s: %w", rel, err)
	}
	if v.Version() != wantVersion {
		return v, fmt.Errorf("%w: %s has %d, want %d", ErrSchemaVersion, rel, v.Version(), wantVersion)
	}
	return v, nil
}
