package logfile

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"
)

const (
	e1 = "2026-10-20T04:46:06.745Z  INFO 1 --- [main] a.B : one"
	e2 = "2026-10-20T04:46:07.100Z  WARN 1 --- [main] a.B : two"
	e3 = "2026-10-20T04:46:08.200Z ERROR 1 --- [main] a.B : three"
)

var (
	ts1 = time.Date(2026, 10, 20, 4, 46, 6, 745e6, time.UTC)
	ts2 = time.Date(2026, 10, 20, 4, 46, 7, 100e6, time.UTC)
	ts3 = time.Date(2026, 10, 20, 4, 46, 8, 200e6, time.UTC)
	now = time.Date(2026, 10, 28, 9, 0, 0, 0, time.UTC)
)

// ranges records the Range header of every request a test server saw.
type ranges struct {
	mu   sync.Mutex
	seen [][2]uint64 // first and last byte requested
}

func (r *ranges) add(h string) {
	var a, b uint64
	if _, err := fmt.Sscanf(h, "bytes=%d-%d", &a, &b); err != nil {
		a, b = 0, 0
	}
	r.mu.Lock()
	r.seen = append(r.seen, [2]uint64{a, b})
	r.mu.Unlock()
}

func (r *ranges) all() [][2]uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([][2]uint64(nil), r.seen...)
}

// fileServer serves content at Path through http.ServeContent, as Spring does for a Range, and records every Range.
func fileServer(t *testing.T, content string) (*File, *ranges) {
	t.Helper()
	rec := &ranges{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != Path {
			http.NotFound(w, r)
			return
		}
		rec.add(r.Header.Get("Range"))
		http.ServeContent(w, r, "", time.Time{}, strings.NewReader(content))
	}))
	t.Cleanup(srv.Close)
	return New(srv.Listener.Addr().String(), WithNow(func() time.Time { return now })), rec
}

func handlerFile(t *testing.T, h http.HandlerFunc) *File {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return New(srv.Listener.Addr().String(), WithNow(func() time.Time { return now }))
}

func read(t *testing.T, f *File, from, until uint64, budget int, prev time.Time) Chunk {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ch, err := f.Read(ctx, from, until, budget, prev)
	if err != nil {
		t.Fatalf("Read(%d, %d, %d): %v", from, until, budget, err)
	}
	return ch
}

func texts(ch Chunk) []string {
	var out []string
	for _, l := range ch.Lines {
		out = append(out, string(l.Text))
	}
	return out
}

func TestReadReturnsWholeLinesWithEnds(t *testing.T) {
	l1, l2, l3 := e1+"\n", e2+"\r\n", e3+"\n"
	f, _ := fileServer(t, l1+l2+l3)

	ch := read(t, f, 0, 0, 1<<20, time.Time{})

	if got := texts(ch); len(got) != 3 || got[0] != e1 || got[1] != e2 || got[2] != e3 {
		t.Fatalf("lines %q", got)
	}
	for i, want := range []time.Time{ts1, ts2, ts3} {
		if !ch.Lines[i].TS.Equal(want) || ch.Lines[i].Stream != "file" || ch.Lines[i].Truncated {
			t.Errorf("line %d: %+v", i, ch.Lines[i])
		}
	}
	wantEnds := []uint64{uint64(len(l1)), uint64(len(l1 + l2)), uint64(len(l1 + l2 + l3))}
	if len(ch.Ends) != 3 || ch.Ends[0] != wantEnds[0] || ch.Ends[1] != wantEnds[1] || ch.Ends[2] != wantEnds[2] {
		t.Fatalf("ends %v, want %v", ch.Ends, wantEnds)
	}
	if ch.Next != ch.Ends[2] || ch.Size != uint64(len(l1+l2+l3)) {
		t.Fatalf("next %d size %d", ch.Next, ch.Size)
	}
}

