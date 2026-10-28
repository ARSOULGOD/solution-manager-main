package serve

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf8"

	"google.golang.org/protobuf/proto"

	"github.com/solace/solution-manager/go-agent/internal/logs/logfile"
	"github.com/solace/solution-manager/go-agent/internal/msg"
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
		t.Fatalf("want LogChunk, got %T %+v", m, m)
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

// readCall is one Source.Read as the server made it.
type readCall struct {
	from, until uint64
	budget      int
	prev        time.Time
}

type pageCall struct {
	q      msg.LogPageQuery
	budget int
}

// fakeSource answers Read and Page from a script and records every call; a call it has no script for fails.
type fakeSource struct {
	mu     sync.Mutex
	reads  []readCall
	pages  []pageCall
	readFn func(ctx context.Context, n int, c readCall) (logfile.Chunk, error) // n: the call's index, 0 first
	pageFn func(ctx context.Context, q msg.LogPageQuery, budget int) ([]msg.LogLine, error)
}

func (f *fakeSource) Read(ctx context.Context, from, until uint64, budget int, prev time.Time) (logfile.Chunk, error) {
	c := readCall{from: from, until: until, budget: budget, prev: prev}
	f.mu.Lock()
	n := len(f.reads)
	f.reads = append(f.reads, c)
	fn := f.readFn
	f.mu.Unlock()
	if fn == nil {
		return logfile.Chunk{}, errors.New("fake: no read scripted")
	}
	return fn(ctx, n, c)
}

func (f *fakeSource) Page(ctx context.Context, q msg.LogPageQuery, budget int) ([]msg.LogLine, error) {
	f.mu.Lock()
	f.pages = append(f.pages, pageCall{q: q, budget: budget})
	fn := f.pageFn
	f.mu.Unlock()
	if fn == nil {
		return nil, errors.New("fake: no page scripted")
	}
	return fn(ctx, q, budget)
}

func (f *fakeSource) readCalls() []readCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.reads)
}

func (f *fakeSource) pageCalls() []pageCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.pages)
}

// text is line i's text, padded with 'a' to size bytes; it starts "line-%04d".
func text(i, size int) string {
	s := fmt.Sprintf("line-%04d ", i)
	return s + strings.Repeat("a", max(size-len(s), 0))
}

// memFile is a log file in memory, read the way logfile.File reads one: whole lines from `from` up to until (0 = no
// bound), skipping to the next line start when from is inside a line, each line charged its text plus
// logfile.LineCost within budget but always the first line, or every line when greedy (so that only the server's trim
// bounds a chunk). Line i is stamped t0 + i ms.
type memFile struct {
	mu     sync.Mutex
	lines  []string
	greedy bool
}

func newMemFile(n, size int) *memFile {
	f := &memFile{}
	for i := range n {
		f.lines = append(f.lines, text(i, size))
	}
	return f
}

func (f *memFile) set(lines ...string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.lines = lines
}

func (f *memFile) add(lines ...string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.lines = append(f.lines, lines...)
}

func (f *memFile) size() uint64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	var n uint64
	for _, l := range f.lines {
		n += uint64(len(l)) + 1
	}
	return n
}

func (f *memFile) read(_ context.Context, _ int, c readCall) (logfile.Chunk, error) {
	size := f.size()
	f.mu.Lock()
	defer f.mu.Unlock()
	ch := logfile.Chunk{Next: c.from, Size: size}
	end := size
	if c.until != 0 {
		end = min(end, c.until)
	}
	var off uint64
	charge := 0
	for i, l := range f.lines {
		start := off
		off += uint64(len(l)) + 1
		switch {
		case start < c.from:
			if off > c.from && off <= end {
				ch.Next = off // from is inside this line: its tail is skipped
			}
			continue
		case off > end, len(ch.Lines) > 0 && !f.greedy && charge+len(l)+logfile.LineCost > c.budget:
			return ch, nil
		}
		ch.Lines = append(ch.Lines, msg.LogLine{TS: t0.Add(time.Duration(i) * time.Millisecond), Stream: logfile.Stream, Text: []byte(l)})
		ch.Ends = append(ch.Ends, off)
		ch.Next, charge = off, charge+len(l)+logfile.LineCost
	}
	return ch, nil
}

