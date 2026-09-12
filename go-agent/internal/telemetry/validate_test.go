package telemetry_test

import (
	"strings"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/telemetry"
)

const maxBody = 1024 * 1024

func TestValidate_Success(t *testing.T) {
	sample := &telemetry.Sample{
		HTTPStatus: 200,
		Body:       "hello",
	}
	if err := telemetry.Validate(sample, maxBody); err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if !sample.OK {
		t.Fatal("expected OK=true")
	}
	if sample.Error != "" {
		t.Fatalf("expected empty error, got %q", sample.Error)
	}
}

func TestValidate_HTTP500(t *testing.T) {
	sample := &telemetry.Sample{
		HTTPStatus: 500,
		Body:       "error",
	}
	if err := telemetry.Validate(sample, maxBody); err == nil {
		t.Fatal("expected validation error")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
	if sample.Error == "" {
		t.Fatal("expected error message")
	}
}

func TestValidate_EmptyBody(t *testing.T) {
	sample := &telemetry.Sample{
		HTTPStatus: 200,
		Body:       "",
	}
	if err := telemetry.Validate(sample, maxBody); err == nil {
		t.Fatal("expected validation error")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
	if sample.Error == "" {
		t.Fatal("expected error message")
	}
}

func TestValidate_OversizeBody(t *testing.T) {
	sample := &telemetry.Sample{
		HTTPStatus: 200,
		Body:       strings.Repeat("x", 11),
	}
	if err := telemetry.Validate(sample, 10); err == nil {
		t.Fatal("expected validation error")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
	if sample.Error == "" {
		t.Fatal("expected error message")
	}
}

func TestValidate_TransportError(t *testing.T) {
	sample := &telemetry.Sample{
		Error: "connection refused",
	}
	if err := telemetry.Validate(sample, maxBody); err == nil {
		t.Fatal("expected validation error")
	}
	if sample.OK {
		t.Fatal("expected OK=false")
	}
	if sample.Error != "connection refused" {
		t.Fatalf("expected preserved error, got %q", sample.Error)
	}
}
