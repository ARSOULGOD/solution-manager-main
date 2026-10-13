// Package tmetrics holds the telemetry pipeline's Prometheus metrics, shared
// by the buffer's callbacks, the collectors and the sender. Every helper is a
// no-op on a nil *Metrics, so unit tests may pass none.
package tmetrics

import (
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

type Metrics struct {
	DroppedBatches  *prometheus.CounterVec
	DroppedSamples  *prometheus.CounterVec
	Unavailable     *prometheus.CounterVec
	ScrapeErrors    *prometheus.CounterVec
	CollectorPanics *prometheus.CounterVec
	WriteErrors     prometheus.Counter
	BatchesSent     prometheus.Counter
	BatchesAcked    prometheus.Counter
	Rejects         *prometheus.CounterVec
	Credits         prometheus.Gauge
	all             []prometheus.Collector
}

func counterVec(name, help string, labels ...string) *prometheus.CounterVec {
	return prometheus.NewCounterVec(prometheus.CounterOpts{Name: name, Help: help}, labels)
}

func New() *Metrics {
	m := &Metrics{
		DroppedBatches:  counterVec("sm_agent_telemetry_dropped_batches_total", "Batches dropped from the buffer, by reason (AG-D9).", "reason"),
		DroppedSamples:  counterVec("sm_agent_telemetry_dropped_samples_total", "Samples in dropped batches, by reason (AG-D9).", "reason"),
		Unavailable:     counterVec("sm_agent_telemetry_unavailable_total", "Declared metrics that yielded no number; no sample was emitted.", "binding", "metric"),
		ScrapeErrors:    counterVec("sm_agent_telemetry_scrape_errors_total", "Failed endpoint fetches, by reason.", "binding", "reason"),
		CollectorPanics: counterVec("sm_agent_telemetry_collector_panics_total", "Collector ticks that panicked and were recovered.", "binding"),
		WriteErrors:     prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_telemetry_buffer_write_errors_total", Help: "Buffer writes that failed; the batch was dropped (AG-D25)."}),
		BatchesSent:     prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_telemetry_batches_sent_total", Help: "TelemetryBatch messages sent."}),
		BatchesAcked:    prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_telemetry_batches_acked_total", Help: "In-flight batches released by a TelemetryAck."}),
		Rejects:         counterVec("sm_agent_telemetry_rejects_total", "TelemetryReject messages received, by code.", "code"),
		Credits:         prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_telemetry_credits", Help: "Telemetry credits held."}),
	}
	m.all = []prometheus.Collector{m.DroppedBatches, m.DroppedSamples, m.Unavailable, m.ScrapeErrors, m.CollectorPanics,
		m.WriteErrors, m.BatchesSent, m.BatchesAcked, m.Rejects, m.Credits}
	return m
}

func (m *Metrics) Collectors() []prometheus.Collector { return m.all }

// BufferGauges exports the buffer's state and both AG-D9 caps — the decision
// requires both caps to be reported, not just enforced.
func (m *Metrics) BufferGauges(stats func() (bytes int64, batches int, oldest time.Time), limitBytes int64, limitAge time.Duration, now func() time.Time) {
	gauge := func(name, help string, f func() float64) prometheus.Collector {
		return prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: name, Help: help}, f)
	}
	m.all = append(m.all,
		gauge("sm_agent_telemetry_buffer_bytes", "Bytes held in the telemetry buffer.", func() float64 { b, _, _ := stats(); return float64(b) }),
		gauge("sm_agent_telemetry_buffer_batches", "Batches held in the telemetry buffer.", func() float64 { _, n, _ := stats(); return float64(n) }),
		gauge("sm_agent_telemetry_buffer_oldest_age_seconds", "Age of the oldest buffered batch.", func() float64 {
			_, _, o := stats()
			if o.IsZero() {
				return 0
			}
			return now().Sub(o).Seconds()
		}),
		gauge("sm_agent_telemetry_buffer_limit_bytes", "AG-D9 byte cap.", func() float64 { return float64(limitBytes) }),
		gauge("sm_agent_telemetry_buffer_limit_age_seconds", "AG-D9 time horizon.", func() float64 { return limitAge.Seconds() }),
	)
}

func (m *Metrics) Drop(reason string, samples int) {
	if m == nil {
		return
	}
	m.DroppedBatches.WithLabelValues(reason).Inc()
	m.DroppedSamples.WithLabelValues(reason).Add(float64(samples))
}

func (m *Metrics) Unavail(binding, metric string) {
	if m != nil {
		m.Unavailable.WithLabelValues(binding, metric).Inc()
	}
}

func (m *Metrics) ScrapeError(binding, reason string) {
	if m != nil {
		m.ScrapeErrors.WithLabelValues(binding, reason).Inc()
	}
}

func (m *Metrics) Panic(binding string) {
	if m != nil {
		m.CollectorPanics.WithLabelValues(binding).Inc()
	}
}

func (m *Metrics) WriteError() {
	if m != nil {
		m.WriteErrors.Inc()
	}
}

func (m *Metrics) Sent() {
	if m != nil {
		m.BatchesSent.Inc()
	}
}

func (m *Metrics) AckedBatches(n int) {
	if m != nil {
		m.BatchesAcked.Add(float64(n))
	}
}

func (m *Metrics) Reject(code string) {
	if m != nil {
		m.Rejects.WithLabelValues(code).Inc()
	}
}

func (m *Metrics) SetCredits(n uint32) {
	if m != nil {
		m.Credits.Set(float64(n))
	}
}
