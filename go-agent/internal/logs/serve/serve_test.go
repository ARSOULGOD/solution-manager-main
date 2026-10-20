package serve

import (
	"bytes"
	"context"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"google.golang.org/protobuf/proto"

	"github.com/solace/solution-manager/go-agent/internal/logs/ring"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
)

var t0 = time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)

// sink is a LogsSender the test reads from.
type sink struct{ ch chan msg.LogsOutbound }

func newSink() *sink { return &sink{ch: make(chan msg.LogsOutbound, 1024)} }

func (s *sink) Send(m msg.LogsOutbound) error { s.ch <- m; return nil }

func (s *sink) next(t *testing.T) msg.LogsOutbound {
	t.Helper()
	select {
	case m := <-s.ch:
		return m
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for a Logs-stream message")
	}
	panic("unreachable")
}

func (s *sink) none(t *testing.T) {
	t.Helper()
	select {
	case m := <-s.ch:
		t.Fatalf("unexpected message %T %+v", m, m)
	case <-time.After(100 * time.Millisecond):
	}
}

func page(t *testing.T, s *sink) msg.LogPage {
	t.Helper()
	m := s.next(t)
	p, ok := m.(msg.LogPage)
	if !ok {
		t.Fatalf("want LogPage, got %T", m)
	}
	return p
}

func chunk(t *testing.T, s *sink) msg.LogChunk {
	t.Helper()
	m := s.next(t)
	c, ok := m.(msg.LogChunk)
	if !ok {
		t.Fatalf("want LogChunk, got %T", m)
	}
	return c
}

// wireSize is the encoded size of the message as the gateway receives it.
func wireSize(t *testing.T, m msg.LogsOutbound) int {
	t.Helper()
	p, err := codec.EncodeLogUp(m)
	if err != nil {
		t.Fatal(err)
	}
	return proto.Size(p)
}

func openRing(t *testing.T, cap uint64) *ring.Ring {
	t.Helper()
	s, err := store.Open(filepath.Join(t.TempDir(), "d"))
	if err != nil {
		t.Fatal(err)
	}
	r, err := ring.Open(s, "p1", cap)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return r
}

func fill(t *testing.T, r *ring.Ring, n, size int) {
	t.Helper()
	for i := range n {
		text := fmt.Sprintf("line-%04d ", i)
		text += string(bytes.Repeat([]byte("a"), max(size-len(text), 0)))
		if err := r.Append(msg.LogLine{TS: t0.Add(time.Duration(i) * time.Millisecond), Stream: "stdout", Text: []byte(text)}); err != nil {
			t.Fatal(err)
		}
	}
}

func newServer(t *testing.T, r *ring.Ring, maxMsg, maxBatch uint64, opts ...Option) *Server {
	t.Helper()
	srv := New(
		func(p string) (*ring.Ring, bool) { return r, p == "p1" && r != nil },
		func(string) bool { return true },
		func() (uint64, uint64) { return maxMsg, maxBatch },
		opts...)
	t.Cleanup(func() { srv.CancelAll(); srv.Wait() })
	return srv
}

func pageReq(id string, limit uint32) msg.LogRequest {
	return msg.LogRequest{RequestID: id, PlacementRef: "p1", Page: &msg.LogPageQuery{Limit: limit}}
}

func exportReq(id string, from uint64) msg.LogRequest {
	return msg.LogRequest{RequestID: id, PlacementRef: "p1", Export: &msg.LogExportQuery{FromSeq: from}}
}

// drain grants credits until the final chunk and returns every chunk.
func drain(t *testing.T, srv *Server, out *sink, id string) []msg.LogChunk {
	t.Helper()
	var cs []msg.LogChunk
	for {
		srv.Credit(id, 1)
		c := chunk(t, out)
		if wireSize(t, c) > 1<<20 {
			t.Fatal("chunk over budget")
		}
		cs = append(cs, c)
		if c.Final {
			return cs
		}
		if len(cs) > 10000 {
			t.Fatal("export never ends")
		}
	}
}

func TestPageRequestAnswersOneLogPageWithinMaxMessageBytes(t *testing.T) {
	r := openRing(t, 1<<20)
	fill(t, r, 200, 100)
	srv := newServer(t, r, 4096, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), pageReq("r1", 1000), out)
	p := page(t, out)
	if p.RequestID != "r1" || p.Unavailable != msg.UnavailableNone || p.MessageID == "" {
		t.Fatalf("%+v", p.Envelope)
	}
	if n := wireSize(t, p); n > 4096 {
		t.Fatalf("page is %d bytes, over max_message_bytes 4096", n)
	}
	if len(p.Lines) < 2 || len(p.Lines) >= 200 {
		t.Fatalf("got %d lines; the budget should cut the page, not empty it", len(p.Lines))
	}
	if !bytes.HasPrefix(p.Lines[len(p.Lines)-1].Text, []byte("line-0199")) {
		t.Fatalf("the page must hold the newest lines, last is %q", p.Lines[len(p.Lines)-1].Text[:9])
	}
	out.none(t) // exactly one page
}