// numberedLines is n LogLines of size bytes, line i stamped t0 + i ms.
func numberedLines(n, size int) []msg.LogLine {
	var out []msg.LogLine
	for i := range n {
		out = append(out, msg.LogLine{TS: t0.Add(time.Duration(i) * time.Millisecond), Stream: logfile.Stream, Text: []byte(text(i, size))})
	}
	return out
}

// newestFitting is a Page that honours its query's Limit and its text budget over numberedLines(n, size): the newest
// lines, oldest first.
func newestFitting(n, size int) func(context.Context, msg.LogPageQuery, int) ([]msg.LogLine, error) {
	all := numberedLines(n, size)
	return func(_ context.Context, q msg.LogPageQuery, budget int) ([]msg.LogLine, error) {
		i, cost := len(all), 0
		for i > 0 && (q.Limit == 0 || len(all)-i < int(q.Limit)) && cost+max(len(all[i-1].Text), 1) <= budget {
			i--
			cost += max(len(all[i].Text), 1)
		}
		return slices.Clone(all[i:]), nil
	}
}

// ignoring is a Page that returns lines whatever its budget.
func ignoring(lines []msg.LogLine) func(context.Context, msg.LogPageQuery, int) ([]msg.LogLine, error) {
	return func(context.Context, msg.LogPageQuery, int) ([]msg.LogLine, error) { return slices.Clone(lines), nil }
}

func opener(src Source) Opener {
	return func(_ context.Context, p string) (Source, msg.UnavailableReason) {
		if p != "p1" {
			return nil, msg.UnavailableNotCollecting
		}
		return src, msg.UnavailableNone
	}
}

func newServer(t *testing.T, src Source, maxMsg, maxBatch uint64, opts ...Option) *Server {
	t.Helper()
	srv := New(opener(src), func(string) bool { return true }, func() (uint64, uint64) { return maxMsg, maxBatch }, opts...)
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

// lineNo is the number a text() line starts with.
func lineNo(t *testing.T, l msg.LogLine) int {
	t.Helper()
	var n int
	if _, err := fmt.Sscanf(string(l.Text), "line-%04d", &n); err != nil {
		t.Fatalf("not a numbered line: %q", l.Text)
	}
	return n
}

// inOrder fails unless the chunks' lines are numbered from, from+1, ... with no gap or repeat; it returns how many.
func inOrder(t *testing.T, from int, cs []msg.LogChunk) int {
	t.Helper()
	want := from
	for _, c := range cs {
		for _, l := range c.Lines {
			if got := lineNo(t, l); got != want {
				t.Fatalf("line %d where %d was due: lost or repeated", got, want)
			}
			want++
		}
	}
	return want - from
}

// ---- pages ----

func TestPageRequestAnswersOneLogPageWithinMaxMessageBytes(t *testing.T) {
	after, before := t0.Add(-time.Hour), t0.Add(time.Hour)
	q := msg.LogPageQuery{Limit: 1000, After: &after, Before: &before}
	src := &fakeSource{pageFn: newestFitting(200, 100)}
	srv := newServer(t, src, 4096, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), msg.LogRequest{RequestID: "r1", PlacementRef: "p1", Page: &q}, out)
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
	if got := lineNo(t, p.Lines[len(p.Lines)-1]); got != 199 {
		t.Fatalf("the page must hold the newest lines, last is line %d", got)
	}
	// As asked, but for a Limit no larger than the lines the page has room to carry framed.
	most := uint32((4096 - (headerOverhead + len("r1") + len(p.MessageID))) / logfile.LineCost)
	if calls := src.pageCalls(); len(calls) != 1 || calls[0].q.Limit != min(q.Limit, most) || calls[0].q.After != q.After || calls[0].q.Before != q.Before {
		t.Fatalf("the query must reach the source as asked, its Limit at most %d: %+v", most, calls)
	}
	out.none(t) // exactly one page
}