func TestReadNeverReturnsAnUnterminatedTail(t *testing.T) {
	f, _ := fileServer(t, "A\nB\npartial")

	ch := read(t, f, 0, 0, 1<<20, time.Time{})

	if got := texts(ch); len(got) != 2 || got[0] != "A" || got[1] != "B" {
		t.Fatalf("lines %q", got)
	}
	if ch.Next != 4 || ch.Size != 11 {
		t.Fatalf("next %d size %d", ch.Next, ch.Size)
	}
	// Read again from where it stopped: only the tail is left, and it is not sent.
	ch = read(t, f, 4, 0, 1<<20, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 4 {
		t.Fatalf("second read: %q next %d", texts(ch), ch.Next)
	}
	// A read that starts exactly at the end of the file has only the probe byte to look at.
	ch = read(t, f, 11, 0, 1<<20, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 11 || ch.Size != 11 {
		t.Fatalf("read at the end: %q next %d size %d", texts(ch), ch.Next, ch.Size)
	}
}

func TestReadGivesContinuationLinesTheirEntryTime(t *testing.T) {
	head := e1 + "\n"
	body := "\tat com.x.Y(Y.java:1)\n\tat com.x.Z(Z.java:2)\n"
	f, _ := fileServer(t, head+body+e2+"\n\tat com.x.W(W.java:3)\n")

	ch := read(t, f, 0, 0, 1<<20, time.Time{})
	if len(ch.Lines) != 5 {
		t.Fatalf("lines %q", texts(ch))
	}
	for i, want := range []time.Time{ts1, ts1, ts1, ts2, ts2} {
		if !ch.Lines[i].TS.Equal(want) {
			t.Errorf("line %d: %v, want %v", i, ch.Lines[i].TS, want)
		}
	}

	// A read that starts on continuation lines takes prev, else the next entry in the read.
	from := uint64(len(head))
	prev := ts1.Add(-time.Second)
	ch = read(t, f, from, 0, 1<<20, prev)
	if !ch.Lines[0].TS.Equal(prev) || !ch.Lines[1].TS.Equal(prev) || !ch.Lines[2].TS.Equal(ts2) {
		t.Errorf("with prev: %v %v %v", ch.Lines[0].TS, ch.Lines[1].TS, ch.Lines[2].TS)
	}
	ch = read(t, f, from, 0, 1<<20, time.Time{})
	if !ch.Lines[0].TS.Equal(ts2) || !ch.Lines[1].TS.Equal(ts2) {
		t.Errorf("without prev: %v %v", ch.Lines[0].TS, ch.Lines[1].TS)
	}

	// No entry start anywhere in the read: the injected fetch time.
	g, _ := fileServer(t, body)
	ch = read(t, g, 0, 0, 1<<20, time.Time{})
	if len(ch.Lines) != 2 || !ch.Lines[0].TS.Equal(now) || !ch.Lines[1].TS.Equal(now) {
		t.Errorf("no entry: %+v", ch.Lines)
	}
}

func TestReadFromMidLineSkipsToTheNextLineStart(t *testing.T) {
	l1, l2, l3 := e1+"\n", e2+"\n", e3+"\n"
	f, _ := fileServer(t, l1+l2+l3)

	ch := read(t, f, 7, 0, 1<<20, time.Time{})
	if got := texts(ch); len(got) != 2 || got[0] != e2 || got[1] != e3 {
		t.Fatalf("mid-line: %q", got)
	}
	if ch.Ends[0] != uint64(len(l1+l2)) || ch.Next != uint64(len(l1+l2+l3)) {
		t.Fatalf("ends %v next %d", ch.Ends, ch.Next)
	}

	ch = read(t, f, uint64(len(l1)), 0, 1<<20, time.Time{})
	if got := texts(ch); len(got) != 2 || got[0] != e2 {
		t.Fatalf("at a line start: %q", got)
	}
	// Starting on the '\n' itself is mid-line too: the line it ends is not ours.
	ch = read(t, f, uint64(len(l1))-1, 0, 1<<20, time.Time{})
	if got := texts(ch); len(got) != 2 || got[0] != e2 {
		t.Fatalf("on the newline: %q", got)
	}
}

func TestReadMidLineWithNoLineStartInTheRangeStillAdvances(t *testing.T) {
	f, _ := fileServer(t, strings.Repeat("x", 3000)+"\ntail\n")

	// Three budgets of one long line, seen from inside it: nothing to return, but each read moves on.
	ch := read(t, f, 10, 0, 1000, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 1010 {
		t.Fatalf("first: %d lines, next %d", len(ch.Lines), ch.Next)
	}
	ch = read(t, f, ch.Next, 0, 1000, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 2010 {
		t.Fatalf("second: %d lines, next %d", len(ch.Lines), ch.Next)
	}
	ch = read(t, f, ch.Next, 0, 1000, time.Time{})
	if got := texts(ch); len(got) != 1 || got[0] != "tail" || ch.Next != 3006 {
		t.Fatalf("third: %q next %d", got, ch.Next)
	}
	// The start of the next line is found but its line is not complete in the range: Next is that line's start.
	g, _ := fileServer(t, "ab\n"+strings.Repeat("y", 50)+"\n")
	ch = read(t, g, 1, 0, 20, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 3 {
		t.Fatalf("skip then partial: %d lines, next %d", len(ch.Lines), ch.Next)
	}
}

func TestReadStopsAtUntil(t *testing.T) {
	f, rec := fileServer(t, e1+"\n"+e2+"\n"+e3+"\n")
	all := read(t, f, 0, 0, 1<<20, time.Time{})
	until := all.Ends[1]

	ch := read(t, f, 0, until, 1<<20, time.Time{})

	if got := texts(ch); len(got) != 2 || got[1] != e2 || ch.Next != until {
		t.Fatalf("lines %q next %d", got, ch.Next)
	}
	for _, r := range rec.all()[1:] { // the first request was the unbounded read above
		if r[1] >= until {
			t.Errorf("requested byte %d, at or past until %d", r[1], until)
		}
	}
	// until inside a line: that line is not complete before it, so it is not returned.
	ch = read(t, f, 0, until-5, 1<<20, time.Time{})
	if got := texts(ch); len(got) != 1 || ch.Next != all.Ends[0] {
		t.Fatalf("until mid-line: %q next %d", got, ch.Next)
	}
	// Nothing to read, so nothing is asked.
	n := len(rec.all())
	ch = read(t, f, until, until, 1<<20, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != until || len(rec.all()) != n {
		t.Fatalf("from == until: %+v, %d requests", ch, len(rec.all())-n)
	}
}

func TestReadCutsALineLongerThanTheBudget(t *testing.T) {
	long := e1[:26] + strings.Repeat("€", 1000) // 3000 bytes of 3-byte runes after the stamp
	f, rec := fileServer(t, long+"\nnext\n")

	ch := read(t, f, 0, 0, 1000, time.Time{})

	if len(ch.Lines) != 1 {
		t.Fatalf("%d lines", len(ch.Lines))
	}
	l := ch.Lines[0]
	if len(l.Text) > 1000 || !utf8.Valid(l.Text) || !l.Truncated || !bytes.HasPrefix([]byte(long), l.Text) || len(l.Text) < 990 {
		t.Fatalf("text %d bytes, valid=%v truncated=%v", len(l.Text), utf8.Valid(l.Text), l.Truncated)
	}
	if !l.TS.Equal(ts1) {
		t.Errorf("ts %v", l.TS)
	}
	want := uint64(len(long)) + 1
	if ch.Ends[0] != want || ch.Next != want {
		t.Fatalf("end %d next %d, want %d", ch.Ends[0], ch.Next, want)
	}
	for _, r := range rec.all() {
		if r[1]-r[0]+1 > 1001 {
			t.Errorf("range %v is wider than the budget", r)
		}
	}
	// The next read is the line after it.
	ch = read(t, f, ch.Next, 0, 1000, time.Time{})
	if got := texts(ch); len(got) != 1 || got[0] != "next" {
		t.Fatalf("after: %q", got)
	}
}

func TestReadALineExactlyTheBudgetIsNotTruncated(t *testing.T) {
	line := strings.Repeat("é", 500) // 1000 bytes
	for _, nl := range []string{"\n", "\r\n"} {
		f, _ := fileServer(t, line+nl+"after\n")
		ch := read(t, f, 0, 0, 1000, time.Time{})
		if len(ch.Lines) != 1 || string(ch.Lines[0].Text) != line || ch.Lines[0].Truncated {
			t.Errorf("%q: %d lines, truncated=%v", nl, len(ch.Lines), len(ch.Lines) == 1 && ch.Lines[0].Truncated)
			continue
		}
		if ch.Next != uint64(len(line+nl)) {
			t.Errorf("%q: next %d", nl, ch.Next)
		}
	}
	// One byte over is cut.
	f, _ := fileServer(t, line+"x\n")
	ch := read(t, f, 0, 0, 1000, time.Time{})
	if len(ch.Lines) != 1 || !ch.Lines[0].Truncated || string(ch.Lines[0].Text) != line {
		t.Fatalf("one over: %+v", ch.Lines)
	}
}

func TestReadALongLineStillBeingWrittenIsNotSent(t *testing.T) {
	f, _ := fileServer(t, "ok\n"+strings.Repeat("z", 2500)) // no '\n' yet
	ch := read(t, f, 3, 0, 1000, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 3 {
		t.Fatalf("%d lines, next %d", len(ch.Lines), ch.Next)
	}
	// And one that runs past until.
	g, _ := fileServer(t, strings.Repeat("z", 2500)+"\n")
	ch = read(t, g, 0, 1800, 1000, time.Time{})
	if len(ch.Lines) != 0 || ch.Next != 0 {
		t.Fatalf("until: %d lines, next %d", len(ch.Lines), ch.Next)
	}
}

func TestReadPastTheEndReportsTheSize(t *testing.T) {
	f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes */300")
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
	})
	ch := read(t, f, 500, 0, 1<<20, time.Time{})
	if ch.Size != 300 || len(ch.Lines) != 0 || ch.Next != 500 {
		t.Fatalf("416: %+v", ch)
	}

	g, _ := fileServer(t, "") // http.ServeContent answers an empty file 200 with Content-Length: 0
	ch = read(t, g, 0, 0, 1<<20, time.Time{})
	if ch.Size != 0 || len(ch.Lines) != 0 || ch.Next != 0 {
		t.Fatalf("empty file: %+v", ch)
	}
	ch = read(t, g, 40, 0, 1<<20, time.Time{})
	if ch.Size != 0 || len(ch.Lines) != 0 || ch.Next != 40 {
		t.Fatalf("empty file, from 40: %+v", ch)
	}

	// Spring's own answer to any range on an empty file.
	h := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes */0")
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
	})
	ch = read(t, h, 0, 0, 1<<20, time.Time{})
	if ch.Size != 0 || len(ch.Lines) != 0 || ch.Next != 0 {
		t.Fatalf("spring empty file: %+v", ch)
	}
}

func TestReadRefusesAWholeFileAnswerWithoutReadingIt(t *testing.T) {
	f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1073741824")
		w.WriteHeader(http.StatusOK)
		w.Write(make([]byte, 1024))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	})
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	start := time.Now()
	_, err := f.Read(ctx, 0, 0, 1<<20, time.Time{})

	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("err %v", err)
	}
	if d := time.Since(start); d > time.Second {
		t.Fatalf("took %v: the body was waited for", d)
	}
}

