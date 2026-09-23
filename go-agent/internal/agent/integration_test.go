//go:build integration

package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestIntegrationHappyPathReachesActive(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if gw.Count("SessionOpen") != 1 || gw.Count("EnrollRequest") != 1 {
		t.Fatalf("open=%d enroll=%d", gw.Count("SessionOpen"), gw.Count("EnrollRequest"))
	}
	if !gw.WaitFor("Heartbeat", 1, 15*time.Second) {
		t.Fatal("no heartbeat")
	}
}

// lostReplyWire performs the real Enroll but reports the first reply as lost.
type lostReplyWire struct {
	Wire
	dropped bool
}

func (w *lostReplyWire) Enroll(ctx context.Context, ep string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	resp, err := w.Wire.Enroll(ctx, ep, req)
	if err == nil && !w.dropped {
		w.dropped = true
		return nil, errors.New("simulated: reply lost in transit")
	}
	return resp, err
}

func TestIntegrationLostEnrollReplyReplaysSameCSR(t *testing.T) {
	gw := startGateway(t, nil)
	// Build by hand so the wire can be wrapped before Run — Build is in this
	// package, so l.d is reachable.
	l, _, err := Build(BuildConfig{DataDir: filepath.Join(t.TempDir(), "data"), Endpoint: gw.Endpoint(), Token: "tok",
		TrustBundlePEM: gw.CAPEM(), AgentVersion: "test", Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	l.d.Wire = &lostReplyWire{Wire: l.d.Wire}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	waitPhase(t, l, PhaseActive, 15*time.Second)
	// Two EnrollRequests reached the gateway with the same CSR; the second was
	// the stored replay (no TOKEN_CONSUMED), so the agent still came up.
	if gw.Count("EnrollRequest") < 2 {
		t.Fatalf("expected a replayed enroll, got %d", gw.Count("EnrollRequest"))
	}
	reqs := gw.Received("EnrollRequest")
	a := reqs[len(reqs)-2].(msg.EnrollRequest).CSRDER
	b := reqs[len(reqs)-1].(msg.EnrollRequest).CSRDER
	if string(a) != string(b) {
		t.Fatal("retry must resend the identical CSR")
	}
	// TOKEN_CONSUMED would have halted the agent instead of activating it.
	if l.Halted() != "" {
		t.Fatalf("halted: %s", l.Halted())
	}
}

func TestIntegrationRestartReusesIdentityWithoutEnroll(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	h.stop()
	h2 := startAgent(t, gw, h.dir, nil)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	if gw.Count("EnrollRequest") != 1 {
		t.Fatalf("a restarted agent must not re-enroll: %d", gw.Count("EnrollRequest"))
	}
	if gw.Count("SessionOpen") != 2 {
		t.Fatalf("SessionOpen=%d", gw.Count("SessionOpen"))
	}
}

func TestIntegrationRejectReEnrollHalts(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.OnOpen = "reject:RE_ENROLL_REQUIRED"
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitHalt(t, h.loop, msg.ErrReEnrollRequired, 10*time.Second)
}
