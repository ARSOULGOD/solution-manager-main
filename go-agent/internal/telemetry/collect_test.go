package telemetry_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/telemetry"
)

func TestRun_TwoTargets_WritesSegregatedLatest(t *testing.T) {
	srv1 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte("one"))
	}))
	defer srv1.Close()

	srv2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte("two"))
	}))
	defer srv2.Close()

	dataDir := t.TempDir()
	targets := []telemetry.Target{
		{ID: "alpha", URL: srv1.URL},
		{ID: "beta", URL: srv2.URL},
	}
	client := testHTTPClient(5 * time.Second)

	if err := telemetry.Run(context.Background(), dataDir, targets, client, 0); err != nil {
		t.Fatalf("Run: %v", err)
	}

	latestAlpha := filepath.Join(dataDir, "telemetry", "alpha", "latest.json")
	latestBeta := filepath.Join(dataDir, "telemetry", "beta", "latest.json")

	if _, err := os.Stat(latestAlpha); err != nil {
		t.Fatalf("alpha latest.json missing: %v", err)
	}
	if _, err := os.Stat(latestBeta); err != nil {
		t.Fatalf("beta latest.json missing: %v", err)
	}

	var alpha, beta telemetry.Sample
	if err := readJSON(latestAlpha, &alpha); err != nil {
		t.Fatal(err)
	}
	if err := readJSON(latestBeta, &beta); err != nil {
		t.Fatal(err)
	}

	if alpha.ServiceID != "alpha" || alpha.Body != "one" || !alpha.OK {
		t.Fatalf("unexpected alpha sample: %+v", alpha)
	}
	if beta.ServiceID != "beta" || beta.Body != "two" || !beta.OK {
		t.Fatalf("unexpected beta sample: %+v", beta)
	}
}

func TestRun_OneTargetFails_WritesFailureAndReturnsError(t *testing.T) {
	okSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok"))
	}))
	defer okSrv.Close()

	failSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("fail"))
	}))
	defer failSrv.Close()

	dataDir := t.TempDir()
	targets := []telemetry.Target{
		{ID: "good", URL: okSrv.URL},
		{ID: "bad", URL: failSrv.URL},
	}
	client := testHTTPClient(5 * time.Second)

	err := telemetry.Run(context.Background(), dataDir, targets, client, maxBody)
	if err == nil {
		t.Fatal("expected error when any target fails")
	}

	goodLatest := filepath.Join(dataDir, "telemetry", "good", "latest.json")
	if _, statErr := os.Stat(goodLatest); statErr != nil {
		t.Fatalf("good latest.json missing: %v", statErr)
	}

	failDir := filepath.Join(dataDir, "telemetry", "_failures")
	entries, readErr := os.ReadDir(failDir)
	if readErr != nil {
		t.Fatalf("failures dir missing: %v", readErr)
	}
	if len(entries) == 0 {
		t.Fatal("expected at least one failure file")
	}

	foundBad := false
	for _, e := range entries {
		if len(e.Name()) >= 4 && e.Name()[:3] == "bad" {
			foundBad = true
			break
		}
	}
	if !foundBad {
		t.Fatalf("expected failure file for bad target, got %v", entries)
	}

	badLatest := filepath.Join(dataDir, "telemetry", "bad", "latest.json")
	if _, statErr := os.Stat(badLatest); !os.IsNotExist(statErr) {
		t.Fatal("expected bad target to not write latest.json")
	}
}
