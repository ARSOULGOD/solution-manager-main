package telemetry_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/telemetry"
)

func TestLoadTargets_ValidYAML(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "targets.yaml")
	content := `targets:
  - id: java1
    url: http://127.0.0.1:8081/hello
  - id: java2
    url: http://127.0.0.1:8082/hello
`
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}

	targets, err := telemetry.LoadTargets(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(targets) != 2 {
		t.Fatalf("expected 2 targets, got %d", len(targets))
	}
	if targets[0].ID != "java1" || targets[0].URL != "http://127.0.0.1:8081/hello" {
		t.Fatalf("unexpected first target: %+v", targets[0])
	}
	if targets[1].ID != "java2" || targets[1].URL != "http://127.0.0.1:8082/hello" {
		t.Fatalf("unexpected second target: %+v", targets[1])
	}
}

func TestLoadTargets_MissingFile(t *testing.T) {
	_, err := telemetry.LoadTargets(filepath.Join(t.TempDir(), "missing.yaml"))
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}

func TestLoadTargets_EmptyID(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "targets.yaml")
	content := `targets:
  - id: ""
    url: http://127.0.0.1:8081/hello
`
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}

	_, err := telemetry.LoadTargets(path)
	if err == nil {
		t.Fatal("expected error for empty id")
	}
}

func TestLoadTargets_EmptyURL(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "targets.yaml")
	content := `targets:
  - id: java1
    url: ""
`
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}

	_, err := telemetry.LoadTargets(path)
	if err == nil {
		t.Fatal("expected error for empty url")
	}
}