func TestReadUnavailableStatuses(t *testing.T) {
	for _, code := range []int{404, 401, 403, 500, 204, 400} {
		f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(code) })
		_, err := f.Read(context.Background(), 0, 0, 1000, time.Time{})
		if !errors.Is(err, ErrUnavailable) {
			t.Errorf("status %d: %v", code, err)
		}
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	ln.Close()
	if _, err := New(addr).Read(context.Background(), 0, 0, 1000, time.Time{}); !errors.Is(err, ErrUnavailable) {
		t.Errorf("closed listener: %v", err)
	}

	slow := handlerFile(t, func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := slow.Read(ctx, 0, 0, 1000, time.Time{}); !errors.Is(err, ErrUnavailable) || !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("deadline: %v", err)
	}
}

func TestReadRefusesAnUnusableContentRange(t *testing.T) {
	for name, cr := range map[string]string{
		"missing":          "",
		"malformed":        "bytes zero-nine/10",
		"star total":       "bytes 0-9/*",
		"wrong start":      "bytes 5-9/100",
		"past the request": "bytes 0-99/200",
		"short of request": "bytes 0-4/100",
		"past the total":   "bytes 0-9/5",
		"reversed":         "bytes 9-0/100",
	} {
		f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
			if cr != "" {
				w.Header().Set("Content-Range", cr)
			}
			w.WriteHeader(http.StatusPartialContent)
			w.Write([]byte("0123456789"))
		})
		_, err := f.Read(context.Background(), 0, 0, 10, time.Time{})
		if !errors.Is(err, ErrUnavailable) {
			t.Errorf("%s: %v", name, err)
		}
	}
	// A 416 without a usable size is as unusable.
	f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusRequestedRangeNotSatisfiable) })
	if _, err := f.Read(context.Background(), 0, 0, 10, time.Time{}); !errors.Is(err, ErrUnavailable) {
		t.Errorf("416 without Content-Range: %v", err)
	}
	// A body shorter than its Content-Range says.
	g := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes 0-9/100")
		w.WriteHeader(http.StatusPartialContent)
		w.Write([]byte("0123"))
	})
	if _, err := g.Read(context.Background(), 0, 0, 10, time.Time{}); !errors.Is(err, ErrUnavailable) {
		t.Errorf("short body: %v", err)
	}
}

