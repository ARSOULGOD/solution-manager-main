package configwriter

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestWriteHappyPathJournalsStagedThenPersisted(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "application.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	var steps []msg.ProgressStep
	res, err := Write(target, []byte("new"), SHA256([]byte("new")), -1, -1, func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	if res.Hash != SHA256([]byte("new")) {
		t.Fatal("hash")
	}
	if len(steps) != 2 || steps[0] != msg.StepStaged || steps[1] != msg.StepPersisted {
		t.Fatalf("steps %v", steps)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "new" {
		t.Fatal("content")
	}
	st, _ := os.Stat(target)
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("temp left behind: %v", entries)
	}
}

func TestWriteRefusesSymlinkEscape(t *testing.T) {
	dir := t.TempDir()
	outside := filepath.Join(t.TempDir(), "victim")
	_ = os.WriteFile(outside, []byte("x"), 0o644)
	link := filepath.Join(dir, "application.yml")
	if err := os.Symlink(outside, link); err != nil {
		t.Skip("symlinks unsupported")
	}
	_, err := Write(link, []byte("new"), SHA256([]byte("new")), -1, -1, func(msg.ProgressStep) error { return nil })
	if !errors.Is(err, ErrSymlinkEscape) {
		t.Fatalf("want ErrSymlinkEscape, got %v", err)
	}
	v, _ := os.ReadFile(outside)
	if string(v) != "x" {
		t.Fatal("victim was overwritten")
	}
}

func TestWriteHashMismatchFailsBeforeRename(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "a.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	_, err := Write(target, []byte("new"), "0000", -1, -1, func(msg.ProgressStep) error { return nil })
	var we *WriteError
	if !errors.As(err, &we) || we.Phase != PhaseBeforeRename {
		t.Fatalf("want before-rename failure, got %v", err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "old" {
		t.Fatal("target must be untouched")
	}
}

func TestWriteIsIdempotentWhenTargetAlreadyMatches(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "a.yml")
	_ = os.WriteFile(target, []byte("new"), 0o600)
	var steps []msg.ProgressStep
	_, err := Write(target, []byte("new"), SHA256([]byte("new")), -1, -1, func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	if len(steps) != 1 || steps[0] != msg.StepPersisted {
		t.Fatalf("a matching target is reported PERSISTED without rewriting: %v", steps)
	}
}

func TestWriteProgressErrorAbortsBeforeTouchingTarget(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "a.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	_, err := Write(target, []byte("new"), SHA256([]byte("new")), -1, -1, func(msg.ProgressStep) error { return errors.New("journal down") })
	var we *WriteError
	if !errors.As(err, &we) || we.Phase != PhaseBeforeRename {
		t.Fatalf("%v", err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "old" {
		t.Fatal("target must be untouched when the journal cannot record STAGED")
	}
}

func TestReadCheckedRefusesFifoAndSymlink(t *testing.T) {
	dir := t.TempDir()
	fifo := filepath.Join(dir, "fifo.yml")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := ReadChecked(fifo, 100); done <- err }()
	select {
	case err := <-done:
		if !errors.Is(err, ErrNotRegular) {
			t.Fatalf("fifo: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("ReadChecked hung on a FIFO")
	}
	target := filepath.Join(dir, "real.yml")
	if err := os.WriteFile(target, []byte("a: 1\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link.yml")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadChecked(link, 100); !errors.Is(err, ErrSymlinkEscape) {
		t.Fatalf("symlink: %v", err)
	}
	if b, err := ReadChecked(target, 100); err != nil || string(b) != "a: 1\n" {
		t.Fatalf("regular: %q %v", b, err)
	}
}
