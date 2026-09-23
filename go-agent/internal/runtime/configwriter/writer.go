// Package configwriter writes a rendered configuration file the way baseline
// go-agent design §5.1 requires: temp in the same directory → fsync → chmod
// 0600 → rename → fsync parent, refusing symlink escapes, keeping the
// previous content for rollback, and verifying by read-back.
package configwriter

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

var ErrSymlinkEscape = errors.New("configwriter: target escapes its declared directory via symlink")

const (
	PhaseBeforeRename          = "before-rename"
	PhaseAfterRenameRolledBack = "after-rename-rolled-back"
	PhaseAfterRenameUnknown    = "after-rename-unknown"
)

type WriteError struct {
	Phase string
	Err   error
}

func (e *WriteError) Error() string { return fmt.Sprintf("configwriter: %s: %v", e.Phase, e.Err) }
func (e *WriteError) Unwrap() error { return e.Err }

func SHA256(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func before(err error) error { return &WriteError{Phase: PhaseBeforeRename, Err: err} }

// checkPath canonicalises the declared directory and refuses a target whose
// resolved location is outside it.
func checkPath(hostPath string) (dir, base string, err error) {
	if !filepath.IsAbs(hostPath) {
		return "", "", fmt.Errorf("configwriter: host path must be absolute: %q", hostPath)
	}
	hostPath = filepath.Clean(hostPath)
	dir, base = filepath.Dir(hostPath), filepath.Base(hostPath)
	realDir, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return "", "", err
	}
	if fi, err := os.Lstat(hostPath); err == nil && fi.Mode()&os.ModeSymlink != 0 {
		return "", "", ErrSymlinkEscape
	}
	if resolved, err := filepath.EvalSymlinks(hostPath); err == nil {
		if filepath.Dir(resolved) != realDir {
			return "", "", ErrSymlinkEscape
		}
	}
	return realDir, base, nil
}

func Write(hostPath string, payload []byte, wantSHA, previousPath string, uid, gid int, progress runtime.Progress) (runtime.WriteResult, error) {
	if SHA256(payload) != wantSHA {
		return runtime.WriteResult{}, before(fmt.Errorf("payload hash %s != declared %s", SHA256(payload), wantSHA))
	}
	dir, base, err := checkPath(hostPath)
	if err != nil {
		return runtime.WriteResult{}, before(err)
	}
	target := filepath.Join(dir, base)

	// Idempotent: a target that already holds the bytes is PERSISTED, never rewritten.
	if cur, err := os.ReadFile(target); err == nil && SHA256(cur) == wantSHA {
		if err := progress(msg.StepPersisted); err != nil {
			return runtime.WriteResult{}, before(err)
		}
		return runtime.WriteResult{Hash: wantSHA}, nil
	}

	// Retain the previous content for FAILED_ROLLED_BACK.
	prev, prevErr := os.ReadFile(target)
	hadPrev := prevErr == nil
	if hadPrev {
		if err := os.MkdirAll(filepath.Dir(previousPath), 0o700); err != nil {
			return runtime.WriteResult{}, before(err)
		}
		if err := os.WriteFile(previousPath, prev, 0o600); err != nil {
			return runtime.WriteResult{}, before(err)
		}
	}

	// STAGED is journaled before the temp file exists (write-before-act).
	if err := progress(msg.StepStaged); err != nil {
		return runtime.WriteResult{}, before(err)
	}
	tmp, err := os.CreateTemp(dir, "."+base+".*.sm-tmp")
	if err != nil {
		return runtime.WriteResult{}, before(err)
	}
	tmpPath := tmp.Name()
	fail := func(err error) (runtime.WriteResult, error) {
		_ = tmp.Close()
		_ = os.Remove(tmpPath)
		return runtime.WriteResult{}, before(err)
	}
	if _, err := tmp.Write(payload); err != nil {
		return fail(err)
	}
	if err := tmp.Sync(); err != nil {
		return fail(err)
	}
	if err := tmp.Chmod(0o600); err != nil {
		return fail(err)
	}
	if uid >= 0 && gid >= 0 {
		if err := tmp.Chown(uid, gid); err != nil {
			return fail(err)
		}
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return runtime.WriteResult{}, before(err)
	}
	if err := os.Rename(tmpPath, target); err != nil {
		_ = os.Remove(tmpPath)
		return runtime.WriteResult{}, before(err)
	}

	// After the rename the new content is visible; any failure from here is
	// rolled back from the retained copy.
	rollback := func(cause error) (runtime.WriteResult, error) {
		if !hadPrev {
			if rmErr := os.Remove(target); rmErr != nil {
				return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameUnknown, Err: errors.Join(cause, rmErr)}
			}
			return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameRolledBack, Err: cause}
		}
		if wErr := os.WriteFile(target, prev, 0o600); wErr != nil {
			return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameUnknown, Err: errors.Join(cause, wErr)}
		}
		return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameRolledBack, Err: cause}
	}
	if err := syncDir(dir); err != nil {
		return rollback(err)
	}
	back, err := os.ReadFile(target)
	if err != nil {
		return rollback(err)
	}
	if !bytes.Equal(back, payload) {
		return rollback(errors.New("read-back differs from payload"))
	}
	if err := progress(msg.StepPersisted); err != nil {
		// The file is durably in place; a journal failure here is reported, not rolled back.
		return runtime.WriteResult{Hash: wantSHA}, &WriteError{Phase: PhaseAfterRenameUnknown, Err: err}
	}
	return runtime.WriteResult{Hash: wantSHA}, nil
}

func syncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
