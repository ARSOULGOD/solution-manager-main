package collect

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/tmetrics"
)

type target struct {
	addr    string
	resolve func() (runtime.Placement, error)
}

func (t *target) Resolve(context.Context, string) (runtime.Placement, error) {
	if t.resolve != nil {
		return t.resolve()
	}
	return runtime.Placement{BindingID: "binding-1", ContainerID: "c1", Name: "java1"}, nil
}
func (t *target) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) {
	return t.addr, nil
}
func (t *target) Preflight(context.Context, runtime.Placement) (msg.PreflightResult, error) {
	return msg.PreflightResult{}, errors.New("unused")
}
func (t *target) WriteConfig(context.Context, runtime.Placement, []byte, string, runtime.Progress) (runtime.WriteResult, error) {
	return runtime.WriteResult{}, errors.New("unused")
}
func (t *target) Restart(context.Context, runtime.Placement) error { return errors.New("unused") }
func (t *target) Stop(context.Context, runtime.Placement) error    { return errors.New("unused") }
func (t *target) Observe(context.Context, runtime.Placement) (runtime.ReplicaFacts, error) {
	return runtime.ReplicaFacts{}, errors.New("unused")
}

type sink struct {
	mu      sync.Mutex
	batches [][]buffer.Sample
}

func (s *sink) Append(_ string, _ time.Time, samples []buffer.Sample) (uint64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.batches = append(s.batches, samples)
	return uint64(len(s.batches)), nil
}

func (s *sink) all() [][]buffer.Sample {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.batches
}

func ptr(s string) *string { return &s }

func decl(timeout time.Duration, metrics ...declare.Metric) declare.Binding {
	return declare.Binding{Interval: time.Second, Timeout: timeout, Metrics: metrics}
}

func metric(name, typ, pointer string) declare.Metric {
	return declare.Metric{Name: name, MetricType: typ, Unit: "x",
		Source: declare.Source{Kind: "http_json", Port: 8081, Path: "/api/cache/stats", Pointer: ptr(pointer), OnUnavailable: "degrade"}}
}

var t0 = time.Date(2026, 10, 1, 7, 0, 0, 0, time.UTC)

func collector(addr string, out *sink, m *tmetrics.Metrics, d declare.Binding) *Collector {
	return &Collector{Binding: "binding-1", Decl: d, Adapter: &target{addr: addr}, InLease: func(string) bool { return true },
		Out: out, Now: func() time.Time { return t0 }, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Metrics: m}
}

func serve(t *testing.T, h http.HandlerFunc) string {
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return strings.TrimPrefix(srv.URL, "http://")
}

func TestATickIsOneBatchFromTheDeclaredPointers(t *testing.T) {
	var gets atomic.Int32
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		gets.Add(1)
		if r.URL.Path != "/api/cache/stats" {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `{"hitRate": 0.93, "missCount": 12}`)
	})
	out := &sink{}
	collector(addr, out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"), metric("cache.miss_count", "counter", "/missCount"))).Tick(context.Background())
	want := []buffer.Sample{
		{Name: "cache.hit_rate", Type: "gauge", TS: t0, Value: 0.93, ReplicaRef: "java1", Incarnation: "c1"},
		{Name: "cache.miss_count", Type: "counter", TS: t0, Value: 12, ReplicaRef: "java1", Incarnation: "c1"},
	}
	if got := out.all(); len(got) != 1 || !equal(got[0], want) {
		t.Fatalf("batches %+v", got)
	}
	if gets.Load() != 1 {
		t.Fatalf("two metrics on one path cost %d GETs, want 1", gets.Load())
	}
}

func equal(a, b []buffer.Sample) bool {
	x, _ := json.Marshal(a)
	y, _ := json.Marshal(b)
	return string(x) == string(y)
}

func TestMissingOrNonNumericValuesProduceNoSampleNeverZero(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, `{"hitRate": "n/a"}`) })
	out, m := &sink{}, tmetrics.New()
	collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"), metric("cache.miss_count", "counter", "/missCount"))).Tick(context.Background())
	if len(out.all()) != 0 {
		t.Fatalf("a tick with no numbers must append nothing: %+v", out.all())
	}
	for _, name := range []string{"cache.hit_rate", "cache.miss_count"} {
		if v := testutil.ToFloat64(m.Unavailable.WithLabelValues("binding-1", name)); v != 1 {
			t.Errorf("unavailable{%s} = %v", name, v)
		}
	}
}

func TestBadResponsesAreScrapeErrors(t *testing.T) {
	for reason, h := range map[string]http.HandlerFunc{
		"status": func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "boom", http.StatusInternalServerError) },
		"decode": func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "<html>") },
	} {
		out, m := &sink{}, tmetrics.New()
		collector(serve(t, h), out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
		if len(out.all()) != 0 || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", reason)) != 1 {
			t.Errorf("%s: batches %d, errors %v", reason, len(out.all()), testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", reason)))
		}
	}
}

