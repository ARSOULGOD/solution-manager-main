package fakegw

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

// logsAgent is an agent whose SessionOpen advertised CAPABILITY_LOGS, with its
// Logs stream open, so the gateway's log requests reach it.
func logsAgent(t *testing.T, s *Server) (*wire.Conn, *wire.LogsStream) {
	t.Helper()
	conn := agentConn(t, s, false)
	open := msg.SessionOpen{Envelope: msg.Envelope{MessageID: "open"}, ProtocolVersion: 1, CapabilitiesSupported: []msg.Capability{msg.CapLogs}}
	if err := conn.Send(open); err != nil {
		t.Fatal(err)
	}
	m, err := conn.Recv()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.(msg.SessionAccept); !ok {
		t.Fatalf("want SessionAccept, got %T", m)
	}
	ls, err := conn.OpenLogs(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ls.Close() })
	return conn, ls
}

// nextInbound returns the next T the gateway sends on Connect, skipping the
// other messages a session carries.
func nextInbound[T msg.Inbound](t *testing.T, conn *wire.Conn) T {
	t.Helper()
	got, errc := make(chan T, 1), make(chan error, 1)
	go func() {
		for {
			m, err := conn.Recv()
			if err != nil {
				errc <- err
				return
			}
			if v, ok := m.(T); ok {
				got <- v
				return
			}
		}
	}()
	select {
	case v := <-got:
		return v
	case err := <-errc:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatalf("no %T within 5s", *new(T))
	}
	panic("unreachable")
}

func waitContains(t *testing.T, out *lockedBuffer, want string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !strings.Contains(out.String(), want) {
		if time.Now().After(deadline) {
			t.Fatalf("want %q in output, got %q", want, out.String())
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// A line that cannot be sent prints why, and nothing is sent.
func TestConsoleRejectsLogLinesItCannotSend(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for _, tc := range []struct{ line, want string }{
		{"logs", "logs needs a placement"},
		{"logs binding-1 many", "limit"},
		{"export", "export needs a placement"},
		{"export binding-1 -3", "seq"},
		// these get as far as a send, so they print "no agent connected"
		{"logs binding-1", "no agent connected"},
		{"export binding-1", "no agent connected"},
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q in output, got %q", tc.line, tc.want, out.String())
		}
	}
}

func TestConsoleLogsPrintsThePageLinesAndGaps(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	conn, ls := logsAgent(t, s)
	out := &lockedBuffer{}
	s.Console(strings.NewReader("logs binding-1 2\n"), out)

	req := nextInbound[msg.LogRequest](t, conn)
	if req.PlacementRef != "binding-1" || req.Page == nil || req.Page.Limit != 2 || req.Export != nil {
		t.Fatalf("want a page of 2 for binding-1, got %+v", req)
	}
	ts := time.Date(2026, 10, 26, 9, 30, 0, 0, time.UTC)
	page := msg.LogPage{RequestID: req.RequestID,
		Lines: []msg.LogLine{
			{TS: ts, Stream: "stdout", Text: []byte("started on port 8080\n")},
			{TS: ts.Add(time.Second), Stream: "stderr", Text: []byte("WARN disk at 91%"), Truncated: true},
		},
		Gaps: []msg.LogGap{{From: ts.Add(-time.Minute), To: ts, Reason: msg.GapDroppedOldest}},
	}
	if err := ls.Send(page); err != nil {
		t.Fatal(err)
	}
	waitContains(t, out, "WARN disk at 91%")
	got := out.String()
	for _, want := range []string{"started on port 8080", "stderr", "truncated", "DROPPED_OLDEST"} {
		if !strings.Contains(got, want) {
			t.Errorf("want %q in output, got %q", want, got)
		}
	}
	if strings.Index(got, "started on port 8080") > strings.Index(got, "WARN disk at 91%") {
		t.Errorf("lines must print oldest first: %q", got)
	}
}

// With no limit typed the page is still bounded: a limit of 0 asks the agent
// for every stored line that fits one message.
func TestConsoleLogsWithoutALimitAsksForABoundedPage(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	conn, ls := logsAgent(t, s)
	out := &lockedBuffer{}
	s.Console(strings.NewReader("logs binding-1\n"), out)

	req := nextInbound[msg.LogRequest](t, conn)
	if req.Page == nil || req.Page.Limit == 0 {
		t.Fatalf("want a bounded page, got %+v", req)
	}
	if err := ls.Send(msg.LogPage{RequestID: req.RequestID, Unavailable: msg.UnavailableNotCollecting}); err != nil {
		t.Fatal(err)
	}
	waitContains(t, out, "NOT_COLLECTING")
}

func TestConsoleExportPrintsEveryChunkUntilTheFinalOne(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	conn, ls := logsAgent(t, s)
	out := &lockedBuffer{}
	s.Console(strings.NewReader("export binding-1 7\n"), out)

	req := nextInbound[msg.LogRequest](t, conn)
	if req.PlacementRef != "binding-1" || req.Export == nil || req.Export.FromSeq != 7 || req.Page != nil {
		t.Fatalf("want an export of binding-1 from seq 7, got %+v", req)
	}
	ts := time.Date(2026, 10, 26, 9, 30, 0, 0, time.UTC)
	for _, c := range []msg.LogChunk{
		{RequestID: req.RequestID, Seq: 8, Lines: []msg.LogLine{{TS: ts, Stream: "stdout", Text: []byte("first exported line")}}},
		{RequestID: req.RequestID, Seq: 9, Final: true,
			Lines: []msg.LogLine{{TS: ts.Add(time.Second), Stream: "stdout", Text: []byte("second exported line")}},
			Gaps:  []msg.LogGap{{From: ts, To: ts.Add(time.Second), Reason: msg.GapContainerUnreadable}}},
	} {
		if err := ls.Send(c); err != nil {
			t.Fatal(err)
		}
	}
	waitContains(t, out, "complete")
	got := out.String()
	for _, want := range []string{"first exported line", "second exported line", "CONTAINER_UNREADABLE", "2 lines"} {
		if !strings.Contains(got, want) {
			t.Errorf("want %q in output, got %q", want, got)
		}
	}
}
