package declare

import (
	"strings"
	"testing"
	"time"
)

const valid = `schema_version: 1
bindings:
  binding-1:
    interval: 15s
    metrics:
      - name: cache.hit_rate
        metricType: gauge
        unit: ratio
        source: {kind: http_json, port: 8081, path: /api/cache/stats, pointer: /hitRate, onUnavailable: degrade}
`

func TestParseAcceptsTheSpecExampleAndDefaultsTheTimeout(t *testing.T) {
	f, err := Parse(strings.NewReader(valid))
	if err != nil {
		t.Fatal(err)
	}
	b := f.Bindings["binding-1"]
	if b.Interval != 15*time.Second || b.Timeout != 5*time.Second {
		t.Fatalf("interval %v timeout %v", b.Interval, b.Timeout)
	}
	m := b.Metrics[0]
	if m.Name != "cache.hit_rate" || m.MetricType != "gauge" || m.Unit != "ratio" || m.Source.Port != 8081 ||
		m.Source.Path != "/api/cache/stats" || *m.Source.Pointer != "/hitRate" || m.Source.OnUnavailable != "degrade" {
		t.Fatalf("%+v", m)
	}
}

func TestParseRefuses(t *testing.T) {
	for _, tc := range []struct{ name, from, to, want string }{
		{"schema version", "schema_version: 1", "schema_version: 2", "schema_version 2"},
		{"unsafe binding id", "binding-1:", "binding/1:", "must match"},
		{"interval below 1s", "interval: 15s", "interval: 500ms", "at least 1s"},
		{"timeout above interval", "interval: 15s", "interval: 15s\n    timeout: 20s", "timeout must be between 0 and interval"},
		{"histogram", "metricType: gauge", "metricType: histogram", "histogram is not in this slice"},
		{"unknown metric type", "metricType: gauge", "metricType: summary", "counter or gauge"},
		{"missing unit", "        unit: ratio\n", "", "unit is required"},
		{"when", "unit: ratio", "unit: ratio\n        when: \"config.provider == 'caffeine'\"", "when is not in this slice"},
		{"another schema kind", "kind: http_json", "kind: prometheus", "kind prometheus is not in this slice"},
		{"not a schema kind", "kind: http_json", "kind: graphite", "not a schema source kind"},
		{"port", "port: 8081", "port: 70000", "out of range"},
		{"path", "path: /api/cache/stats", "path: api/cache/stats", "must start with /"},
		{"pointer missing", "pointer: /hitRate, ", "", "pointer is required"},
		{"pointer invalid", "pointer: /hitRate", "pointer: hitRate", "not a JSON Pointer"},
		{"onUnavailable", "onUnavailable: degrade", "onUnavailable: zero", "degrade or error"},
		{"unknown key", "unit: ratio", "unit: ratio\n        units: ratio", "field units not found"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if !strings.Contains(valid, tc.from) {
				t.Fatalf("fixture lacks %q", tc.from)
			}
			_, err := Parse(strings.NewReader(strings.Replace(valid, tc.from, tc.to, 1)))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("want error containing %q, got %v", tc.want, err)
			}
		})
	}
}

func TestParseRefusesADuplicateNameInOneBinding(t *testing.T) {
	dup := valid + "      - name: cache.hit_rate\n        metricType: gauge\n        unit: ratio\n" +
		"        source: {kind: http_json, port: 8081, path: /x, pointer: /y, onUnavailable: degrade}\n"
	if _, err := Parse(strings.NewReader(dup)); err == nil || !strings.Contains(err.Error(), `duplicate name "cache.hit_rate"`) {
		t.Fatalf("got %v", err)
	}
}

func TestErrorsNameTheBindingAndIndex(t *testing.T) {
	_, err := Parse(strings.NewReader(strings.Replace(valid, "port: 8081", "port: 0", 1)))
	if err == nil || !strings.Contains(err.Error(), `binding "binding-1" metrics[0]`) {
		t.Fatalf("got %v", err)
	}
}
