package telemetry_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/telemetry"
)

func testHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{Timeout: timeout}
}

func TestScrape_HTTP200_ReturnsValidSample(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("hello"))
	}))
	defer srv.Close()

	target := telemetry.Target{ID: "svc1", URL: srv.URL}
	sample := telemetry.Scrape(context.Background(), testHTTPClient(5*time.Second), target)

	if sample.ServiceID != "svc1" {
		t.Fatalf("service_id = %q, want svc1", sample.ServiceID)
	}
	if sample.URL != srv.URL {
		t.Fatalf("url = %q, want %q", sample.URL, srv.URL)
	}
	if sample.ScrapedAt == "" {
		t.Fatal("expected scraped_at to be set")
	}
	if _, err := time.Parse(time.RFC3339, sample.ScrapedAt); err != nil {
		t.Fatalf("scraped_at not RFC3339: %v", err)
	}
	if sample.HTTPStatus != http.StatusOK {
		t.Fatalf("http_status = %d, want 200", sample.HTTPStatus)
	}
	if sample.Body != "hello" {
		t.Fatalf("body = %q, want hello", sample.Body)
	}
	if sample.Error != "" {
		t.Fatalf("expected empty error before validate, got %q", sample.Error)
	}

	if err := telemetry.Validate(&sample, maxBody); err != nil {
		t.Fatalf("expected valid sample, got %v", err)
	}
	if !sample.OK {
		t.Fatal("expected OK=true after validate")
	}
}

func TestScrape_HTTP503_NotOKAfterValidate(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte("unavailable"))
	}))
	defer srv.Close()

	target := telemetry.Target{ID: "svc1", URL: srv.URL}
	sample := telemetry.Scrape(context.Background(), testHTTPClient(5*time.Second), target)

	if sample.HTTPStatus != http.StatusServiceUnavailable {
		t.Fatalf("http_status = %d, want 503", sample.HTTPStatus)
	}
	if sample.Error != "" {
		t.Fatalf("expected empty transport error, got %q", sample.Error)
	}

	if err := telemetry.Validate(&sample, maxBody); err == nil {
		t.Fatal("expected validation error for 503")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
}

func TestScrape_ConnectionRefused_NotOKAfterValidate(t *testing.T) {
	target := telemetry.Target{ID: "svc1", URL: "http://127.0.0.1:1/refused"}
	sample := telemetry.Scrape(context.Background(), testHTTPClient(2*time.Second), target)

	if sample.Error == "" {
		t.Fatal("expected transport error")
	}
	if sample.HTTPStatus != 0 {
		t.Fatalf("http_status = %d, want 0 on transport failure", sample.HTTPStatus)
	}

	if err := telemetry.Validate(&sample, maxBody); err == nil {
		t.Fatal("expected validation error for transport failure")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
}

func TestScrape_Timeout_NotOKAfterValidate(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("slow"))
	}))
	defer srv.Close()

	target := telemetry.Target{ID: "svc1", URL: srv.URL}
	sample := telemetry.Scrape(context.Background(), testHTTPClient(50*time.Millisecond), target)

	if sample.Error == "" {
		t.Fatal("expected timeout error")
	}

	if err := telemetry.Validate(&sample, maxBody); err == nil {
		t.Fatal("expected validation error for timeout")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
}
