package fakegw

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func telemetryServer(t *testing.T, mutate func(*Scenario)) (*Server, *lockedBuffer) {
	t.Helper()
	sc := DefaultScenario()
	if mutate != nil {
		mutate(sc)
	}
	out := &lockedBuffer{}
	s, err := Start(Config{Listen: "127.0.0.1:0", Tokens: []string{"tok"}, Scenario: sc, Out: out})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Stop)
	return s, out
}

// agentConn enrolls a fresh identity and dials; with open, it also completes
// SessionOpen → SessionAccept so the gateway holds a live session.
func agentConn(t *testing.T, s *Server, open bool) *wire.Conn {
	t.Helper()
	ctx := context.Background()
	id, err := identity.Load(store.MustOpen(t.TempDir()))
	if err != nil {
		t.Fatal(err)
	}
	csr, err := id.CSR()
	if err != nil {
		t.Fatal(err)
	}
	c := wire.New(id, s.CAPEM())
	resp, err := c.Enroll(ctx, s.Endpoint(), msg.EnrollRequest{Token: "tok", CSRDER: csr, AgentVersion: "t"})
	if err != nil {
		t.Fatal(err)
	}
	if err := id.StoreEnrollment(*resp); err != nil {
		t.Fatal(err)
	}
	conn, err := c.Dial(ctx, s.Endpoint())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if open {
		if err := conn.Send(msg.SessionOpen{Envelope: msg.Envelope{MessageID: "open"}, ProtocolVersion: 1}); err != nil {
			t.Fatal(err)
		}
		m, err := conn.Recv()
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := m.(msg.SessionAccept); !ok {
			t.Fatalf("want SessionAccept, got %T", m)
		}
	}
	return conn
}

func batch(seq uint64) msg.TelemetryBatch {
	return msg.TelemetryBatch{Envelope: msg.Envelope{MessageID: fmt.Sprintf("b%d", seq)}, StreamID: "binding-1", BatchSeq: seq,
		Samples: []msg.Sample{{SeriesKey: "cache.hit_rate", Type: msg.MetricGauge, Value: 0.93}}}
}

func openTelemetry(t *testing.T, conn *wire.Conn) *wire.TelemetryStream {
	t.Helper()
	ts, err := conn.OpenTelemetry(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ts.Close() })
	return ts
}

func recvAck(t *testing.T, ts *wire.TelemetryStream) msg.TelemetryAck {
	t.Helper()
	m, err := ts.Recv()
	if err != nil {
		t.Fatal(err)
	}
	a, ok := m.(msg.TelemetryAck)
	if !ok {
		t.Fatalf("want TelemetryAck, got %T %+v", m, m)
	}
	return a
}

func sendBatch(t *testing.T, ts *wire.TelemetryStream, seq uint64) {
	t.Helper()
	if err := ts.Send(batch(seq)); err != nil {
		t.Fatal(err)
	}
}

func TestTelemetryAcksCommitsAndDedupesReplays(t *testing.T) {
	s, out := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, true))

	sendBatch(t, ts, 1)
	if a := recvAck(t, ts); a.StreamID != "binding-1" || a.HighWatermarkSeq != 1 || a.CreditsGranted != 1 {
		t.Fatalf("first ack %+v", a)
	}
	sendBatch(t, ts, 1) // a replay: acked at the watermark, not recorded again
	if a := recvAck(t, ts); a.HighWatermarkSeq != 1 {
		t.Fatalf("replay ack %+v", a)
	}
	sendBatch(t, ts, 2)
	if a := recvAck(t, ts); a.HighWatermarkSeq != 2 {
		t.Fatalf("second ack %+v", a)
	}
	for _, want := range []string{
		"telemetry binding-1 seq=1 cache.hit_rate=0.93 -> acked",
		"telemetry binding-1 seq=1 duplicate",
		"telemetry binding-1 seq=2 cache.hit_rate=0.93 -> acked",
	} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("want %q in output:\n%s", want, out.String())
		}
	}
}

func TestTelemetryReopenAcksTheWatermarkFirst(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	conn := agentConn(t, s, true)
	ts := openTelemetry(t, conn)
	sendBatch(t, ts, 1)
	recvAck(t, ts)
	sendBatch(t, ts, 2)
	recvAck(t, ts)
	_ = ts.Close()

	ts2 := openTelemetry(t, conn) // gateway §4.13 (b): the durable watermark comes first
	if a := recvAck(t, ts2); a.StreamID != "binding-1" || a.HighWatermarkSeq != 2 || a.CreditsGranted != 0 {
		t.Fatalf("watermark ack on reopen %+v", a)
	}
}

func TestTelemetryRefusedWithoutAnAcceptedSession(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, false))
	_, err := ts.Recv()
	var pe *msg.ProtocolError
	if !errors.As(err, &pe) || pe.Code != msg.ErrUnauthenticatedIdentity {
		t.Fatalf("want UNAUTHENTICATED_IDENTITY, got %v", err)
	}
}

func TestTelemetryPauseWithholdsCreditsAndResumeGrants(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, true))
	s.PauseTelemetry()
	sendBatch(t, ts, 1)
	if a := recvAck(t, ts); a.CreditsGranted != 0 {
		t.Fatalf("paused ack granted %d credits", a.CreditsGranted)
	}
	s.ResumeTelemetry(4)
	m, err := ts.Recv()
	if c, ok := m.(msg.TelemetryCredit); err != nil || !ok || c.CreditsGranted != 4 {
		t.Fatalf("want TelemetryCredit{4}, got %T %+v %v", m, m, err)
	}
	sendBatch(t, ts, 2)
	if a := recvAck(t, ts); a.CreditsGranted != 1 {
		t.Fatalf("resumed ack granted %d credits", a.CreditsGranted)
	}
}

func TestTelemetryRejectNextRejectsOnceWithoutCommitting(t *testing.T) {
	s, out := telemetryServer(t, func(sc *Scenario) { sc.Policy.Telemetry.RejectNext = "UNKNOWN_REPLICA" })
	ts := openTelemetry(t, agentConn(t, s, true))
	sendBatch(t, ts, 1)
	m, err := ts.Recv()
	if r, ok := m.(msg.TelemetryReject); err != nil || !ok || r.Code != msg.ErrUnknownReplica || r.StreamID != "binding-1" {
		t.Fatalf("want TelemetryReject{UNKNOWN_REPLICA}, got %T %+v %v", m, m, err)
	}
	sendBatch(t, ts, 1) // not committed by the reject, so this is new, not a duplicate
	if a := recvAck(t, ts); a.HighWatermarkSeq != 1 {
		t.Fatalf("ack after reject %+v", a)
	}
	if strings.Contains(out.String(), "duplicate") {
		t.Fatalf("a rejected batch must not advance the watermark:\n%s", out.String())
	}
}
