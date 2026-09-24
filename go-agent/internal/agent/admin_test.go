package agent

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/reports"
	"github.com/solace/solution-manager/go-agent/internal/testca"
)

func TestAdminHealthAndMetrics(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	m := NewMetrics()
	l.d.Observe = m.Observe(l)
	lis, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := lis.Addr().String()
	lis.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = ServeAdmin(ctx, addr, l, m, slog.New(slog.NewTextHandler(io.Discard, nil))) }()
	go func() { _ = l.Run(ctx) }()
	c := w.conn(0)
	expect[msg.SessionOpen](t, c)

	time.Sleep(50 * time.Millisecond)
	resp, err := http.Get("http://" + addr + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	var h health
	_ = json.NewDecoder(resp.Body).Decode(&h)
	if h.SessionPhase != string(PhaseOpening) {
		t.Fatalf("%+v", h)
	}
	resp2, err := http.Get("http://" + addr + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp2.Body)
	if !strings.Contains(string(body), "sm_agent_events_total") {
		t.Fatal("metrics missing")
	}
}

// TestMetricsWireJournalReportsAndCommandCounters is I3: three gauges/counter
// (sm_agent_journal_open_records, sm_agent_reports_pending,
// sm_agent_commands_total) were registered but never written. This drives
// Observe directly against a Loop whose State already has one open journal
// record and one pending report item, and a CommandAck effect closing that
// same record, and asserts all three actually moved off zero.
func TestMetricsWireJournalReportsAndCommandCounters(t *testing.T) {
	j := commands.Journal{Records: map[string]commands.Record{}, Watermark: map[string]uint64{}}
	j.Put(commands.Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c-open", Operation: msg.OpApply, Step: msg.StepPersisted})
	initial := State{
		Commands: commands.State{Journal: j},
		Reports:  reports.State{Pending: reports.Pending{Items: []msg.ActualStateReport{{ItemID: "i1"}}}},
	}
	l := New(Deps{Now: time.Now}, initial)
	m := NewMetrics()
	obs := m.Observe(l)

	ack := msg.CommandAck{CommandID: "c-open", Generation: 1, Outcome: msg.OutcomeApplied}
	obs(reactor.Resumed{At: time.Now()}, []reactor.Effect{reactor.Send{Msg: ack}})

	if got := testutil.ToFloat64(m.journalOpen); got != 1 {
		t.Fatalf("sm_agent_journal_open_records = %v, want 1 (one open record)", got)
	}
	if got := testutil.ToFloat64(m.reportsPend); got != 1 {
		t.Fatalf("sm_agent_reports_pending = %v, want 1 (one pending item)", got)
	}
	if got := testutil.ToFloat64(m.commands.WithLabelValues(string(msg.OpApply), string(msg.OutcomeApplied))); got != 1 {
		t.Fatalf("sm_agent_commands_total{op=apply,outcome=APPLIED} = %v, want 1", got)
	}
}
