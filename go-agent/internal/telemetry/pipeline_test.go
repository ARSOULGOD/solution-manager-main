package telemetry

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

// watching builds a pipeline with one binding declared at 5 s and runs only
// its cadence watcher.
func watching(t *testing.T) (*Pipeline, *view.Hub) {
	t.Helper()
	hub := view.NewHub()
	decl := &declare.File{SchemaVersion: 1, Bindings: map[string]declare.Binding{"binding-1": {Interval: 5 * time.Second, Timeout: time.Second}}}
	p, err := New(Config{Store: store.MustOpen(t.TempDir()), Declarations: decl, BufferAge: time.Hour, View: hub,
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { p.watchCadence(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return p, hub
}

func sessionWith(id string, pull, push time.Duration) view.Snapshot {
	return view.Snapshot{Session: &view.SessionInfo{ID: id, Limits: msg.Limits{TelemetryPullInterval: pull, TelemetryPushInterval: push}}}
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("never: %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func gauges(p *Pipeline) (pull, push float64) {
	return testutil.ToFloat64(p.metrics.PullInterval.WithLabelValues("binding-1")), testutil.ToFloat64(p.metrics.PushInterval)
}

func TestANewSessionAppliesTheDPIntervalsAndADropKeepsThem(t *testing.T) {
	p, hub := watching(t)
	if pull, push := gauges(p); pull != 5 || push != 0 {
		t.Fatalf("at start: pull %v push %v, want the declared 5 and 0", pull, push)
	}
	hub.Publish(sessionWith("s1", 2*time.Second, time.Minute))
	want := cadence.Settings{Pull: 2 * time.Second, Push: time.Minute}
	eventually(t, "the DP's intervals applied", func() bool { return p.cadence.Current() == want })
	if pull, push := gauges(p); pull != 2 || push != 60 {
		t.Fatalf("gauges pull %v push %v", pull, push)
	}
	hub.Publish(view.Snapshot{}) // the session drops
	time.Sleep(50 * time.Millisecond)
	if got := p.cadence.Current(); got != want {
		t.Fatalf("a session drop changed the intervals to %+v", got)
	}
	hub.Publish(sessionWith("s2", 0, 0)) // a new session that sets neither
	eventually(t, "the local defaults back", func() bool { return p.cadence.Current() == (cadence.Settings{}) })
	if pull, push := gauges(p); pull != 5 || push != 0 {
		t.Fatalf("gauges after s2: pull %v push %v", pull, push)
	}
}

func TestARefusedDPIntervalIsCountedAndTheDefaultKept(t *testing.T) {
	p, hub := watching(t)
	hub.Publish(sessionWith("s1", 500*time.Millisecond, 2*time.Hour))
	ignored := func(limit string) float64 { return testutil.ToFloat64(p.metrics.LimitsIgnored.WithLabelValues(limit)) }
	eventually(t, "both refusals counted", func() bool { return ignored("pull") == 1 && ignored("push") == 1 })
	if got := p.cadence.Current(); got != (cadence.Settings{}) {
		t.Fatalf("settings %+v, want the defaults", got)
	}
	if pull, push := gauges(p); pull != 5 || push != 0 {
		t.Fatalf("gauges pull %v push %v", pull, push)
	}
}