func TestExportEmitsChunksOnlyWithCredits(t *testing.T) {
	r := openRing(t, 1<<20)
	fill(t, r, 50, 100)
	srv := newServer(t, r, 1<<20, 1024)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	out.none(t) // no credit, no chunk

	srv.Credit("e1", 1)
	first := chunk(t, out)
	if n := wireSize(t, first); n > 1024 {
		t.Fatalf("chunk is %d bytes, over max_batch_bytes 1024", n)
	}
	if first.Final || len(first.Lines) == 0 {
		t.Fatalf("%+v", first)
	}
	out.none(t) // one credit, one chunk

	lines := len(first.Lines)
	all := append([]msg.LogChunk{first}, drain(t, srv, out, "e1")...)
	for _, c := range all[1:] {
		lines += len(c.Lines)
		if wireSize(t, c) > 1024 {
			t.Fatal("chunk over max_batch_bytes")
		}
	}
	if lines != 50 {
		t.Fatalf("exported %d lines, want 50", lines)
	}
	want := 0
	for _, c := range all {
		for _, l := range c.Lines {
			if !bytes.HasPrefix(l.Text, fmt.Appendf(nil, "line-%04d", want)) {
				t.Fatalf("out of order or repeated: %q at %d", l.Text[:9], want)
			}
			want++
		}
	}
	if srv.Inflight() != 0 {
		t.Fatal("a finished export keeps its state")
	}
}

func TestExportResumesFromFromSeq(t *testing.T) {
	r := openRing(t, 1<<20)
	fill(t, r, 40, 100)
	srv := newServer(t, r, 1<<20, 1024)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	first := chunk(t, out)
	srv.Cancel("e1")

	// Seq is the resume point: the from_seq that continues right after this chunk.
	srv.Handle(context.Background(), exportReq("e2", first.Seq), out)
	rest := drain(t, srv, out, "e2")
	last := first.Lines[len(first.Lines)-1].Text[:9]
	got := rest[0].Lines[0].Text[:9]
	var n int
	fmt.Sscanf(string(last), "line-%04d", &n)
	if string(got) != fmt.Sprintf("line-%04d", n+1) {
		t.Fatalf("resumed at %q after %q", got, last)
	}
	total := len(first.Lines)
	for _, c := range rest {
		total += len(c.Lines)
	}
	if total != 40 {
		t.Fatalf("first chunk plus resume carried %d lines, want 40 with no repeat", total)
	}
}

func TestCancelStopsExportAndFreesState(t *testing.T) {
	r := openRing(t, 1<<20)
	fill(t, r, 50, 100)
	srv := newServer(t, r, 1<<20, 1024)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	chunk(t, out)
	srv.Cancel("e1")
	if srv.Inflight() != 0 {
		t.Fatal("Cancel must free the request's state at once")
	}
	srv.Credit("e1", 5)
	out.none(t)
	if srv.Ignored() != 1 {
		t.Fatalf("a credit for a cancelled request is an unknown id: ignored=%d", srv.Ignored())
	}
}

