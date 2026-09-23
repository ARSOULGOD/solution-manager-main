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

	"github.com/solace/solution-manager/go-agent/internal/msg"
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
