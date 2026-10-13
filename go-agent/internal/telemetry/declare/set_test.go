package declare

import (
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func metric(name string) msg.TelemetryMetric {
	return msg.TelemetryMetric{Name: name, MetricType: msg.MetricGauge, Unit: "entries",
		HTTPJSON: &msg.HTTPJSONSource{Port: 8080, Path: "/api/cache/size", Pointer: "/size", OnUnavailable: msg.OnUnavailableDegrade}}
}

func decl(binding string, ms ...msg.TelemetryMetric) msg.TelemetryDeclarations {
	return msg.TelemetryDeclarations{BindingID: binding, Interval: 5 * time.Second, Metrics: ms}
}

// AG-D28: a TelemetryDeclarations replaces the binding's set atomically.
func TestReplaceSwapsTheBindingsWholeSet(t *testing.T) {
	s := NewSet()
	if _, err := s.Replace(decl("b1", metric("a"), metric("b"))); err != nil {
		t.Fatal(err)
	}
	if got := s.Bindings()["b1"]; len(got.Metrics) != 2 || got.Interval != 5*time.Second || got.Timeout != 5*time.Second {
		t.Fatalf("%+v", got)
	}
	if _, err := s.Replace(decl("b1", metric("c"))); err != nil {
		t.Fatal(err)
	}
	got := s.Bindings()["b1"]
	if len(got.Metrics) != 1 || got.Metrics[0].Name != "c" {
		t.Fatalf("the new set replaces the old whole, no merge: %+v", got)
	}
	select {
	case <-s.Changed():
	default:
		t.Fatal("a change is signalled")
	}
}

func TestAMessageThatFailsValidationChangesNothing(t *testing.T) {
	s := NewSet()
	if _, err := s.Replace(decl("b1", metric("a"))); err != nil {
		t.Fatal(err)
	}
	bad := metric("b")
	bad.HTTPJSON.Path = "no-slash"
	for _, d := range []msg.TelemetryDeclarations{
		decl("b1", metric("c"), bad),
		decl("b1", metric("dup"), metric("dup")),
		{BindingID: "../escape", Interval: 5 * time.Second, Metrics: []msg.TelemetryMetric{metric("a")}},
		{BindingID: "b1", Interval: time.Millisecond, Metrics: []msg.TelemetryMetric{metric("a")}},
	} {
		if _, err := s.Replace(d); err == nil {
			t.Fatalf("%+v must be refused", d)
		}
	}
	got := s.Bindings()["b1"]
	if len(got.Metrics) != 1 || got.Metrics[0].Name != "a" {
		t.Fatalf("a refused message left the previous set untouched: %+v", got)
	}
}

// AG-D28: a metric the agent cannot collect (histogram, unknown source kind) is
// dropped with a counter, never a zero sample.
func TestUncollectableMetricsAreDroppedAndCounted(t *testing.T) {
	s := NewSet()
	hist := metric("h")
	hist.MetricType = msg.MetricHistogram
	unknown := msg.TelemetryMetric{Name: "u", MetricType: msg.MetricGauge, Unit: "x"} // no source: a kind this agent does not know
	dropped, err := s.Replace(decl("b1", metric("a"), hist, unknown))
	if err != nil || dropped != 2 {
		t.Fatal(err, dropped)
	}
	if got := s.Bindings()["b1"]; len(got.Metrics) != 1 || got.Metrics[0].Name != "a" {
		t.Fatalf("%+v", got)
	}
}

// OR-101: a declared probe is carried; none declared means none (and the previous
// binding's probe does not linger).
func TestProbeFollowsTheMessageAndAbsentMeansNone(t *testing.T) {
	s := NewSet()
	if s.Probe("b1") != nil {
		t.Fatal("nothing declared, nothing to probe")
	}
	d := decl("b1", metric("a"))
	d.HealthProbe = &msg.HealthProbe{Kind: msg.ProbeActuatorHealth, Path: "/actuator/health", HostPort: 18080, Strength: msg.StrengthReadiness}
	if _, err := s.Replace(d); err != nil {
		t.Fatal(err)
	}
	if p := s.Probe("b1"); p == nil || p.HostPort != 18080 || p.Strength != msg.StrengthReadiness {
		t.Fatalf("%+v", p)
	}
	p := s.Probe("b1")
	p.HostPort = 1
	if s.Probe("b1").HostPort != 18080 {
		t.Fatal("the caller gets a copy")
	}
	if _, err := s.Replace(decl("b1", metric("a"))); err != nil {
		t.Fatal(err)
	}
	if s.Probe("b1") != nil {
		t.Fatal("a message without a probe clears the binding's probe")
	}
	d.HealthProbe.Path = "nope"
	if _, err := s.Replace(d); err == nil {
		t.Fatal("a malformed probe is refused")
	}
}

func TestSetIsSafeUnderConcurrentReplaceAndRead(t *testing.T) {
	s := NewSet()
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				_, _ = s.Replace(decl("b1", metric("a"), metric("b")))
			}
		}()
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				if m := s.Bindings()["b1"].Metrics; len(m) != 0 && len(m) != 2 {
					t.Errorf("torn set: %d", len(m))
				}
				_ = s.Probe("b1")
			}
		}()
	}
	wg.Wait()
}

// review finding 10: the set is bounded.
func TestSetIsBounded(t *testing.T) {
	s := NewSet()
	var many []msg.TelemetryMetric
	for i := 0; i <= MaxMetricsPerBinding; i++ {
		many = append(many, metric("m"+string(rune('a'+i%26))+string(rune('a'+i/26%26))+string(rune('a'+i/676))))
	}
	if _, err := s.Replace(decl("b1", many...)); err == nil {
		t.Fatal("a binding with more than the cap of metrics is refused")
	}
	for i := 0; i < MaxBindings; i++ {
		if _, err := s.Replace(decl("b"+string(rune('A'+i%26))+string(rune('A'+i/26)), metric("a"))); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Replace(decl("one-too-many", metric("a"))); err == nil {
		t.Fatal("a new binding beyond the cap is refused")
	}
	if _, err := s.Replace(decl("bA"+"A", metric("z"))); err != nil {
		t.Fatalf("replacing an existing binding still works at the cap: %v", err)
	}
}

// review finding 16: a probe kind this agent does not know drops the PROBE (nothing is probed), not the message.
func TestUnknownProbeKindDropsTheProbeNotTheMessage(t *testing.T) {
	s := NewSet()
	d := decl("b1", metric("a"))
	d.HealthProbe = &msg.HealthProbe{Kind: msg.ProbeKind("FUTURE_KIND"), Path: "/h", HostPort: 18080}
	dropped, err := s.Replace(d)
	if err != nil || dropped != 1 {
		t.Fatal(err, dropped)
	}
	if s.Probe("b1") != nil || len(s.Bindings()["b1"].Metrics) != 1 {
		t.Fatal("the metrics apply; nothing is probed")
	}
}
