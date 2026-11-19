//go:build integration

package agent

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

// multiAdapter is a filesystem-only runtime with one simulated container per
// binding: each has its own config directory, run state and start time.
type multiAdapter struct {
	mu  sync.Mutex
	dir string
	cs  map[string]*simContainer
}

type simContainer struct {
	started time.Time
	running bool
}

func newMultiAdapter(t *testing.T, bindings ...string) *multiAdapter {
	a := &multiAdapter{dir: t.TempDir(), cs: map[string]*simContainer{}}
	for _, b := range bindings {
		a.cs[b] = &simContainer{started: time.Now(), running: true}
		if err := os.MkdirAll(filepath.Dir(a.target(b)), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	return a
}

func (a *multiAdapter) target(binding string) string {
	return filepath.Join(a.dir, binding, "application.yml")
}

func (a *multiAdapter) Resolve(_ context.Context, binding string) (runtime.Placement, error) {
	if _, ok := a.cs[binding]; !ok {
		return runtime.Placement{}, fmt.Errorf("no container for %s", binding)
	}
	return runtime.Placement{BindingID: binding, ContainerID: "id-" + binding, Name: "app-" + binding,
		ConfigPathInContainer: "/etc/app/application.yml", HostPath: a.target(binding), Mechanism: "additional-location", UID: -1, GID: -1}, nil
}

func (a *multiAdapter) Preflight(_ context.Context, p runtime.Placement) (msg.PreflightResult, error) {
	return msg.PreflightResult{ContainerID: p.ContainerID, ImageDigest: "sha256:test", ConfigPathInContainer: p.ConfigPathInContainer,
		HostPath: p.HostPath, Mechanism: p.Mechanism, Writable: true}, nil
}

func (a *multiAdapter) WriteConfig(_ context.Context, p runtime.Placement, payload []byte, sha string, pr runtime.Progress) (runtime.WriteResult, error) {
	return configwriter.Write(p.HostPath, payload, sha, -1, -1, pr)
}

func (a *multiAdapter) Restart(_ context.Context, p runtime.Placement) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	c := a.cs[p.BindingID]
	c.started, c.running = time.Now().Add(time.Second), true
	return nil
}

func (a *multiAdapter) Stop(_ context.Context, p runtime.Placement) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.cs[p.BindingID].running = false
	return nil
}

func (a *multiAdapter) Observe(_ context.Context, p runtime.Placement) (runtime.ReplicaFacts, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	c := a.cs[p.BindingID]
	return runtime.ReplicaFacts{ReplicaRef: p.Name, Incarnation: p.ContainerID, Running: c.running, StartedAt: c.started, ImageDigest: "sha256:test"}, nil
}

func (a *multiAdapter) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) {
	return "", fmt.Errorf("multiAdapter: no scrape address")
}

func (a *multiAdapter) running(binding string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.cs[binding].running
}

// One agent, two containers: a command addressed to one binding changes only
// that container's config and lifecycle, and each binding numbers its own
// generations.
func TestIntegrationOneAgentControlsTwoContainers(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Lease.BindingIDs = []string{"binding-1", "binding-2"}
	sc.Limits.ReportInterval = 300 * time.Millisecond
	gw := startGateway(t, sc)
	ad := newMultiAdapter(t, "binding-1", "binding-2")
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)

	dir := t.TempDir()
	write := func(name, body string) string {
		f := filepath.Join(dir, name)
		if err := os.WriteFile(f, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		return f
	}
	f1, f2 := write("one.yml", "server.port: 8081\n"), write("two.yml", "server.port: 8082\n")

	out := &syncBuffer{}
	go gw.Console(strings.NewReader(
		"apply "+f1+"\n@binding-2 apply "+f2+"\n@binding-2 stop\n@binding-2 preflight\n"), out)
	for _, want := range []string{
		"apply-1 gen=1 -> APPLIED",           // default: the lease's first binding
		"binding-2-apply-1 gen=1 -> APPLIED", // binding-2 starts at its own generation 1
		"binding-2-stop-2 gen=2 -> APPLIED",
		"binding-2-preflight-3 gen=3 -> PREFLIGHT_OK",
	} {
		waitOutput(t, out, want, 20*time.Second)
	}

	for b, want := range map[string]string{"binding-1": "server.port: 8081\n", "binding-2": "server.port: 8082\n"} {
		if got, err := os.ReadFile(ad.target(b)); err != nil || string(got) != want {
			t.Errorf("%s config = %q, %v; want %q", b, got, err, want)
		}
	}
	if !ad.running("binding-1") || ad.running("binding-2") {
		t.Errorf("stop @binding-2 must stop only binding-2: running 1=%v 2=%v", ad.running("binding-1"), ad.running("binding-2"))
	}

	// Periodic reports cover both containers.
	seen := map[string]bool{}
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) && len(seen) < 2 {
		for _, r := range gw.Received("ActualStateReportBatch") {
			for _, it := range r.(msg.ActualStateReportBatch).Items {
				seen[it.BindingID] = true
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !seen["binding-1"] || !seen["binding-2"] {
		t.Fatalf("reports must cover both bindings, saw %v", seen)
	}
}

func TestIntegrationOfferToAnUnleasedBindingIsRefused(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgentWith(t, gw, "", newMultiAdapter(t, "binding-1"))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if _, err := gw.Offer("binding-9", msg.OpRestart, nil); err == nil || !strings.Contains(err.Error(), "not in the lease") {
		t.Fatalf("want a not-in-the-lease error, got %v", err)
	}
}