func TestPageBudgetLeavesRoomForFramingSoNothingIsTrimmed(t *testing.T) {
	for _, c := range []struct {
		limit uint32
		size  int
	}{{0, 60}, {0, 300}, {10, 12}, {500, 120}} {
		t.Run(fmt.Sprintf("limit%d-size%d", c.limit, c.size), func(t *testing.T) {
			fn := newestFitting(5000, c.size)
			src := &fakeSource{pageFn: fn}
			srv := newServer(t, src, 4096, 1<<20)
			out := newSink()
			srv.Handle(context.Background(), pageReq("r1", c.limit), out)
			p := page(t, out)
			call := src.pageCalls()[0]
			asked, _ := fn(context.Background(), call.q, call.budget)
			if len(p.Lines) != len(asked) || len(p.Lines) == 0 {
				t.Fatalf("the source gave %d lines within its budget %d and the page carries %d: trim fired", len(asked), call.budget, len(p.Lines))
			}
			if n := wireSize(t, p); n > 4096 {
				t.Fatalf("page is %d bytes", n)
			}
		})
	}
}

func TestPageTrimsTheOldestToFitMaxMessageBytes(t *testing.T) {
	all := numberedLines(200, 100)
	srv := newServer(t, &fakeSource{pageFn: ignoring(all)}, 4096, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), pageReq("r1", 0), out)
	p := page(t, out)
	if n := wireSize(t, p); n > 4096 {
		t.Fatalf("page is %d bytes, over max_message_bytes 4096", n)
	}
	if len(p.Lines) < 2 || len(p.Lines) >= len(all) {
		t.Fatalf("%d lines: the oldest must be dropped, and only as many as needed", len(p.Lines))
	}
	for i, l := range p.Lines { // the newest, in order
		if want := len(all) - len(p.Lines) + i; lineNo(t, l) != want {
			t.Fatalf("line %d is %d, want %d", i, lineNo(t, l), want)
		}
	}
}

func TestPageOfEmptyLinesAsksTheSourceForNoMoreLinesThanTheRoomCarries(t *testing.T) {
	// An empty line costs a Source's page budget one byte but a reply logfile.LineCost: with Limit 0 passed on, the
	// Source would build about one LogLine per byte of its budget for trim to drop all but a few.
	empty := make([]msg.LogLine, 1<<16)
	for i := range empty {
		empty[i] = msg.LogLine{TS: t0, Stream: logfile.Stream}
	}
	src := &fakeSource{pageFn: func(_ context.Context, q msg.LogPageQuery, budget int) ([]msg.LogLine, error) {
		n := min(len(empty), budget) // max(len, 1) a line, as logfile.File charges a page
		if q.Limit > 0 {
			n = min(n, int(q.Limit))
		}
		return slices.Clone(empty[len(empty)-n:]), nil
	}}
	const maxMsg = 64 << 10
	srv := newServer(t, src, maxMsg, 1<<20, WithNewID(func() string { return "m1" }))
	out := newSink()
	srv.Handle(context.Background(), pageReq("r1", 0), out)
	p := page(t, out)
	most := (maxMsg - (headerOverhead + len("r1") + len("m1"))) / logfile.LineCost
	if calls := src.pageCalls(); len(calls) != 1 || calls[0].q.Limit == 0 || int(calls[0].q.Limit) > most {
		t.Fatalf("the source was asked for %+v; want a Limit of 1 to %d", calls, most)
	}
	if n := wireSize(t, p); len(p.Lines) == 0 || len(p.Lines) > most || n > maxMsg {
		t.Fatalf("%d lines in %d bytes", len(p.Lines), n)
	}
}

func TestPageTrimDropsWholeTimeGroups(t *testing.T) {
	// An entry and its two trace lines share one time: 20 entries of three lines.
	var grouped []msg.LogLine
	for i, l := range numberedLines(60, 100) {
		l.TS = t0.Add(time.Duration(i/3) * time.Second)
		grouped = append(grouped, l)
	}
	sameTime := numberedLines(60, 100)
	for i := range sameTime {
		sameTime[i].TS = t0
	}
	for name, all := range map[string][]msg.LogLine{"groups": grouped, "one-group": sameTime} {
		t.Run(name, func(t *testing.T) {
			srv := newServer(t, &fakeSource{pageFn: ignoring(all)}, 2048, 1<<20)
			out := newSink()
			srv.Handle(context.Background(), pageReq("r1", 0), out)
			p := page(t, out)
			if n := wireSize(t, p); n > 2048 {
				t.Fatalf("page is %d bytes", n)
			}
			if len(p.Lines) == 0 || len(p.Lines) >= len(all) {
				t.Fatalf("%d lines", len(p.Lines))
			}
			dropped := all[:len(all)-len(p.Lines)]
			for i, l := range p.Lines {
				if lineNo(t, l) != lineNo(t, all[len(dropped)+i]) {
					t.Fatal("the page must be the newest lines, in order")
				}
			}
			if name == "one-group" {
				return // dropping the whole group would empty the page: the newest of it stay
			}
			for _, d := range dropped {
				if p.Lines[0].TS.Equal(d.TS) {
					t.Fatalf("the page starts inside the time group %v that it dropped part of", d.TS)
				}
			}
			if len(p.Lines)%3 != 0 {
				t.Fatalf("%d lines: whole entries only", len(p.Lines))
			}
		})
	}
}

