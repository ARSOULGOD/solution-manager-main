//go:build integration

package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

func startGateway(t *testing.T, sc *fakegw.Scenario) *fakegw.Server {
	t.Helper()
	gw, err := fakegw.Start(fakegw.Config{Listen: "127.0.0.1:0", Tokens: []string{"tok"}, Scenario: sc,
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(gw.Stop)
	return gw
}

type agentHandle struct {
	loop   *Loop
	dir    string
	cancel context.CancelFunc
	done   chan error

	// stopOnce guards the cancel+drain below so a test that calls stop()
	// explicitly (e.g. before restarting against the same store dir) and
	// then hits the Cleanup below does not receive from done twice: done is
	// a single-value channel (Run sends to it exactly once), so a second
	// unconditional receive — the Cleanup used to do this inline — would
	// block forever once stop() had already drained it. Confirmed by a
	// goroutine dump during self-review: TestIntegrationRestartReusesIdentityWithoutEnroll
	// hung with a goroutine parked on the Cleanup's second <-h.done.
	stopOnce sync.Once
}

func startAgent(t *testing.T, gw *fakegw.Server, dir string, mutate func(*BuildConfig)) *agentHandle {
	t.Helper()
	if dir == "" {
		dir = filepath.Join(t.TempDir(), "data")
	}
	bc := BuildConfig{DataDir: dir, Endpoint: gw.Endpoint(), Token: "tok", TrustBundlePEM: gw.CAPEM(), AgentVersion: "test",
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if mutate != nil {
		mutate(&bc)
	}
	l, _, err := Build(bc)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	h := &agentHandle{loop: l, dir: dir, cancel: cancel, done: make(chan error, 1)}
	go func() { h.done <- l.Run(ctx) }()
	t.Cleanup(h.stop)
	return h
}

func (h *agentHandle) stop() {
	h.stopOnce.Do(func() {
		h.cancel()
		<-h.done
	})
}

func waitPhase(t *testing.T, l *Loop, want Phase, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if l.Snapshot().Session.Phase == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("phase %s not reached (at %s)", want, l.Snapshot().Session.Phase)
}

func waitHalt(t *testing.T, l *Loop, want msg.ErrorCode, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if l.Halted() == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("halt %s not reached (halted=%q phase=%s)", want, l.Halted(), l.Snapshot().Session.Phase)
}

// fakeAdapter is a filesystem-only runtime: it writes where the test can see
// it and simulates restarts by advancing StartedAt.
type fakeAdapter struct {
	dir       string
	started   time.Time
	running   bool
	failWrite error
	scrape    string // host:port collectors fetch from (telemetry tests)
}

func newFakeAdapter(t *testing.T) *fakeAdapter {
	return &fakeAdapter{dir: t.TempDir(), started: time.Now(), running: true}
}

func (a *fakeAdapter) target() string { return filepath.Join(a.dir, "application.yml") }

func (a *fakeAdapter) Resolve(_ context.Context, binding string) (runtime.Placement, error) {
	return runtime.Placement{BindingID: binding, ContainerID: "container-abc", Name: "java1",
		ConfigPathInContainer: "/etc/app/application.yml", HostPath: a.target(), Mechanism: "additional-location", UID: -1, GID: -1}, nil
}

func (a *fakeAdapter) Preflight(_ context.Context, p runtime.Placement) (msg.PreflightResult, error) {
	return msg.PreflightResult{ContainerID: p.ContainerID, ImageDigest: "sha256:test", ConfigPathInContainer: p.ConfigPathInContainer,
		HostPath: p.HostPath, Mechanism: p.Mechanism, Writable: true}, nil
}

func (a *fakeAdapter) WriteConfig(_ context.Context, p runtime.Placement, payload []byte, sha string, pr runtime.Progress) (runtime.WriteResult, error) {
	if a.failWrite != nil {
		_ = pr(msg.StepStaged)
		return runtime.WriteResult{}, a.failWrite
	}
	return configwriter.Write(p.HostPath, payload, sha, -1, -1, pr)
}

func (a *fakeAdapter) Restart(context.Context, runtime.Placement) error {
	a.started = time.Now().Add(time.Second)
	a.running = true
	return nil
}

func (a *fakeAdapter) Stop(context.Context, runtime.Placement) error { a.running = false; return nil }

func (a *fakeAdapter) Observe(context.Context, runtime.Placement) (runtime.ReplicaFacts, error) {
	return runtime.ReplicaFacts{ReplicaRef: "java1", Incarnation: "container-abc", Running: a.running,
		StartedAt: a.started, ImageDigest: "sha256:test"}, nil
}

func (a *fakeAdapter) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) {
	if a.scrape == "" {
		return "", errors.New("fakeAdapter: no scrape address")
	}
	return a.scrape, nil
}

func startAgentWith(t *testing.T, gw *fakegw.Server, dir string, ad runtime.Adapter) *agentHandle {
	return startAgent(t, gw, dir, func(bc *BuildConfig) { bc.Adapter = ad })
}
