package ring

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)

func at(i int) time.Time { return t0.Add(time.Duration(i) * time.Second) }

func line(i int, text string) msg.LogLine {
	return msg.LogLine{TS: at(i), Stream: "stdout", Text: []byte(text)}
}

func open(t *testing.T, cap uint64) (*Ring, *store.Store) {
	t.Helper()
	st := store.MustOpen(t.TempDir())
	r, err := Open(st, "p1", cap)
	if err != nil {
		t.Fatal(err)
	}
	return r, st
}

func texts(ls []msg.LogLine) []string {
	var out []string
	for _, l := range ls {
		out = append(out, string(l.Text))
	}
	return out
}

func fill(t *testing.T, r *Ring, n int) {
	t.Helper()
	for i := 1; i <= n; i++ {
		if err := r.Append(line(i, fmt.Sprintf("line-%03d", i))); err != nil {
			t.Fatal(err)
		}
	}
}

func TestAppendThenPageReturnsNewestLimitOldestFirst(t *testing.T) {
	r, _ := open(t, 1<<20)
	fill(t, r, 10)
	ls, _, err := r.Page(nil, nil, 3, 0)
	if err != nil {
		t.Fatal(err)
	}
	got := strings.Join(texts(ls), ",")
	if got != "line-008,line-009,line-010" {
		t.Fatalf("got %s", got)
	}
}

func TestPageBoundsAreExclusive(t *testing.T) {
	r, _ := open(t, 1<<20)
	fill(t, r, 10)
	a, b := at(3), at(7)
	ls, _, _ := r.Page(&a, &b, 100, 0)
	if got := strings.Join(texts(ls), ","); got != "line-004,line-005,line-006" {
		t.Fatalf("got %s", got)
	}
}

func TestCapDropsOldestSegmentAndRecordsGap(t *testing.T) {
	r, _ := open(t, 4096)
	big := strings.Repeat("x", 200)
	for i := 1; i <= 100; i++ {
		if err := r.Append(line(i, fmt.Sprintf("%03d%s", i, big))); err != nil {
			t.Fatal(err)
		}
	}
	ls, gaps, _ := r.Page(nil, nil, 1000, 0)
	if len(ls) == 0 || len(ls) >= 100 {
		t.Fatalf("lines %d", len(ls))
	}
	if !strings.HasPrefix(string(ls[len(ls)-1].Text), "100") {
		t.Fatal("newest line lost")
	}
	if len(gaps) == 0 || gaps[0].Reason != "DROPPED_OLDEST" {
		t.Fatalf("gaps %+v", gaps)
	}
	if !gaps[0].From.Equal(at(1)) {
		t.Fatalf("gap from %v", gaps[0].From)
	}
}

func TestSetCapSmallerTrimsImmediately(t *testing.T) {
	r, _ := open(t, 1<<20)
	big := strings.Repeat("y", 200)
	for i := 1; i <= 100; i++ {
		_ = r.Append(line(i, big))
	}
	before, _, _ := r.Page(nil, nil, 1000, 0)
	if err := r.SetCap(2048); err != nil {
		t.Fatal(err)
	}
	after, gaps, _ := r.Page(nil, nil, 1000, 0)
	if len(after) >= len(before) || len(after) == 0 {
		t.Fatalf("before %d after %d", len(before), len(after))
	}
	if len(gaps) == 0 || gaps[0].Reason != "CAP_REDUCED" {
		t.Fatalf("gaps %+v", gaps)
	}
}