func TestReadErrorsNeverCarryLogText(t *testing.T) {
	const secret = "s3cr3t-token-in-a-log-line"
	f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte(secret))
	})
	_, err := f.Read(context.Background(), 0, 0, 1000, time.Time{})
	if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), secret) {
		t.Fatalf("err %v", err)
	}
	g := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes "+secret+"/10")
		w.WriteHeader(http.StatusPartialContent)
		w.Write([]byte(secret))
	})
	_, err = g.Read(context.Background(), 0, 0, 1000, time.Time{})
	if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), secret) {
		t.Fatalf("err %v", err)
	}
}

func TestReadRangesStayWithinTheBudget(t *testing.T) {
	var sb strings.Builder
	for i := range 40 {
		fmt.Fprintf(&sb, "%s #%d\n\tat com.x.Y(Y.java:%d)\n", e1, i, i)
	}
	sb.WriteString(strings.Repeat("w", 900) + "\n")
	const budget = 300
	f, rec := fileServer(t, sb.String())

	var from uint64
	for i := 0; i < 200; i++ {
		ch := read(t, f, from, 0, budget, time.Time{})
		if ch.Next == from && len(ch.Lines) == 0 {
			break
		}
		from = ch.Next
	}
	for _, from := range []uint64{0, 1, 77, 5000} {
		read(t, f, from, 0, budget, time.Time{})
		read(t, f, from, from+500, budget, time.Time{})
	}
	if len(rec.all()) < 10 {
		t.Fatalf("only %d requests recorded", len(rec.all()))
	}
	for _, r := range rec.all() {
		if r[1] < r[0] || r[1]-r[0]+1 > budget+1 {
			t.Errorf("range %v spans %d bytes, budget %d", r, r[1]-r[0]+1, budget)
		}
	}
}