func TestPageErrorIsNotCollecting(t *testing.T) {
	src := &fakeSource{pageFn: func(context.Context, msg.LogPageQuery, int) ([]msg.LogLine, error) {
		return nil, fmt.Errorf("%w: status 503", logfile.ErrUnavailable)
	}}
	srv := newServer(t, src, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), pageReq("r1", 10), out)
	if p := page(t, out); p.RequestID != "r1" || p.Unavailable != msg.UnavailableNotCollecting || len(p.Lines) != 0 || len(p.Gaps) != 0 {
		t.Fatalf("%+v", p)
	}
	out.none(t)
}

// ---- opening ----

func TestOpenRefusalIsAPage(t *testing.T) {
	for _, c := range []struct {
		name string
		why  msg.UnavailableReason
		want msg.UnavailableReason
	}{
		{"container-missing", msg.UnavailableContainerMissing, msg.UnavailableContainerMissing},
		{"not-collecting", msg.UnavailableNotCollecting, msg.UnavailableNotCollecting},
		{"no-source", msg.UnavailableNone, msg.UnavailableNotCollecting}, // nil Source with no reason
	} {
		t.Run(c.name, func(t *testing.T) {
			srv := New(func(context.Context, string) (Source, msg.UnavailableReason) { return nil, c.why },
				func(string) bool { return true }, func() (uint64, uint64) { return 1 << 20, 1 << 20 })
			t.Cleanup(srv.Wait)
			out := newSink()
			for _, req := range []msg.LogRequest{pageReq("r-page", 10), exportReq("r-export", 0)} {
				srv.Handle(context.Background(), req, out)
				if p := page(t, out); p.RequestID != req.RequestID || p.Unavailable != c.want || len(p.Lines) != 0 {
					t.Fatalf("%s: %+v", req.RequestID, p)
				}
			}
			out.none(t) // one page each, and no chunk for the export
			srv.Wait()
			if srv.Inflight() != 0 {
				t.Fatal("a refused request keeps its slot")
			}
		})
	}
}