func TestSetCapZeroDeletesEverything(t *testing.T) {
	r, st := open(t, 1<<20)
	fill(t, r, 5)
	_ = r.MarkGap(msg.LogGap{From: at(1), To: at(2), Reason: "OVERSIZE"})
	if err := r.SetCap(0); err != nil {
		t.Fatal(err)
	}
	ls, gaps, _ := r.Page(nil, nil, 100, 0)
	if len(ls) != 0 || len(gaps) != 0 {
		t.Fatalf("left %d lines %d gaps", len(ls), len(gaps))
	}
	if _, ok := r.LastTimestamp(); ok {
		t.Fatal("timestamp survived")
	}
	r2, err := Open(st, "p1", 0)
	if err != nil {
		t.Fatal(err)
	}
	if ls, gaps, _ := r2.Page(nil, nil, 100, 0); len(ls)+len(gaps) != 0 {
		t.Fatal("reopen resurrected data")
	}
	_ = r2.Append(line(9, "off"))
	if ls, _, _ := r2.Page(nil, nil, 100, 0); len(ls) != 0 {
		t.Fatal("cap 0 must not collect")
	}
}

func TestSurvivesReopen(t *testing.T) {
	r, st := open(t, 1<<20)
	fill(t, r, 5)
	_ = r.MarkGap(msg.LogGap{From: at(2), To: at(3), Reason: "COLLECTION_PAUSED"})
	r2, err := Open(st, "p1", 0) // no explicit cap: persisted cap kept
	if err != nil {
		t.Fatal(err)
	}
	ls, gaps, _ := r2.Page(nil, nil, 100, 0)
	if len(ls) != 5 || len(gaps) != 1 {
		t.Fatalf("lines %d gaps %d", len(ls), len(gaps))
	}
	if ts, ok := r2.LastTimestamp(); !ok || !ts.Equal(at(5)) {
		t.Fatalf("last %v %v", ts, ok)
	}
	if err := r2.Append(line(6, "after")); err != nil {
		t.Fatal(err)
	}
	if ls, _, _ := r2.Page(nil, nil, 100, 0); len(ls) != 6 {
		t.Fatalf("lines %d", len(ls))
	}
}

func TestRejectsUnsafePlacementID(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	for _, id := range []string{"", ".", "..", "../x", "a/b", "a b"} {
		if _, err := Open(st, id, 1024); err == nil {
			t.Fatalf("accepted %q", id)
		}
	}
}

func TestLineSplitAcrossSegmentBoundaryIsNeverReturnedHalf(t *testing.T) {
	r, st := open(t, 4096)
	for i := 1; i <= 60; i++ {
		_ = r.Append(line(i, fmt.Sprintf("%03d-%s-END", i, strings.Repeat("z", 100))))
	}
	// Tear the tail of the newest segment as a crash mid-append would.
	files, _ := filepath.Glob(filepath.Join(st.Path("logs/p1"), "*.seg"))
	last := files[len(files)-1]
	f, _ := os.OpenFile(last, os.O_APPEND|os.O_WRONLY, 0o600)
	_, _ = f.WriteString(`{"t":"2026-10-20T13:00:00Z","s":"stdout","x":"aGFsZi`)
	_ = f.Close()
	r2, err := Open(st, "p1", 0)
	if err != nil {
		t.Fatal(err)
	}
	ls, _, _ := r2.Page(nil, nil, 1000, 0)
	for _, l := range ls {
		if !strings.HasSuffix(string(l.Text), "-END") {
			t.Fatalf("partial line returned: %q", l.Text)
		}
	}
	if err := r2.Append(line(99, "next-END")); err != nil {
		t.Fatal(err)
	}
	ls, _, _ = r2.Page(nil, nil, 1000, 0)
	if string(ls[len(ls)-1].Text) != "next-END" {
		t.Fatal("append after torn tail lost")
	}
}

