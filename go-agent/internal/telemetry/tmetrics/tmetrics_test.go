package tmetrics

import (
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestHelpersCountWhatTheyName(t *testing.T) {
	m := New()
	m.BufferGauges(func() (int64, int, time.Time) { return 1234, 3, time.Unix(100, 0) }, 64<<20, 24*time.Hour, func() time.Time { return time.Unix(160, 0) })
	reg := prometheus.NewRegistry()
	reg.MustRegister(m.Collectors()...) // no duplicate names

	m.Drop("EXPIRED", 3)
	m.Unavail("binding-1", "cache.hit_rate")
	m.ScrapeError("binding-1", "status")
	m.Panic("binding-1")
	m.WriteError()
	m.Sent()
	m.AckedBatches(2)
	m.Reject("UNKNOWN_REPLICA")
	m.SetCredits(4)

	for name, c := range map[string]prometheus.Collector{
		"dropped batches": m.DroppedBatches.WithLabelValues("EXPIRED"),
		"unavailable":     m.Unavailable.WithLabelValues("binding-1", "cache.hit_rate"),
		"scrape errors":   m.ScrapeErrors.WithLabelValues("binding-1", "status"),
		"panics":          m.CollectorPanics.WithLabelValues("binding-1"),
		"write errors":    m.WriteErrors,
		"sent":            m.BatchesSent,
		"rejects":         m.Rejects.WithLabelValues("UNKNOWN_REPLICA"),
	} {
		if v := testutil.ToFloat64(c); v != 1 {
			t.Errorf("%s = %v, want 1", name, v)
		}
	}
	if v := testutil.ToFloat64(m.DroppedSamples.WithLabelValues("EXPIRED")); v != 3 {
		t.Errorf("dropped samples = %v, want 3", v)
	}
	if v := testutil.ToFloat64(m.BatchesAcked); v != 2 {
		t.Errorf("acked = %v, want 2", v)
	}
	if v := testutil.ToFloat64(m.Credits); v != 4 {
		t.Errorf("credits = %v, want 4", v)
	}
	if n, err := testutil.GatherAndCount(reg, "sm_agent_telemetry_buffer_oldest_age_seconds"); err != nil || n != 1 {
		t.Errorf("age gauge: %d %v", n, err)
	}
}

func TestANilMetricsIsANoOp(t *testing.T) {
	var m *Metrics
	m.Drop("EXPIRED", 1)
	m.Unavail("b", "m")
	m.ScrapeError("b", "r")
	m.Panic("b")
	m.WriteError()
	m.Sent()
	m.AckedBatches(1)
	m.Reject("X")
	m.SetCredits(1)
}
