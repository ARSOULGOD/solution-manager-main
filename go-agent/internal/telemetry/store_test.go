package telemetry_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/telemetry"
)

func sampleOK() telemetry.Sample {
	return telemetry.Sample{
		ServiceID:   "java1",
		URL:         "http://127.0.0.1:8081/hello",
		ScrapedAt:   "2026-09-12T10:00:00Z",
		HTTPStatus:  200,
		ContentType: "text/plain",
		Body:        "Hello from java1",
		OK:          true,
	}
}

func sampleFail() telemetry.Sample {
	return telemetry.Sample{
		ServiceID:  "java1",
		URL:        "http://127.0.0.1:8081/hello",
		ScrapedAt:  "2026-09-12T10:01:00Z",
		HTTPStatus: 503,
		Body:       "",
		OK:         false,
		Error:      "HTTP status 503",
	}
}

func TestStore_SaveSuccess_CreatesLatestAndHistory(t *testing.T) {
	dataDir := t.TempDir()
	store := telemetry.Store{}
	s := sampleOK()

	if err := store.SaveSuccess(dataDir, s); err != nil {
		t.Fatal(err)
	}

	latestPath := filepath.Join(dataDir, "telemetry", "java1", "latest.json")
	historyPath := filepath.Join(dataDir, "telemetry", "java1", "history", "20260912T100000Z.json")

	if _, err := os.Stat(latestPath); err != nil {
		t.Fatalf("latest.json missing: %v", err)
	}
	if _, err := os.Stat(historyPath); err != nil {
		t.Fatalf("history file missing: %v", err)
	}

	var latest telemetry.Sample
	if err := readJSON(latestPath, &latest); err != nil {
		t.Fatal(err)
	}
	if latest.ServiceID != s.ServiceID || latest.Body != s.Body || !latest.OK {
		t.Fatalf("unexpected latest content: %+v", latest)
	}
}

func TestStore_SaveFailure_WritesUnderFailures(t *testing.T) {
	dataDir := t.TempDir()
	store := telemetry.Store{}
	s := sampleFail()

	if err := store.SaveFailure(dataDir, s); err != nil {
		t.Fatal(err)
	}

	failPath := filepath.Join(dataDir, "telemetry", "_failures", "java1-20260912T100100Z.json")
	if _, err := os.Stat(failPath); err != nil {
		t.Fatalf("failure file missing: %v", err)
	}

	latestPath := filepath.Join(dataDir, "telemetry", "java1", "latest.json")
	if _, err := os.Stat(latestPath); !os.IsNotExist(err) {
		t.Fatal("expected latest.json to be absent after failure-only save")
	}
}

func TestStore_SuccessAfterFailure_DoesNotLeaveStaleFailureAsLatest(t *testing.T) {
	dataDir := t.TempDir()
	store := telemetry.Store{}

	if err := store.SaveFailure(dataDir, sampleFail()); err != nil {
		t.Fatal(err)
	}

	ok := sampleOK()
	if err := store.SaveSuccess(dataDir, ok); err != nil {
		t.Fatal(err)
	}

	latestPath := filepath.Join(dataDir, "telemetry", "java1", "latest.json")
	var latest telemetry.Sample
	if err := readJSON(latestPath, &latest); err != nil {
		t.Fatal(err)
	}
	if !latest.OK {
		t.Fatalf("expected successful latest, got %+v", latest)
	}
	if latest.HTTPStatus != 200 {
		t.Fatalf("expected HTTP 200 in latest, got %d", latest.HTTPStatus)
	}
}

func readJSON(path string, v any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}
