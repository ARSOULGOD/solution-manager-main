//go:build integration

package agent

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
)

// syncBuffer is the console's output: acks print from their own goroutines.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func waitOutput(t *testing.T, out *syncBuffer, want string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if strings.Contains(out.String(), want) {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("console never printed %q; got:\n%s", want, out.String())
}

// Four commands typed back to back, without waiting for each ack: the
// gateway numbers them 1–4 for binding-1, and the agent runs all four.
func TestIntegrationConsoleCommandsBackToBack(t *testing.T) {
	gw := startGateway(t, nil)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	file := filepath.Join(t.TempDir(), "new.yml")
	if err := os.WriteFile(file, []byte("server.port: 8082\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	out := &syncBuffer{}
	go gw.Console(strings.NewReader("preflight\napply "+file+"\nrestart\nstop\n"), out)
	for _, want := range []string{
		"preflight-binding-1-1 gen=1 -> PREFLIGHT_OK",
		"apply-binding-1-2 gen=2 -> APPLIED",
		"restart-binding-1-3 gen=3 -> APPLIED",
		"stop-binding-1-4 gen=4 -> APPLIED",
	} {
		waitOutput(t, out, want, 20*time.Second)
	}
	got, err := os.ReadFile(ad.target())
	if err != nil || string(got) != "server.port: 8082\n" {
		t.Fatalf("apply wrote %q, %v", got, err)
	}
}

// A typed command must not reuse a generation a scripted offer already used:
// the agent would answer it from its journal, or refuse it as stale.
func TestIntegrationConsoleGenerationFollowsScriptedOffers(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-s", BindingID: "binding-1", Generation: 5, Operation: "preflight"}},
	}
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", newFakeAdapter(t))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("scripted offer never acked")
	}

	out := &syncBuffer{}
	go gw.Console(strings.NewReader("restart\n"), out)
	waitOutput(t, out, "restart-binding-1-6 gen=6 -> APPLIED", 20*time.Second)
}

// An agent restarted with its data dir intact reconnects to the same
// gateway, and typed commands follow it to the new session.
func TestIntegrationConsoleReachesAgentAfterItRestarts(t *testing.T) {
	gw := startGateway(t, nil)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	out := &syncBuffer{}
	gw.Console(strings.NewReader("restart\n"), out)
	waitOutput(t, out, "restart-binding-1-1 gen=1 -> APPLIED", 20*time.Second)

	h.stop()
	h2 := startAgentWith(t, gw, h.dir, ad)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	gw.Console(strings.NewReader("stop\n"), out)
	waitOutput(t, out, "stop-binding-1-2 gen=2 -> APPLIED", 20*time.Second)
}

// disconnect drops the session while the gateway keeps its CA and enrolment,
// so the agent reconnects with the identity it has — unlike a gateway restart.
func TestIntegrationConsoleDisconnectKeepsEnrolment(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	out := &syncBuffer{}
	gw.Console(strings.NewReader("disconnect\n"), out)
	waitOutput(t, out, "session closed; the agent will reconnect", 5*time.Second)
	deadline := time.Now().Add(30 * time.Second)
	for gw.Sessions() < 2 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if gw.Sessions() < 2 {
		t.Fatalf("agent did not reconnect: sessions=%d", gw.Sessions())
	}
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if n := gw.Count("EnrollRequest"); n != 1 {
		t.Fatalf("a disconnect must not force re-enrolment: EnrollRequest=%d", n)
	}
}
