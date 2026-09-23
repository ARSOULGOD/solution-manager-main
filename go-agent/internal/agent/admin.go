package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/solace/solution-manager/go-agent/internal/reactor"
)

// Metrics hang off the loop's Observe hook — one increment per event and per
// effect — never inside reducers (spec §8.3).
type Metrics struct {
	reg         *prometheus.Registry
	events      *prometheus.CounterVec
	effects     *prometheus.CounterVec
	reconnects  prometheus.Counter
	leaseState  *prometheus.GaugeVec
	leaseRemain prometheus.Gauge
	clockOffset prometheus.Gauge
	halted      *prometheus.GaugeVec
	journalOpen prometheus.Gauge
	reportsPend prometheus.Gauge
	commands    *prometheus.CounterVec
}

func NewMetrics() *Metrics {
	m := &Metrics{reg: prometheus.NewRegistry()}
	m.events = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "sm_agent_events_total"}, []string{"kind"})
	m.effects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "sm_agent_effects_total"}, []string{"kind"})
	m.reconnects = prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_reconnects_total"})
	m.leaseState = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "sm_agent_lease_state"}, []string{"state"})
	m.leaseRemain = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_lease_remaining_seconds"})
	m.clockOffset = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_clock_offset_ms"})
	m.halted = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "sm_agent_halted"}, []string{"reason"})
	m.journalOpen = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_journal_open_records"})
	m.reportsPend = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_reports_pending"})
	m.commands = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "sm_agent_commands_total"}, []string{"op", "outcome"})
	m.reg.MustRegister(m.events, m.effects, m.reconnects, m.leaseState, m.leaseRemain, m.clockOffset, m.halted, m.journalOpen, m.reportsPend, m.commands)
	return m
}

// kindName is the Go type name without package: "reactor.WireIn" → "WireIn".
func kindName(v any) string {
	s := fmt.Sprintf("%T", v)
	if i := strings.LastIndex(s, "."); i >= 0 {
		return s[i+1:]
	}
	return s
}

// Observe is the hook to pass as Deps.Observe.
func (m *Metrics) Observe(l *Loop) func(reactor.Event, []reactor.Effect) {
	return func(ev reactor.Event, effs []reactor.Effect) {
		m.events.WithLabelValues(kindName(ev)).Inc()
		for _, e := range effs {
			m.effects.WithLabelValues(kindName(e)).Inc()
			if d, ok := e.(reactor.Dial); ok && d.Endpoint != "" {
				m.reconnects.Inc()
			}
		}
		st := l.Snapshot()
		m.leaseState.Reset()
		m.leaseState.WithLabelValues(string(st.Authority.Phase)).Set(1)
		m.leaseRemain.Set(st.Authority.Remaining(time.Now().Add(l.Clock().Offset())).Seconds())
		m.clockOffset.Set(float64(l.Clock().Offset().Milliseconds()))
		m.halted.Reset()
		if h := l.Halted(); h != "" {
			m.halted.WithLabelValues(string(h)).Set(1)
		}
	}
}

type health struct {
	SessionPhase   string    `json:"session_phase"`
	AuthorityPhase string    `json:"authority_phase"`
	ExpiresAt      time.Time `json:"expires_at,omitempty"`
	ClockOffsetMs  int64     `json:"clock_offset_ms"`
	HaltedReason   string    `json:"halted_reason,omitempty"`
	At             time.Time `json:"at"`
}

func ServeAdmin(ctx context.Context, addr string, l *Loop, m *Metrics, log *slog.Logger) error {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.HandlerFor(m.reg, promhttp.HandlerOpts{}))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		st := l.Snapshot()
		h := health{SessionPhase: string(st.Session.Phase), AuthorityPhase: string(st.Authority.Phase),
			ClockOffsetMs: l.Clock().Offset().Milliseconds(), HaltedReason: string(l.Halted()), At: time.Now().UTC()}
		if st.Authority.Grant != nil {
			h.ExpiresAt = st.Authority.Grant.ExpiresAt
		}
		w.Header().Set("Content-Type", "application/json")
		if h.HaltedReason != "" {
			w.WriteHeader(http.StatusServiceUnavailable)
		}
		_ = json.NewEncoder(w).Encode(h)
	})
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		_ = srv.Close()
	}()
	log.Info("admin listening", "addr", lis.Addr().String())
	err = srv.Serve(lis)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