func TestReadUsesNoProxyAndFollowsNoRedirect(t *testing.T) {
	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
	t.Setenv("http_proxy", "http://127.0.0.1:1")
	f, _ := fileServer(t, "A\n")
	if ch := read(t, f, 0, 0, 100, time.Time{}); len(ch.Lines) != 1 {
		t.Fatalf("%d lines", len(ch.Lines))
	}
	// net/http bypasses an environment proxy for loopback, so the line above proves little; this proves the rule.
	if tr, ok := defaultClient.Transport.(*http.Transport); !ok || tr.Proxy != nil {
		t.Fatal("the default client may use a proxy")
	}

	var mu sync.Mutex
	var paths []string
	r := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		paths = append(paths, r.URL.Path)
		mu.Unlock()
		http.Redirect(w, r, "/elsewhere", http.StatusFound)
	})
	if _, err := r.Read(context.Background(), 0, 0, 100, time.Time{}); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("302: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(paths) != 1 || paths[0] != Path {
		t.Fatalf("paths seen: %v", paths)
	}
}

func TestOptions(t *testing.T) {
	c := &http.Client{}
	f := New("10.0.0.1:8080", WithClient(c), WithStep(123), WithNow(func() time.Time { return now }))
	if f.url != "http://10.0.0.1:8080/actuator/logfile" || f.client != c || f.step != 123 || !f.now().Equal(now) {
		t.Fatalf("%+v", f)
	}
	d := New("h:1")
	if d.client != defaultClient || d.step != DefaultStep || d.now == nil {
		t.Fatalf("defaults %+v", d)
	}
}
