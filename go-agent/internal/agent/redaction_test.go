//go:build integration

package agent

import (
	"bytes"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
)

// TestIntegrationRedactsSecretsFromLog is the redaction test the plan's
// success criteria owe ("No secret values in logs"). It drives a real
// preflight/apply/restart run (applyScenario, the same scenario
// TestIntegrationPreflightApplyRestart uses) whose apply payload embeds a
// secret and whose enrollment token is a distinctive value, captures
// everything the agent writes through its injected *slog.Logger during that
// run — at Debug level, so the per-event log line in loop.go is included —
// and asserts neither the secret nor the token ever reaches it.
//
// If this ever fails, the fix is a slog.LogValuer on whichever
// payload/token-bearing type is being logged with %v/%+v — never a weaker
// assertion.
func TestIntegrationRedactsSecretsFromLog(t *testing.T) {
	const secret = "super-secret-password"
	const enrollToken = "enroll-tok-redact-8f2c7a01" // distinctive: won't coincidentally appear in unrelated log output

	payload := "credentials:\n  password: " + secret + "\n"
	if !strings.Contains(payload, secret) {
		t.Fatalf("test bug: payload does not actually contain the secret: %q", payload)
	}

	// Inlined rather than startGateway(t, sc): that helper hardcodes
	// Tokens: []string{"tok"}, and this test needs a distinctive token to
	// make the "enrollment token not logged" assertion meaningful.
	gw, err := fakegw.Start(fakegw.Config{
		Listen:   "127.0.0.1:0",
		Tokens:   []string{enrollToken},
		Scenario: applyScenario(payload),
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(gw.Stop)

	var buf bytes.Buffer
	testLog := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))

	ad := newFakeAdapter(t)
	h := startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Token = enrollToken
		bc.Log = testLog
		bc.Adapter = ad
	})
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 3, 30*time.Second) {
		t.Fatalf("expected three acks (preflight/apply/restart), got %d", gw.Count("CommandAck"))
	}
	// Join the loop and its connection goroutines before reading buf.
	// slog's text handler serializes concurrent Handle calls against each
	// other internally, but bytes.Buffer is not safe to read from this
	// goroutine while another might still be writing to it — h.stop()
	// blocks until Run(ctx) has returned, which happens only after the
	// Shutdown effect batch has synchronously closed the connection and
	// joined its writer goroutine (see Loop.closeConn).
	h.stop()

	got := buf.String()
	if got == "" {
		t.Fatal("captured no log output; the test wiring is broken, not proving anything")
	}
	if strings.Contains(got, secret) {
		t.Fatalf("secret payload value leaked into the agent log:\n%s", got)
	}
	if strings.Contains(got, enrollToken) {
		t.Fatalf("enrollment token leaked into the agent log:\n%s", got)
	}
}