func TestHandleDoesNotBlockOnOpen(t *testing.T) {
	release := make(chan struct{})
	var opens atomic.Int32
	src := &fakeSource{pageFn: newestFitting(5, 50)}
	srv := New(func(ctx context.Context, _ string) (Source, msg.UnavailableReason) {
		opens.Add(1)
		select {
		case <-release:
			return src, msg.UnavailableNone
		case <-ctx.Done():
			return nil, msg.UnavailableNotCollecting
		}
	}, func(string) bool { return true }, func() (uint64, uint64) { return 1 << 20, 1 << 20 })
	t.Cleanup(func() { srv.CancelAll(); srv.Wait() })
	out := newSink()
	for i := range DefaultMaxConcurrent {
		returned := make(chan struct{})
		go func() { srv.Handle(context.Background(), pageReq(fmt.Sprintf("r%d", i), 10), out); close(returned) }()
		select {
		case <-returned:
		case <-time.After(time.Second):
			t.Fatal("Handle blocked on the open")
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	for opens.Load() < DefaultMaxConcurrent {
		if time.Now().After(deadline) {
			t.Fatalf("%d opens under way, want %d", opens.Load(), DefaultMaxConcurrent)
		}
		time.Sleep(5 * time.Millisecond)
	}
	srv.Handle(context.Background(), pageReq("r-busy", 10), out)
	if p := page(t, out); p.RequestID != "r-busy" || p.Unavailable != msg.UnavailableBusy {
		t.Fatalf("a request over the bound while the others open must be BUSY: %+v", p)
	}
	close(release)
	for range DefaultMaxConcurrent {
		if p := page(t, out); p.Unavailable != msg.UnavailableNone || len(p.Lines) != 5 {
			t.Fatalf("%+v", p)
		}
	}
}

func TestEachCallIsBoundedByTheReadTimeout(t *testing.T) {
	// Only the call's own deadline ends it: without one the request is never answered.
	blocked := func(ctx context.Context) error {
		<-ctx.Done()
		return fmt.Errorf("%w: %w", logfile.ErrUnavailable, ctx.Err())
	}
	limits := func() (uint64, uint64) { return 1 << 20, 1 << 20 }
	for name, c := range map[string]struct {
		open Opener
		req  msg.LogRequest
	}{
		"open": {func(ctx context.Context, _ string) (Source, msg.UnavailableReason) {
			if blocked(ctx) != nil {
				return nil, msg.UnavailableNotCollecting
			}
			return nil, msg.UnavailableNone
		}, pageReq("r1", 10)},
		"page": {opener(&fakeSource{pageFn: func(ctx context.Context, _ msg.LogPageQuery, _ int) ([]msg.LogLine, error) {
			return nil, blocked(ctx)
		}}), pageReq("r1", 10)},
		"export-read": {opener(&fakeSource{readFn: func(ctx context.Context, _ int, _ readCall) (logfile.Chunk, error) {
			return logfile.Chunk{}, blocked(ctx)
		}}), exportReq("r1", 0)},
	} {
		t.Run(name, func(t *testing.T) {
			srv := New(c.open, func(string) bool { return true }, limits,
				WithReadTimeout(50*time.Millisecond), WithInitialCredits(func() uint32 { return 1 }))
			t.Cleanup(func() { srv.CancelAll(); srv.Wait() })
			out := newSink()
			start := time.Now()
			srv.Handle(context.Background(), c.req, out)
			p := page(t, out)
			if p.Unavailable != msg.UnavailableNotCollecting {
				t.Fatalf("%+v", p)
			}
			if d := time.Since(start); d > time.Second {
				t.Fatalf("answered after %v; the read timeout is 50ms", d)
			}
			out.none(t)
		})
	}
}

// ---- exports ----

func TestExportEmitsChunksOnlyWithCredits(t *testing.T) {
	f := newMemFile(50, 100)
	srv := newServer(t, &fakeSource{readFn: f.read}, 1<<20, 1024)
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

	all := append([]msg.LogChunk{first}, drain(t, srv, out, "e1")...)
	for _, c := range all {
		if wireSize(t, c) > 1024 {
			t.Fatal("chunk over max_batch_bytes")
		}
	}
	if n := inOrder(t, 0, all); n != 50 {
		t.Fatalf("exported %d lines, want 50", n)
	}
	srv.Wait()
	if srv.Inflight() != 0 {
		t.Fatal("a finished export keeps its state")
	}
}

func TestExportSnapshotEndsAtTheFirstReportedSize(t *testing.T) {
	f := newMemFile(3, 99) // 300 bytes: three lines of 99 and their '\n'
	src := &fakeSource{readFn: func(ctx context.Context, n int, c readCall) (logfile.Chunk, error) {
		ch, err := f.read(ctx, n, c)
		if n == 0 {
			f.add(text(3, 99), text(4, 99)) // the file grows once the export has begun
		}
		return ch, err
	}}
	srv := newServer(t, src, 1<<20, 300) // room for one line a chunk: 99 bytes of text and its framing
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	cs := drain(t, srv, out, "e1")
	if n := inOrder(t, 0, cs); n != 3 {
		t.Fatalf("exported %d lines, want the 3 of the snapshot", n)
	}
	if last := cs[len(cs)-1]; last.Seq != 300 {
		t.Fatalf("final chunk seq %d, want 300", last.Seq)
	}
	reads := src.readCalls()
	if reads[0].from != 0 || reads[0].until != 0 {
		t.Fatalf("the first read is unbounded: %+v", reads[0])
	}
	for _, r := range reads[1:] {
		if r.until != 300 {
			t.Fatalf("a later read has until %d, want the snapshot's 300", r.until)
		}
	}
	if len(reads) < 3 {
		t.Fatalf("%d reads", len(reads))
	}
}

func TestExportSeqIsTheByteOffsetAfterTheLastSentLine(t *testing.T) {
	f := newMemFile(40, 100) // line i spans [101i, 101i+101)
	f.greedy = true          // every line at once: only trim bounds a chunk
	srv := newServer(t, &fakeSource{readFn: f.read}, 1<<20, 1024)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	first := chunk(t, out)
	srv.Cancel("e1")
	k := len(first.Lines)
	if k == 0 || k >= 40 {
		t.Fatalf("%d lines: trim must drop the newest", k)
	}
	if first.Seq != uint64(101*k) {
		t.Fatalf("seq %d, want %d: the offset just past the last line sent", first.Seq, 101*k)
	}

	srv.Handle(context.Background(), exportReq("e2", first.Seq), out)
	rest := drain(t, srv, out, "e2")
	if got := lineNo(t, rest[0].Lines[0]); got != k {
		t.Fatalf("resumed at line %d, want %d", got, k)
	}
	if n := inOrder(t, 0, append([]msg.LogChunk{first}, rest...)); n != 40 {
		t.Fatalf("the first chunk and the resume carried %d lines, want 40", n)
	}
}

func TestExportFromBeyondTheSizeRestartsWithDroppedOldest(t *testing.T) {
	f := newMemFile(3, 99) // 300 bytes
	src := &fakeSource{readFn: f.read}
	srv := newServer(t, src, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 5000), out)
	srv.Credit("e1", 1)
	c := chunk(t, out)
	if len(c.Lines) == 0 || lineNo(t, c.Lines[0]) != 0 {
		t.Fatalf("the export must start again at 0: %+v", c)
	}
	if len(c.Gaps) == 0 || c.Gaps[0].Reason != msg.GapDroppedOldest || !c.Gaps[0].From.Equal(c.Lines[0].TS) || !c.Gaps[0].To.Equal(c.Lines[0].TS) {
		t.Fatalf("the first gap must be DROPPED_OLDEST at the first line's time: %+v", c.Gaps)
	}
	reads := src.readCalls()
	if len(reads) < 2 || reads[0].from != 5000 || reads[1].from != 0 || reads[1].until != 300 {
		t.Fatalf("reads %+v", reads)
	}
	if !c.Final || c.Seq != 300 {
		t.Fatalf("seq %d final %v", c.Seq, c.Final)
	}
}

func TestExportAtTheExactEndIsAnEmptyFinal(t *testing.T) {
	f := newMemFile(3, 99) // 300 bytes
	src := &fakeSource{readFn: f.read}
	srv := newServer(t, src, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 300), out)
	srv.Credit("e1", 1)
	c := chunk(t, out)
	if !c.Final || len(c.Lines) != 0 || len(c.Gaps) != 0 || c.Seq != 300 {
		t.Fatalf("%+v", c)
	}
	out.none(t)
	if n := len(src.readCalls()); n != 1 {
		t.Fatalf("%d reads, want 1", n)
	}
}

