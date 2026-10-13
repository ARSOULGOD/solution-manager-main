package configwriter

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func noProgress(msg.ProgressStep) error { return nil }

// Review finding 7: the precondition is evaluated on the read the write is based on.
func TestWriteIfEvaluatesThePreconditionOnItsOwnRead(t *testing.T) {
	host := filepath.Join(t.TempDir(), "application.yml")
	_ = os.WriteFile(host, []byte("cur\n"), 0o600)
	payload := []byte("new\n")
	wrong := SHA256([]byte("other"))
	steps := 0
	_, err := WriteIf(host, payload, SHA256(payload), &wrong, -1, -1, func(msg.ProgressStep) error { steps++; return nil })
	if !errors.Is(err, ErrTargetMoved) || steps != 0 {
		t.Fatalf("a mismatch writes nothing and journals no step: %v steps=%d", err, steps)
	}
	if b, _ := os.ReadFile(host); string(b) != "cur\n" {
		t.Fatalf("%q", b)
	}
	right := SHA256([]byte("cur\n"))
	if _, err := WriteIf(host, payload, SHA256(payload), &right, -1, -1, noProgress); err != nil {
		t.Fatal(err)
	}
	// absent file + empty expectation; present file + empty expectation is TARGET_MOVED
	empty := ""
	fresh := filepath.Join(t.TempDir(), "application.yml")
	if _, err := WriteIf(fresh, payload, SHA256(payload), &empty, -1, -1, noProgress); err != nil {
		t.Fatal(err)
	}
	if _, err := WriteIf(host, []byte("again\n"), SHA256([]byte("again\n")), &empty, -1, -1, noProgress); !errors.Is(err, ErrTargetMoved) {
		t.Fatal(err)
	}
	// replay: the file already holds the payload -> idempotent PERSISTED, whatever the expectation
	if _, err := WriteIf(host, payload, SHA256(payload), &wrong, -1, -1, noProgress); err != nil {
		t.Fatalf("a crashed REMATERIALIZE replays to completion: %v", err)
	}
	if _, err := Write(host, payload, SHA256(payload), -1, -1, noProgress); err != nil {
		t.Fatal(err)
	}
}