// Review Focus 2.
func TestOversizedBodyIsRefused(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"hitRate": 0.5, "pad": [`+strings.Repeat(`0,`, MaxBody)+`0]}`)
	})
	out, m := &sink{}, tmetrics.New()
	collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
	if len(out.all()) != 0 || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "decode")) != 1 {
		t.Fatalf("a body over %d bytes must be refused, not decoded", MaxBody)
	}
}

func TestAHungEndpointIsBoundedByTheTimeout(t *testing.T) {
	addr := serve(t, func(_ http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	out, m := &sink{}, tmetrics.New()
	start := time.Now()
	collector(addr, out, m, decl(100*time.Millisecond, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
	if time.Since(start) > 2*time.Second || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "http")) != 1 {
		t.Fatalf("tick took %v", time.Since(start))
	}
}

func TestAPanicIsRecoveredAndCounted(t *testing.T) {
	out, m := &sink{}, tmetrics.New()
	c := collector("unused", out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Adapter = &target{resolve: func() (runtime.Placement, error) { panic("adapter bug") }}
	c.Tick(context.Background()) // must return
	if v := testutil.ToFloat64(m.CollectorPanics.WithLabelValues("binding-1")); v != 1 {
		t.Fatalf("panics = %v", v)
	}
}

func TestABindingOutsideTheLeaseIsNotScraped(t *testing.T) {
	var gets atomic.Int32
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) { gets.Add(1); _, _ = io.WriteString(w, `{"hitRate": 1}`) })
	c := collector(addr, &sink{}, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.InLease = func(string) bool { return false }
	c.Tick(context.Background())
	if gets.Load() != 0 {
		t.Fatal("scraped a binding the lease does not name")
	}
}

// countingRT counts this collector's own requests in flight. Counting on the
// server would be flaky: a cancelled handler can still be finishing when the
// next scrape arrives, though the collector has already moved on.
type countingRT struct{ inflight, peak atomic.Int32 }

func (c *countingRT) RoundTrip(r *http.Request) (*http.Response, error) {
	n := c.inflight.Add(1)
	defer c.inflight.Add(-1)
	for p := c.peak.Load(); n > p && !c.peak.CompareAndSwap(p, n); p = c.peak.Load() {
	}
	return http.DefaultTransport.RoundTrip(r)
}

// Review Focus 5: an endpoint slower than the timeout, at the interval's edge.
func TestSlowEndpointNeverOverlaps(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(60 * time.Millisecond):
		case <-r.Context().Done():
			return
		}
		_, _ = io.WriteString(w, `{"hitRate": 1}`)
	})
	rt := &countingRT{}
	d := declare.Binding{Interval: 50 * time.Millisecond, Timeout: 50 * time.Millisecond, Metrics: []declare.Metric{metric("cache.hit_rate", "gauge", "/hitRate")}}
	c := collector(addr, &sink{}, nil, d)
	c.Client = &http.Client{Transport: rt}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	c.Run(ctx)
	if p := rt.peak.Load(); p != 1 {
		t.Fatalf("peak in-flight scrapes %d, want 1", p)
	}
}

func TestARedirectIsAStatusErrorAndIsNeverFollowed(t *testing.T) {
	var second atomic.Int32
	other := serve(t, func(http.ResponseWriter, *http.Request) { second.Add(1) })
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://"+other+"/x", http.StatusFound)
	})
	out, m := &sink{}, tmetrics.New()
	collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
	if len(out.all()) != 0 || second.Load() != 0 {
		t.Fatalf("batches %+v, redirect target hit %d times", out.all(), second.Load())
	}
	if v := testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "status")); v != 1 {
		t.Fatalf("status errors = %v", v)
	}
}

func TestANullBodyCountsEachMetricUnavailable(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, `null`) })
	out, m := &sink{}, tmetrics.New()
	collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"), metric("cache.miss_count", "counter", "/missCount"))).Tick(context.Background())
	if len(out.all()) != 0 {
		t.Fatalf("batches %+v", out.all())
	}
	for _, name := range []string{"cache.hit_rate", "cache.miss_count"} {
		if v := testutil.ToFloat64(m.Unavailable.WithLabelValues("binding-1", name)); v != 1 {
			t.Errorf("unavailable{%s} = %v", name, v)
		}
	}
}

func okTarget(t *testing.T) string {
	return serve(t, func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, `{"hitRate": 1}`) })
}

func runFor(c *Collector, d time.Duration) {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	defer cancel()
	c.Run(ctx)
}

func TestTheDPPullIntervalReplacesTheDeclaredOne(t *testing.T) {
	out := &sink{}
	c := collector(okTarget(t), out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	c.Cadence.Set(cadence.Settings{Pull: 50 * time.Millisecond})
	runFor(c, 400*time.Millisecond)
	if n := len(out.all()); n < 5 {
		t.Fatalf("%d ticks in 400ms at a 50ms DP interval; the declared 1s gives 1", n)
	}
}

func TestAZeroDPPullKeepsTheDeclaredInterval(t *testing.T) {
	out := &sink{}
	c := collector(okTarget(t), out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	c.Cadence.Set(cadence.Settings{Push: time.Minute})
	runFor(c, 400*time.Millisecond)
	if n := len(out.all()); n != 1 {
		t.Fatalf("%d ticks in 400ms; the declared 1s gives exactly 1", n)
	}
}

func TestACadenceChangeResetsTheTicker(t *testing.T) {
	out := &sink{}
	c := collector(okTarget(t), out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	go func() {
		time.Sleep(100 * time.Millisecond)
		c.Cadence.Set(cadence.Settings{Pull: 50 * time.Millisecond})
	}()
	runFor(c, 600*time.Millisecond)
	if n := len(out.all()); n < 5 {
		t.Fatalf("%d ticks: the ticker kept the declared 1s after the change", n)
	}
}

func TestTheTimeoutIsClampedToTheDPPullInterval(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(300 * time.Millisecond):
		case <-r.Context().Done():
			return
		}
		_, _ = io.WriteString(w, `{"hitRate": 1}`)
	})
	out, m := &sink{}, tmetrics.New()
	c := collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	c.Cadence.Set(cadence.Settings{Pull: 100 * time.Millisecond})
	c.Tick(context.Background())
	if len(out.all()) != 0 || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "http")) != 1 {
		t.Fatalf("a 300ms answer was accepted under a 100ms pull interval: %d batches", len(out.all()))
	}
}