func TestExportOfAnEmptyFileIsOneEmptyFinal(t *testing.T) {
	for _, from := range []uint64{0, 5000} {
		t.Run(fmt.Sprint(from), func(t *testing.T) {
			src := &fakeSource{readFn: (&memFile{}).read} // size 0
			srv := newServer(t, src, 1<<20, 1<<20)
			out := newSink()
			srv.Handle(context.Background(), exportReq("e1", from), out)
			srv.Credit("e1", 3)
			c := chunk(t, out)
			if !c.Final || len(c.Lines) != 0 || c.Seq != 0 {
				t.Fatalf("%+v", c)
			}
			if from > 0 && (len(c.Gaps) != 1 || c.Gaps[0].Reason != msg.GapDroppedOldest) {
				t.Fatalf("a resume into an empty file says the older lines are gone: %+v", c.Gaps)
			}
			out.none(t)
			// until 0 means "no bound": the empty snapshot must never be read again with it.
			if n := len(src.readCalls()); n != 1 {
				t.Fatalf("%d reads, want 1", n)
			}
		})
	}
}

func TestExportEndsCollectionPausedWhenTheFileShrinks(t *testing.T) {
	f := newMemFile(3, 99) // 300 bytes; a max_batch_bytes of 300 is one line a chunk
	srv := newServer(t, &fakeSource{readFn: f.read}, 1<<20, 300)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	first := chunk(t, out)
	if first.Final || first.Seq != 100 {
		t.Fatalf("%+v", first)
	}
	f.set(text(7, 20)) // rotated: 21 bytes, below seq
	srv.Credit("e1", 1)
	c := chunk(t, out)
	if !c.Final || len(c.Lines) != 0 || c.Seq != 100 || len(c.Gaps) != 1 || c.Gaps[0].Reason != msg.GapCollectionPaused {
		t.Fatalf("%+v", c)
	}
	if last := first.Lines[len(first.Lines)-1].TS; !c.Gaps[0].From.Equal(last) || !c.Gaps[0].To.Equal(c.SentAt) {
		t.Fatalf("the gap runs from the last line sent to now: %+v", c.Gaps[0])
	}
	out.none(t)
}