func TestMultibyteTextRoundTripsByteExact(t *testing.T) {
	r, _ := open(t, 1<<20)
	in := "héllo \xe2\x82\xac 日本語 \U0001F600 end"
	_ = r.Append(line(1, in))
	_ = r.Append(msg.LogLine{TS: at(2), Stream: "stderr", Text: []byte{0xff, 0xfe, 'a', '\t'}})
	ls, _, _ := r.Page(nil, nil, 10, 0)
	if string(ls[0].Text) != in || ls[0].Truncated {
		t.Fatalf("got %q", ls[0].Text)
	}
	if !bytes.Equal(ls[1].Text, []byte{0xff, 0xfe, 'a', '\t'}) || ls[1].Stream != "stderr" {
		t.Fatalf("raw bytes changed: %q", ls[1].Text)
	}
	// Returned-side truncation must land on a rune boundary.
	r2, _ := open(t, 1<<20)
	_ = r2.Append(line(1, strings.Repeat("日", 20)))
	got, _, _ := r2.Page(nil, nil, 1, 10)
	if len(got) != 1 || !got[0].Truncated || !utf8.Valid(got[0].Text) || len(got[0].Text) > 10 {
		t.Fatalf("bad truncation %q trunc=%v", got[0].Text, got[0].Truncated)
	}
}

func TestOversizeLineIsTruncatedWithMarkerNotDropped(t *testing.T) {
	r, _ := open(t, 4096)
	huge := strings.Repeat("日", 5000) // far larger than a segment
	if err := r.Append(line(1, huge)); err != nil {
		t.Fatal(err)
	}
	if err := r.Append(line(2, "after")); err != nil {
		t.Fatal(err)
	}
	ls, _, _ := r.Page(nil, nil, 10, 0)
	if len(ls) != 2 || !ls[0].Truncated || len(ls[0].Text) == 0 || !utf8.Valid(ls[0].Text) {
		t.Fatalf("stored oversize wrong: %d lines trunc=%v len=%d", len(ls), ls[0].Truncated, len(ls[0].Text))
	}
	if string(ls[1].Text) != "after" || ls[1].Truncated {
		t.Fatal("ring wedged")
	}
	// A line over the per-message budget is returned truncated, not dropped.
	r2, _ := open(t, 1<<20)
	_ = r2.Append(line(1, strings.Repeat("a", 500)))
	got, _, _ := r2.Page(nil, nil, 10, 100)
	if len(got) != 1 || !got[0].Truncated || len(got[0].Text) != 100 {
		t.Fatalf("budget truncation: %d", len(got))
	}
}

func TestExportFromResumesBySeqWithoutRepeats(t *testing.T) {
	r, _ := open(t, 1<<20)
	fill(t, r, 20)
	var all []string
	var seq uint64
	for n := 0; ; n++ {
		if n > 50 {
			t.Fatal("no progress")
		}
		ls, _, next, done, err := r.ExportFrom(seq, 40) // ~4 lines per chunk
		if err != nil {
			t.Fatal(err)
		}
		if len(ls) == 0 && !done {
			t.Fatal("empty chunk, not done")
		}
		all = append(all, texts(ls)...)
		seq = next
		if done {
			break
		}
	}
	if len(all) != 20 {
		t.Fatalf("got %d lines", len(all))
	}
	for i, s := range all {
		if s != fmt.Sprintf("line-%03d", i+1) {
			t.Fatalf("order/repeat at %d: %s", i, s)
		}
	}
	// Resuming at the end yields nothing and done.
	ls, _, next, done, _ := r.ExportFrom(seq, 40)
	if len(ls) != 0 || !done || next != seq {
		t.Fatal("end of ring")
	}
	// New lines appended later are picked up from the same cursor.
	_ = r.Append(line(21, "late"))
	ls, _, _, done, _ = r.ExportFrom(seq, 1000)
	if len(ls) != 1 || string(ls[0].Text) != "late" || !done {
		t.Fatal("resume after append")
	}
}

func TestConcurrentAppendAndRead(t *testing.T) {
	r, _ := open(t, 8192)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 300; i++ {
			_ = r.Append(line(i, strings.Repeat("c", 50)))
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			_, _, _ = r.Page(nil, nil, 20, 1000)
			_, _, _, _, _ = r.ExportFrom(0, 1000)
			r.LastTimestamp()
		}
	}()
	wg.Wait()
}
