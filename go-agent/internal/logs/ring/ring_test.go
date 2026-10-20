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

func diskSegBytes(t *testing.T, st *store.Store) int64 {
	t.Helper()
	files, _ := filepath.Glob(filepath.Join(st.Path("logs/p1"), "*.seg"))
	var n int64
	for _, f := range files {
		fi, err := os.Stat(f)
		if err != nil {
			t.Fatal(err)
		}
		n += fi.Size()
	}
	return n
}

func TestOpenWithSmallerCapTrimsImmediatelyAndRecordsGap(t *testing.T) {
	r, st := open(t, 1<<20)
	big := strings.Repeat("y", 200)
	for i := 1; i <= 100; i++ {
		_ = r.Append(line(i, big))
	}
	_ = r.Close()
	r2, err := Open(st, "p1", 2048)
	if err != nil {
		t.Fatal(err)
	}
	if n := diskSegBytes(t, st); n > 2048 || n == 0 {
		t.Fatalf("disk %d over cap 2048 after Open", n)
	}
	ls, gaps, _ := r2.Page(nil, nil, 1000, 0)
	if len(ls) == 0 || len(ls) >= 100 || len(gaps) == 0 || gaps[0].Reason != "CAP_REDUCED" {
		t.Fatalf("lines %d gaps %+v", len(ls), gaps)
	}
}

func TestCapHoldsAfterEveryAppend(t *testing.T) {
	r, st := open(t, 4096)
	for i := 1; i <= 200; i++ {
		_ = r.Append(line(i, fmt.Sprintf("%04d%s", i, strings.Repeat("q", i%150))))
		if n := diskSegBytes(t, st); n > 4096 {
			t.Fatalf("append %d: disk %d over cap", i, n)
		}
	}
}

func TestExportFromDroppedCursorReportsGapAndProgresses(t *testing.T) {
	r, _ := open(t, 4096)
	big := strings.Repeat("e", 200)
	for i := 1; i <= 100; i++ {
		_ = r.Append(line(i, fmt.Sprintf("%04d%s", i, big)))
	}
	var seq uint64 = 2 // inside the dropped prefix
	sawGap := false
	prev := 0
	for n := 0; ; n++ {
		if n > 200 {
			t.Fatal("no progress")
		}
		ls, gaps, next, done, err := r.ExportFrom(seq, 700)
		if err != nil {
			t.Fatal(err)
		}
		for _, g := range gaps {
			if g.Reason == "DROPPED_OLDEST" {
				sawGap = true
			}
		}
		for _, l := range ls {
			var i int
			fmt.Sscanf(string(l.Text), "%04d", &i)
			if i <= prev {
				t.Fatalf("repeat/out of order %d after %d", i, prev)
			}
			prev = i
		}
		if !done && next <= seq {
			t.Fatalf("cursor stuck at %d", seq)
		}
		seq = next
		if done {
			break
		}
	}
	if !sawGap || prev != 100 {
		t.Fatalf("gap=%v last=%d", sawGap, prev)
	}
}

func TestExportAcrossRestartNeverRepeatsOrSkips(t *testing.T) {
	r, st := open(t, 1<<20)
	for i := 1; i <= 30; i++ {
		_ = r.Append(line(i, fmt.Sprintf("%04d", i)))
	}
	ls, _, seq, done, _ := r.ExportFrom(0, 20) // ~4 lines
	if done || len(ls) == 0 {
		t.Fatal("expected partial chunk")
	}
	got := texts(ls)
	_ = r.Close()
	r2, _ := Open(st, "p1", 0)
	_ = r2.Append(line(31, "0031"))
	for n := 0; !done; n++ {
		if n > 50 {
			t.Fatal("no progress")
		}
		ls, _, seq, done, _ = r2.ExportFrom(seq, 20)
		got = append(got, texts(ls)...)
	}
	if len(got) != 31 {
		t.Fatalf("got %d: %v", len(got), got)
	}
	for i, s := range got {
		if s != fmt.Sprintf("%04d", i+1) {
			t.Fatalf("at %d: %s", i, s)
		}
	}
}

func TestSeqNeverRegressesWhenAllSegmentsDroppedBeforeRestart(t *testing.T) {
	r, st := open(t, 1) // cap 1: every stored line is immediately over cap and dropped
	for i := 1; i <= 3; i++ {
		_ = r.Append(line(i, "gone"))
	}
	_, _, cursor, _, _ := r.ExportFrom(0, 100)
	_ = r.Close()
	r2, err := Open(st, "p1", 0)
	if err != nil {
		t.Fatal(err)
	}
	if err := r2.SetCap(1 << 20); err != nil {
		t.Fatal(err)
	}
	_ = r2.Append(line(10, "fresh"))
	ls, _, next, done, _ := r2.ExportFrom(cursor, 100)
	if len(ls) != 1 || string(ls[0].Text) != "fresh" || !done || next <= cursor {
		t.Fatalf("seq regressed: lines=%d next=%d cursor=%d", len(ls), next, cursor)
	}
}