func TestExportFirstReadErrorIsARefusal(t *testing.T) {
	src := &fakeSource{readFn: func(context.Context, int, readCall) (logfile.Chunk, error) {
		return logfile.Chunk{}, fmt.Errorf("%w: status 404", logfile.ErrUnavailable)
	}}
	srv := newServer(t, src, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	if p := page(t, out); p.RequestID != "e1" || p.Unavailable != msg.UnavailableNotCollecting || len(p.Lines) != 0 {
		t.Fatalf("%+v", p)
	}
	out.none(t)
}

func TestExportLaterReadErrorEndsCollectionPaused(t *testing.T) {
	f := newMemFile(3, 99)
	src := &fakeSource{readFn: func(ctx context.Context, n int, c readCall) (logfile.Chunk, error) {
		if n > 0 {
			return logfile.Chunk{}, fmt.Errorf("%w: status 503", logfile.ErrUnavailable)
		}
		return f.read(ctx, n, c)
	}}
	srv := newServer(t, src, 1<<20, 400)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	first := chunk(t, out)
	srv.Credit("e1", 1)
	c := chunk(t, out)
	if !c.Final || len(c.Lines) != 0 || c.Seq != first.Seq || len(c.Gaps) != 1 || c.Gaps[0].Reason != msg.GapCollectionPaused {
		t.Fatalf("%+v", c)
	}
	out.none(t)
}

func TestExportPassesThePreviousTimestamp(t *testing.T) {
	f := newMemFile(40, 100)
	f.greedy = true
	src := &fakeSource{readFn: f.read}
	srv := newServer(t, src, 1<<20, 1024)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Credit("e1", 1)
	first := chunk(t, out)
	srv.Credit("e1", 1)
	chunk(t, out)
	reads := src.readCalls()
	if !reads[0].prev.IsZero() {
		t.Fatalf("the first read has no previous line: %v", reads[0].prev)
	}
	// trim dropped the newest lines read: prev is the last line sent, not the last line read.
	if last := first.Lines[len(first.Lines)-1].TS; !reads[1].prev.Equal(last) || reads[1].from != first.Seq {
		t.Fatalf("second read %+v; want from %d with prev %v", reads[1], first.Seq, last)
	}
}

func TestExportSkipWithoutLinesSpendsNoCredit(t *testing.T) {
	line := msg.LogLine{TS: t0, Stream: logfile.Stream, Text: []byte("line-0001 resumed")}
	src := &fakeSource{readFn: func(_ context.Context, n int, c readCall) (logfile.Chunk, error) {
		switch n {
		case 0: // only the tail of a line begun before from_seq
			return logfile.Chunk{Next: c.from + 10, Size: 300}, nil
		case 1:
			return logfile.Chunk{Lines: []msg.LogLine{line}, Ends: []uint64{100}, Next: 100, Size: 300}, nil
		}
		return logfile.Chunk{}, errors.New("fake: unscripted read")
	}}
	srv := newServer(t, src, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 50), out)
	srv.Credit("e1", 1)
	c := chunk(t, out)
	if len(c.Lines) != 1 || string(c.Lines[0].Text) != string(line.Text) || c.Seq != 100 || c.Final {
		t.Fatalf("%+v", c)
	}
	out.none(t) // one credit, one chunk
	reads := src.readCalls()
	if len(reads) != 2 || reads[1].from != 60 || reads[1].until != 300 {
		t.Fatalf("reads %+v", reads)
	}
}

