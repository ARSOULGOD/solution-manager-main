package declare

import (
	"errors"
	"fmt"
	"maps"
	"reflect"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/jsonptr"
)

// FixedInterval is OR-73 / OR-128 (8)'s fixed 5 s, used when a message carries
// none; the package's own intervalSeconds is never what arrives (AG-D28).
const FixedInterval = 5 * time.Second

// The set is bounded: a binding carries at most MaxMetricsPerBinding metrics and the agent at most MaxBindings
// declared bindings (a per-target placement cap exists on the DP side; this is the agent's own backstop).
const (
	MaxMetricsPerBinding = 256
	MaxBindings          = 64
)

// Set is the live set of declarations, per binding. The DP pushes a binding's
// complete set in a TelemetryDeclarations message (AG-D28) and Replace swaps it
// atomically: a reader sees the previous set or the new one, never a mixture,
// and a message that fails validation changes nothing. The file flag
// (--telemetry-config) only seeds it, for development.
type Set struct {
	mu       sync.RWMutex
	bindings map[string]Binding
	probes   map[string]*msg.HealthProbe
	changed  chan struct{}
}

func NewSet() *Set {
	return &Set{bindings: map[string]Binding{}, probes: map[string]*msg.HealthProbe{}, changed: make(chan struct{}, 1)}
}

// SeedFrom adds a development file's bindings.
func (s *Set) SeedFrom(f *File) {
	if f == nil {
		return
	}
	s.mu.Lock()
	for id, b := range f.Bindings {
		s.bindings[id] = b
	}
	s.mu.Unlock()
	s.notify()
}

// Changed is signalled (coalesced) after every Replace or SeedFrom.
func (s *Set) Changed() <-chan struct{} { return s.changed }

func (s *Set) notify() {
	select {
	case s.changed <- struct{}{}:
	default:
	}
}

// Bindings is a copy of the current per-binding metric sets.
func (s *Set) Bindings() map[string]Binding {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return maps.Clone(s.bindings)
}

// Probe is the declared health probe for a binding, nil when none is declared;
// with none, nothing is probed (OR-101).
func (s *Set) Probe(binding string) *msg.HealthProbe {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if p := s.probes[binding]; p != nil {
		c := *p
		return &c
	}
	return nil
}

// Replace swaps the binding's complete set atomically and returns how many
// metrics were dropped as uncollectable (histogram, unknown source kind): those
// are dropped with a counter, never turned into a zero sample (AG-D28). A
// message that fails validation changes nothing and its error never quotes the
// message beyond names the DP chose.
func (s *Set) Replace(d msg.TelemetryDeclarations) (dropped int, err error) {
	if !safeID.MatchString(d.BindingID) || d.BindingID == "." || d.BindingID == ".." {
		return 0, fmt.Errorf("declarations: binding id must match %s", safeID)
	}
	if len(d.Metrics) > MaxMetricsPerBinding {
		return 0, fmt.Errorf("declarations: more than %d metrics for one binding", MaxMetricsPerBinding)
	}
	interval := d.Interval
	if interval == 0 {
		interval = FixedInterval
	}
	if interval < time.Second {
		return 0, errors.New("declarations: interval must be at least 1s")
	}
	b := Binding{Interval: interval, Timeout: min(interval, FixedInterval)}
	seen := map[string]bool{}
	for i, m := range d.Metrics {
		if m.HTTPJSON == nil || (m.MetricType != msg.MetricCounter && m.MetricType != msg.MetricGauge) {
			dropped++
			continue
		}
		if m.Name == "" || seen[m.Name] {
			return 0, fmt.Errorf("declarations: metrics[%d]: name is empty or duplicate", i)
		}
		if m.Unit == "" {
			return 0, fmt.Errorf("declarations: metrics[%d]: unit is required", i)
		}
		seen[m.Name] = true
		ptr := m.HTTPJSON.Pointer
		on := "degrade"
		if m.HTTPJSON.OnUnavailable == msg.OnUnavailableError {
			on = "error"
		}
		src := Source{Kind: "http_json", Port: int(m.HTTPJSON.Port), Path: m.HTTPJSON.Path, Pointer: &ptr, OnUnavailable: on}
		if err := src.validate(); err != nil {
			return 0, fmt.Errorf("declarations: metrics[%d]: source: %w", i, err)
		}
		if !jsonptr.Valid(ptr) {
			return 0, fmt.Errorf("declarations: metrics[%d]: pointer is not a JSON Pointer", i)
		}
		b.Metrics = append(b.Metrics, Metric{Name: m.Name, MetricType: string(m.MetricType), Unit: m.Unit, Source: src})
	}
	var probe *msg.HealthProbe
	if p := d.HealthProbe; p != nil && p.Kind != msg.ProbeActuatorHealth && p.Kind != msg.ProbeHTTPStatus {
		dropped++ // a kind this agent does not know (a newer DP): nothing is probed, the metrics still apply
	} else if p != nil {
		if p.HostPort < 1 || p.HostPort > 65535 || len(p.Path) == 0 || p.Path[0] != '/' {
			return 0, errors.New("declarations: health probe needs a path starting with / and a host port")
		}
		c := *p
		probe = &c
	}
	s.mu.Lock()
	old, had := s.bindings[d.BindingID]
	if !had && len(s.bindings) >= MaxBindings {
		s.mu.Unlock()
		return 0, fmt.Errorf("declarations: more than %d bindings", MaxBindings)
	}
	s.bindings[d.BindingID] = b
	if probe != nil {
		s.probes[d.BindingID] = probe
	} else {
		delete(s.probes, d.BindingID)
	}
	s.mu.Unlock()
	if !had || !reflect.DeepEqual(old, b) {
		s.notify()
	}
	return dropped, nil
}