func TestExportFirstLineLargerThanBudgetStillAdvances(t *testing.T) {
	r, _ := open(t, 1<<20)
	_ = r.Append(line(1, strings.Repeat("b", 500)))
	_ = r.Append(line(2, "small"))
	ls, _, next, done, _ := r.ExportFrom(0, 50)
	if len(ls) != 1 || !ls[0].Truncated || len(ls[0].Text) != 50 || next != 2 || done {
		t.Fatalf("lines=%d next=%d done=%v", len(ls), next, done)
	}
}

func TestResegmentOnShrinkKeepsNewestLinesContiguous(t *testing.T) {
	r, _ := open(t, 1<<20)
	for i := 1; i <= 100; i++ {
		_ = r.Append(line(i, fmt.Sprintf("%04d%s", i, strings.Repeat("r", 100))))
	}
	_ = r.SetCap(4096)
	ls, _, _, _, _ := r.ExportFrom(0, 0)
	if len(ls) < 5 {
		t.Fatalf("lost too much: %d", len(ls))
	}
	var first int
	fmt.Sscanf(string(ls[0].Text), "%04d", &first)
	for i, l := range ls {
		var n int
		fmt.Sscanf(string(l.Text), "%04d", &n)
		if n != first+i {
			t.Fatalf("hole at %d: %d", i, n)
		}
	}
	if first+len(ls)-1 != 100 {
		t.Fatal("newest lost")
	}
}

// A crash during resegment after the later pieces are written but before the
// original is replaced must leave every line readable exactly once.
func TestResegmentCrashBetweenPiecesLosesNothing(t *testing.T) {
	r, st := open(t, 1<<20)
	for i := 1; i <= 40; i++ {
		_ = r.Append(line(i, fmt.Sprintf("%04d", i)))
	}
	_ = r.Close()
	dir := st.Path("logs/p1")
	orig := filepath.Join(dir, segName(1))
	data, _ := os.ReadFile(orig)
	recs := bytes.SplitAfter(bytes.TrimSuffix(data, []byte("\n")), []byte("\n"))
	_ = recs
	all := bytes.Split(bytes.TrimSuffix(data, []byte("\n")), []byte("\n"))
	for p := 10; p < 40; p += 10 { // later pieces only; original still whole
		var buf []byte
		for _, l := range all[p : p+10] {
			buf = append(buf, l...)
			buf = append(buf, '\n')
		}
		if err := os.WriteFile(filepath.Join(dir, segName(uint64(p+1))), buf, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	r2, err := Open(st, "p1", 0)
	if err != nil {
		t.Fatal(err)
	}
	ls, _, next, done, _ := r2.ExportFrom(0, 0)
	if len(ls) != 40 || !done || next != 41 {
		t.Fatalf("lines=%d next=%d", len(ls), next)
	}
	for i, l := range ls {
		if string(l.Text) != fmt.Sprintf("%04d", i+1) {
			t.Fatalf("dup/skip at %d: %s", i, l.Text)
		}
	}
	_ = r2.Append(line(41, "0041"))
	ls, _, _, _, _ = r2.ExportFrom(41, 0)
	if len(ls) != 1 || string(ls[0].Text) != "0041" {
		t.Fatal("append after overlap repair misaligned")
	}
}

func TestPageEqualTimestampTies(t *testing.T) {
	r, _ := open(t, 1<<20)
	for i := 0; i < 6; i++ {
		_ = r.Append(msg.LogLine{TS: at(5), Stream: "stdout", Text: []byte(fmt.Sprintf("t%d", i))})
	}
	ls, _, _ := r.Page(nil, nil, 3, 0)
	if strings.Join(texts(ls), ",") != "t3,t4,t5" {
		t.Fatalf("got %v", texts(ls))
	}
	a := at(5)
	if ls, _, _ := r.Page(&a, nil, 10, 0); len(ls) != 0 {
		t.Fatal("exclusive after must drop ties")
	}
	if ls, _, _ := r.Page(nil, &a, 10, 0); len(ls) != 0 {
		t.Fatal("exclusive before must drop ties")
	}
}

func TestFileModes(t *testing.T) {
	r, st := open(t, 1<<20)
	fill(t, r, 3)
	_ = r.MarkGap(msg.LogGap{From: at(1), To: at(2), Reason: "OVERSIZE"})
	d, _ := os.Stat(st.Path("logs/p1"))
	if d.Mode().Perm() != 0o700 {
		t.Fatalf("dir %v", d.Mode().Perm())
	}
	entries, _ := os.ReadDir(st.Path("logs/p1"))
	for _, e := range entries {
		fi, _ := e.Info()
		if fi.Mode().Perm() != 0o600 {
			t.Fatalf("%s %v", e.Name(), fi.Mode().Perm())
		}
	}
}