func TestCancelStopsExportAndFreesState(t *testing.T) {
	f := newMemFile(50, 100)
	srv := newServer(t, &fakeSource{readFn: f.read}, 1<<20, 1024)
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

// ---- admission ----

func TestRequestForPlacementNotInLeaseIsRefusedPLACEMENT_NOT_IN_LEASE(t *testing.T) {
	var opened atomic.Int32
	srv := New(
		func(context.Context, string) (Source, msg.UnavailableReason) {
			opened.Add(1)
			return nil, msg.UnavailableNotCollecting
		},
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
	srv.Wait()
	if opened.Load() != 0 {
		t.Fatal("nothing may be opened for a placement outside the lease")
	}
}

func TestOversizeLineIsTruncatedInPageAndChunk(t *testing.T) {
	big := msg.LogLine{TS: t0, Stream: logfile.Stream, Text: bytes.Repeat([]byte("é"), 10000)} // 20000 bytes of two-byte runes
	src := &fakeSource{
		pageFn: ignoring([]msg.LogLine{big}),
		readFn: func(context.Context, int, readCall) (logfile.Chunk, error) {
			return logfile.Chunk{Lines: []msg.LogLine{big}, Ends: []uint64{20001}, Next: 20001, Size: 20001}, nil
		},
	}
	srv := newServer(t, src, 2048, 4096)
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
	if len(c.Lines) != 1 || !c.Lines[0].Truncated || !utf8.Valid(c.Lines[0].Text) || !c.Final || c.Seq != 20001 {
		t.Fatalf("%d chunks; first has %d lines, final=%v seq=%d", len(cs), len(c.Lines), c.Final, c.Seq)
	}
}

func TestDuplicateAndUnknownRequestIdsAreIgnoredAndCounted(t *testing.T) {
	f := newMemFile(10, 100)
	srv := newServer(t, &fakeSource{readFn: f.read}, 1<<20, 1<<20)
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
	f := newMemFile(10, 100)
	srv := newServer(t, &fakeSource{readFn: f.read, pageFn: newestFitting(10, 100)}, 1<<20, 1<<20, WithMaxConcurrent(1))
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

func TestTrimTruncatesTheLoneLineBeforeDroppingGaps(t *testing.T) {
	line := []msg.LogLine{{TS: t0, Stream: logfile.Stream, Text: bytes.Repeat([]byte("é"), 500)}}
	gaps := []msg.LogGap{{Reason: msg.GapDroppedOldest}, {Reason: msg.GapCollectionPaused}}
	room := size(line, gaps) - 300
	ls, gs := trim(line, gaps, room, true)
	if len(gs) != 2 || len(ls) != 1 || !ls[0].Truncated || !utf8.Valid(ls[0].Text) || size(ls, gs) > room {
		t.Fatalf("the line must be cut first and the gaps kept: %d gaps, %d text", len(gs), len(ls[0].Text))
	}
	// Room below the gaps alone: the line is emptied, then gaps go oldest first.
	ls, gs = trim(line, gaps, size(nil, gaps[1:])+lineOverhead+len(logfile.Stream), true)
	if len(gs) != 1 || gs[0].Reason != msg.GapCollectionPaused || len(ls) != 1 || len(ls[0].Text) != 0 {
		t.Fatalf("%d gaps, %d lines", len(gs), len(ls))
	}
}

func TestRevokeCancelsThePlacementsRequests(t *testing.T) {
	f := newMemFile(10, 100)
	srv := newServer(t, &fakeSource{readFn: f.read}, 1<<20, 1<<20)
	out := newSink()
	srv.Handle(context.Background(), exportReq("e1", 0), out)
	srv.Revoke("other")
	if srv.Inflight() != 1 {
		t.Fatal("revoking another placement touches nothing")
	}
	srv.Revoke("p1")
	if srv.Inflight() != 0 {
		t.Fatal("revoke must end the placement's requests")
	}
	srv.Credit("e1", 3)
	out.none(t)
}
