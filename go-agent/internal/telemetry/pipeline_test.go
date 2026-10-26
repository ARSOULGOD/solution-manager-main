package telemetry

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

type logBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *logBuf) Write(p []byte) (int, error) { l.mu.Lock(); defer l.mu.Unlock(); return l.b.Write(p) }
func (l *logBuf) String() string              { l.mu.Lock(); defer l.mu.Unlock(); return l.b.String() }

// watching builds a pipeline with the given age cap and runs only its push watcher.
func watching(t *testing.T, age time.Duration) (*Pipeline, *view.Hub, *logBuf) {
	t.Helper()
	hub, logs := view.NewHub(), &logBuf{}
	p, err := New(Config{Store: store.MustOpen(t.TempDir()), Declarations: declare.NewSet(), BufferAge: age, View: hub,
		Log: slog.New(slog.NewTextHandler(logs, nil))})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { p.watchPush(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return p, hub, logs
}

func sessionWith(id string, push *time.Duration) view.Snapshot {
	return view.Snapshot{Session: &view.SessionInfo{ID: id, Limits: msg.Limits{TelemetryPushInterval: push}}}
}

func durp(d time.Duration) *time.Duration { return &d }

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

func pushGauge(p *Pipeline) float64 { return testutil.ToFloat64(p.metrics.PushInterval) }
func refusals(p *Pipeline) float64 {
	return testutil.ToFloat64(p.metrics.LimitsIgnored.WithLabelValues("push"))
}

func TestANewSessionReportsItsPushIntervalAndADropKeepsIt(t *testing.T) {
	p, hub, _ := watching(t, time.Hour)
	if got := pushGauge(p); got != 60 {
		t.Fatalf("before any session the gauge is %v, want the default 60", got)
	}
	hub.Publish(sessionWith("s1", durp(30*time.Second)))
	eventually(t, "the DP's 30 s", func() bool { return pushGauge(p) == 30 })
	hub.Publish(view.Snapshot{}) // the session drops
	time.Sleep(50 * time.Millisecond)
	if got := pushGauge(p); got != 30 {
		t.Fatalf("a session drop changed the gauge to %v", got)
	}
	hub.Publish(sessionWith("s2", nil)) // today's gateway: no field
	eventually(t, "the default back", func() bool { return pushGauge(p) == 60 })
	hub.Publish(sessionWith("s3", durp(0)))
	eventually(t, "at once", func() bool { return pushGauge(p) == 0 })
}

func TestARefusedPushIntervalIsCountedOncePerSessionAndLogged(t *testing.T) {
	p, hub, logs := watching(t, time.Hour)
	hub.Publish(sessionWith("s1", durp(2*time.Hour)))
	eventually(t, "the refusal counted", func() bool { return refusals(p) == 1 })
	hub.Publish(sessionWith("s1", durp(2*time.Hour))) // the same session again
	time.Sleep(50 * time.Millisecond)
	if got := refusals(p); got != 1 {
		t.Fatalf("counted %v times for one session", got)
	}
	if got := pushGauge(p); got != 60 {
		t.Fatalf("gauge %v, want the default 60", got)
	}
	for _, want := range []string{`msg="telemetry: DP push interval refused; using the default"`, `value=2h0m0s`, `reason="not below the buffer age cap"`} {
		if !strings.Contains(logs.String(), want) {
			t.Errorf("log lacks %s:\n%s", want, logs.String())
		}
	}
}

// Review Focus 2.
func TestAnAgeCapAtOrBelowTheDefaultSendsAtOnceWithoutCounting(t *testing.T) {
	p, hub, logs := watching(t, 30*time.Second)
	if got := pushGauge(p); got != 0 {
		t.Fatalf("before any session the gauge is %v, want 0", got)
	}
	hub.Publish(sessionWith("s1", nil))
	eventually(t, "the warning", func() bool {
		return strings.Contains(logs.String(), `msg="telemetry: the default push interval is not below the buffer age cap; sending each batch at once"`)
	})
	if got := refusals(p); got != 0 {
		t.Fatalf("counted %v refusals; no DP value was refused", got)
	}
	if !strings.Contains(logs.String(), `reason="default not below the buffer age cap"`) {
		t.Fatalf("the warning lacks the reason; log:\n%s", logs.String())
	}
	if got := pushGauge(p); got != 0 {
		t.Fatalf("after the session the gauge is %v, want 0", got)
	}
}