func TestRequestForPlacementNotInLeaseIsRefusedPLACEMENT_NOT_IN_LEASE(t *testing.T) {
	var mu sync.Mutex
	read := 0
	srv := New(
		func(string) (*ring.Ring, bool) { mu.Lock(); read++; mu.Unlock(); return nil, false },
		func(p string) bool { return p == "leased" },
		func() (uint64, uint64) { return 1 << 20, 1 << 20 })
	t.Cleanup(srv.Wait)
	out := newSink()
	for _, req := range []msg.LogRequest{pageReq("r1", 10), exportReq("r2", 0)} {
		req.PlacementRef = "someone-else"
		srv.Handle(context.Background(), req, out)
		p := page(t, out)
		if p.RequestID != req.RequestID || p.Unavailable != msg.UnavailablePlacementNotLease || len(p.Lines) != 0 {
			t.Fatalf("%+v", p)
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if read != 0 {
		t.Fatal("nothing may be read for a placement outside the lease")
	}
}

func TestNotCollectingPlacementAnswersNOT_COLLECTING(t *testing.T) {
	off := openRing(t, 0) // a fresh ring with no cap: collection off
	srv := New(
		func(p string) (*ring.Ring, bool) {
			if p == "off" {
				return off, true
			}
			return nil, false
		},
		func(string) bool { return true },
		func() (uint64, uint64) { return 1 << 20, 1 << 20 })
	t.Cleanup(srv.Wait)
	out := newSink()
	for _, p := range []string{"off", "no-ring"} {
		req := pageReq("r-"+p, 10)
		req.PlacementRef = p
		srv.Handle(context.Background(), req, out)
		if got := page(t, out); got.Unavailable != msg.UnavailableNotCollecting {
			t.Fatalf("%s: %+v", p, got)
		}
	}
}

func TestCapReducedMidExportEndsWithCAP_REDUCEDGapAndNoPanic(t *testing.T) {
	t.Run("smaller", func(t *testing.T) {
		r := openRing(t, 256<<10)
		fill(t, r, 300, 200)
		srv := newServer(t, r, 1<<20, 1024)
		out := newSink()
		srv.Handle(context.Background(), exportReq("e1", 0), out)
		srv.Credit("e1", 1)
		chunk(t, out)
		if err := r.SetCap(70 << 10); err != nil {
			t.Fatal(err)
		}
		rest := drain(t, srv, out, "e1")
		reduced := false
		for _, c := range rest {
			for _, g := range c.Gaps {
				reduced = reduced || g.Reason == msg.GapCapReduced
			}
		}
		if !reduced || !rest[len(rest)-1].Final {
			t.Fatalf("want a CAP_REDUCED gap and a final chunk; reduced=%v chunks=%d", reduced, len(rest))
		}
	})
	t.Run("zero", func(t *testing.T) {
		r := openRing(t, 1<<20)
		fill(t, r, 50, 100)
		srv := newServer(t, r, 1<<20, 1024)
		out := newSink()
		srv.Handle(context.Background(), exportReq("e1", 0), out)
		srv.Credit("e1", 1)
		chunk(t, out)
		if err := r.SetCap(0); err != nil {
			t.Fatal(err)
		}
		srv.Credit("e1", 1)
		c := chunk(t, out)
		if !c.Final || len(c.Gaps) != 1 || c.Gaps[0].Reason != msg.GapCapReduced || len(c.Lines) != 0 {
			t.Fatalf("collection turned off mid-export must end it with a CAP_REDUCED gap: %+v", c)
		}
		out.none(t)
	})
}

func TestOversizeLineIsTruncatedInPageAndChunk(t *testing.T) {
	r := openRing(t, 1<<20)
	big := bytes.Repeat([]byte("é"), 10000) // 20000 bytes of two-byte runes
	if err := r.Append(msg.LogLine{TS: t0, Stream: "stderr", Text: big}); err != nil {
		t.Fatal(err)
	}
	srv := newServer(t, r, 2048, 4096)
	out := newSink()

	srv.Handle(context.Background(), pageReq("r1", 10), out)
	p := page(t, out)
	if n := wireSize(t, p); n > 2048 {
		t.Fatalf("page %d bytes", n)
	}
	if len(p.Lines) != 1 || !p.Lines[0].Truncated || !utf8.Valid(p.Lines[0].Text) || len(p.Lines[0].Text) < 1000 {
		t.Fatalf("the oversize line must arrive truncated, marked, valid UTF-8: %d lines", len(p.Lines))
	}

	srv.Handle(context.Background(), exportReq("e1", 0), out)
	cs := drain(t, srv, out, "e1")
	c := cs[0]
	if n := wireSize(t, c); n > 4096 {
		t.Fatalf("chunk %d bytes", n)
	}
	if len(c.Lines) != 1 || !c.Lines[0].Truncated || !utf8.Valid(c.Lines[0].Text) || !c.Final {
		t.Fatalf("%d chunks; first has %d lines, final=%v", len(cs), len(c.Lines), c.Final)
	}
}

func TestDuplicateAndUnknownRequestIdsAreIgnoredAndCounted(t *testing.T) {
	r := openRing(t, 1<<20)
	fill(t, r, 10, 100)
	srv := newServer(t, r, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out) // in flight: no credits
	srv.Handle(context.Background(), exportReq("e1", 0), out) // duplicate
	srv.Credit("unknown", 3)
	srv.Cancel("unknown")
	srv.Handle(context.Background(), msg.LogRequest{PlacementRef: "p1", Page: &msg.LogPageQuery{}}, out) // no id
	out.none(t)
	if got := srv.Ignored(); got != 4 {
		t.Fatalf("ignored=%d, want 4", got)
	}
	if srv.Inflight() != 1 {
		t.Fatalf("inflight=%d", srv.Inflight())
	}
}

func TestBusyWhenConcurrentRequestsExceedBound(t *testing.T) {
	r := openRing(t, 1<<20)
	fill(t, r, 10, 100)
	srv := newServer(t, r, 1<<20, 1<<20, WithMaxConcurrent(1))
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out) // holds the one slot, waiting for credit
	srv.Handle(context.Background(), pageReq("r2", 10), out)
	if p := page(t, out); p.RequestID != "r2" || p.Unavailable != msg.UnavailableBusy || len(p.Lines) != 0 {
		t.Fatalf("%+v", p)
	}
	srv.Cancel("e1")
	srv.Handle(context.Background(), pageReq("r3", 10), out)
	if p := page(t, out); p.RequestID != "r3" || p.Unavailable != msg.UnavailableNone || len(p.Lines) != 10 {
		t.Fatalf("a freed slot must serve again: %+v", p.Unavailable)
	}
}
