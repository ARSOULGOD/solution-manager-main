package logfile

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// entryTS is the time of the page fixture's entry i: one entry a second.
func entryTS(i int) time.Time {
	return time.Date(2026, 10, 20, 4, 0, 0, 0, time.UTC).Add(time.Duration(i) * time.Second)
}

// entryLine is entry i in Spring Boot's default pattern; its text ends with its number.
func entryLine(i int) string {
	return entryTS(i).Format("2006-01-02T15:04:05.000Z07:00") + "  INFO 1 --- [main] a.B : entry " + strconv.Itoa(i)
}

// hundred is the page fixture: entries 1 to 100, one line each.
func hundred() string {
	var b strings.Builder
	for i := 1; i <= 100; i++ {
		b.WriteString(entryLine(i) + "\n")
	}
	return b.String()
}

// traced is the fixture with a three-line stack trace under every entry; each trace line names its entry.
func traced() string {
	var b strings.Builder
	for i := 1; i <= 100; i++ {
		b.WriteString(entryLine(i) + "\n")
		for k := 1; k <= 3; k++ {
			fmt.Fprintf(&b, "\tat com.x.E%d.run(E.java:%d)\n", i, k)
		}
	}
	return b.String()
}

// entryOf is the fixture entry a line belongs to, read from its text.
func entryOf(text string) int {
	if i := strings.Index(text, "entry "); i >= 0 {
		n, _ := strconv.Atoi(text[i+len("entry "):])
		return n
	}
	if i := strings.Index(text, ".E"); i >= 0 {
		if j := strings.Index(text[i:], ".run"); j > 0 {
			n, _ := strconv.Atoi(text[i+2 : i+j])
			return n
		}
	}
	return -1
}

// fixtureLines is content's lines, without their ends.
func fixtureLines(content string) []string {
	return strings.Split(strings.TrimSuffix(content, "\n"), "\n")
}

// logServer serves a log file at Path through http.ServeContent, which honours Range as Spring does, and records
// every Range header. body gives the file for the n-th request (0 first), so a test can change it between reads.
type logServer struct {
	mu   sync.Mutex
	body func(n int) string
	seen []string
}

func (s *logServer) set(body func(n int) string) {
	s.mu.Lock()
	s.body = body
	s.mu.Unlock()
}

func (s *logServer) ranges() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.seen...)
}

func fixed(content string) func(int) string { return func(int) string { return content } }

func pageFile(t *testing.T, body func(n int) string, opts ...Option) (*File, *logServer) {
	t.Helper()
	s := &logServer{body: body}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != Path {
			http.NotFound(w, r)
			return
		}
		s.mu.Lock()
		content := s.body(len(s.seen))
		s.seen = append(s.seen, r.Header.Get("Range"))
		s.mu.Unlock()
		http.ServeContent(w, r, "", time.Time{}, strings.NewReader(content))
	}))
	t.Cleanup(srv.Close)
	return New(srv.Listener.Addr().String(), append([]Option{WithNow(func() time.Time { return now })}, opts...)...), s
}

func page(t *testing.T, f *File, q msg.LogPageQuery, budget int) []msg.LogLine {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	lines, err := f.Page(ctx, q, budget)
	if err != nil {
		t.Fatalf("Page(%+v, %d): %v", q, budget, err)
	}
	return lines
}

func at(t time.Time) *time.Time { return &t }

// checkLines asserts got is exactly want, in order, each line whole and stamped with its fixture entry's time.
func checkLines(t *testing.T, name string, got []msg.LogLine, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s: %d lines, want %d: %q", name, len(got), len(want), pageTexts(got))
	}
	for i, l := range got {
		if string(l.Text) != want[i] || l.Stream != "file" || l.Truncated {
			t.Fatalf("%s: line %d is %q (stream %q, truncated %v), want %q", name, i, l.Text, l.Stream, l.Truncated, want[i])
		}
		if n := entryOf(want[i]); !l.TS.Equal(entryTS(n)) {
			t.Fatalf("%s: line %d %q at %v, want entry %d's %v", name, i, l.Text, l.TS, n, entryTS(n))
		}
	}
}

func pageTexts(lines []msg.LogLine) []string {
	out := make([]string, len(lines))
	for i, l := range lines {
		out[i] = string(l.Text)
	}
	return out
}

func entryRange(from, to int) []string {
	var out []string
	for i := from; i <= to; i++ {
		out = append(out, entryLine(i))
	}
	return out
}

func TestPageNewestLinesOldestFirst(t *testing.T) {
	for _, step := range []int{DefaultStep, 100} {
		f, _ := pageFile(t, fixed(hundred()), WithStep(step))
		checkLines(t, fmt.Sprintf("step %d", step), page(t, f, msg.LogPageQuery{Limit: 10}, 1<<20), entryRange(91, 100))
	}

	// One step that ends at the '\n' before entry 91: the limit is met in that read, so there is no second.
	content := hundred()
	f, s := pageFile(t, fixed(content), WithStep(len(content)-strings.Index(content, entryLine(91))+1))
	checkLines(t, "exact step", page(t, f, msg.LogPageQuery{Limit: 10}, 1<<20), entryRange(91, 100))
	if got := s.ranges(); len(got) != 1 {
		t.Fatalf("requests %q", got)
	}
}

func TestPageAfterReturnsOnlyNewerLines(t *testing.T) {
	for _, step := range []int{DefaultStep, 100} {
		f, _ := pageFile(t, fixed(hundred()), WithStep(step))
		lines := page(t, f, msg.LogPageQuery{After: at(entryTS(95))}, 1<<20)
		checkLines(t, fmt.Sprintf("step %d", step), lines, entryRange(96, 100))
	}
}

func TestPageAfterTheNewestLineIsEmptyAfterOneRead(t *testing.T) {
	f, s := pageFile(t, fixed(hundred()), WithStep(256)) // the file is many steps long
	if lines := page(t, f, msg.LogPageQuery{After: at(entryTS(100))}, 1<<20); len(lines) != 0 {
		t.Fatalf("lines %q", pageTexts(lines))
	}
	if got := s.ranges(); len(got) != 1 || got[0] != "bytes=-256" {
		t.Fatalf("requests %q, want one suffix range", got)
	}
}

func TestPageBeforeWalksBack(t *testing.T) {
	const step = 256
	content := hundred()
	f, s := pageFile(t, fixed(content), WithStep(step))

	lines := page(t, f, msg.LogPageQuery{Before: at(entryTS(20)), Limit: 5}, 1<<20)
	checkLines(t, "before 20", lines, entryRange(15, 19))

	// The first read is the last step; each later one is the step before it, and none spans more than a step.
	got := s.ranges()
	if len(got) < 2 || got[0] != "bytes=-256" {
		t.Fatalf("requests %q", got)
	}
	end := len(content) - step
	for i, h := range got[1:] {
		first := max(end-step, 0)
		if want := fmt.Sprintf("bytes=%d-%d", first, end-1); h != want {
			t.Fatalf("request %d is %q, want %q", i+1, h, want)
		}
		if end-first > step {
			t.Fatalf("request %d spans %d bytes", i+1, end-first)
		}
		end = first
	}
}

func TestPageJoinsALineSplitAcrossReads(t *testing.T) {
	crlf := strings.ReplaceAll(hundred(), "\n", "\r\n")
	for _, content := range []string{hundred(), crlf} {
		for _, step := range []int{1, 7, 16, 61} { // every step shorter than one line
			f, _ := pageFile(t, fixed(content), WithStep(step))
			lines := page(t, f, msg.LogPageQuery{Limit: 10}, 1<<20)
			checkLines(t, fmt.Sprintf("step %d, crlf %v", step, content == crlf), lines, entryRange(91, 100))
		}
	}
}

func TestPageDropsTheOrphanedHeadOfAnEntry(t *testing.T) {
	content := traced()
	all := fixtureLines(content) // entry i's start is all[(i-1)*4], its trace the three lines after
	cost := func(lines []string) int {
		n := 0
		for _, l := range lines {
			n += len(l)
		}
		return n
	}
	cases := []struct {
		name   string
		q      msg.LogPageQuery
		budget int
		want   []string
	}{
		// Entry 100, then entry 99's last two trace lines make six: the page is full inside entry 99's trace, whose
		// start is not read, so those two are dropped.
		{"limit 6", msg.LogPageQuery{Limit: 6}, 1 << 20, all[len(all)-4:]},
		// Entry 95's trace sits between 95 and 96, and goes with entry 95.
		{"after 95", msg.LogPageQuery{After: at(entryTS(95))}, 1 << 20, all[95*4:]},
		// Entry 20's trace goes with entry 20; entry 19 is on the page, and entry 18's trace fills it.
		{"before 20", msg.LogPageQuery{Before: at(entryTS(20)), Limit: 7}, 1 << 20, all[18*4 : 19*4]},
		// Room for entries 99 and 100 and one line of entry 98's trace: full there, and that line goes.
		{"budget", msg.LogPageQuery{}, cost(all[len(all)-9:]), all[len(all)-8:]},
		// Room for entry 98's whole trace but not its start: the trace goes with it.
		{"budget, start too long", msg.LogPageQuery{}, cost(all[len(all)-11:]) + 10, all[len(all)-8:]},
	}
	// Steps shorter than an entry with its trace, so reads end inside stack traces.
	for _, step := range []int{13, 100, DefaultStep} {
		f, _ := pageFile(t, fixed(content), WithStep(step))
		for _, c := range cases {
			name := fmt.Sprintf("step %d, %s", step, c.name)
			lines := page(t, f, c.q, c.budget)
			checkLines(t, name, lines, c.want)
			checkWholeEntries(t, name, lines)
		}
	}
}

// checkWholeEntries asserts every continuation line on a page has its entry start on the page too.
func checkWholeEntries(t *testing.T, name string, lines []msg.LogLine) {
	t.Helper()
	starts := map[string]bool{}
	for _, l := range lines {
		starts[string(l.Text)] = true
	}
	for _, l := range lines {
		if n := entryOf(string(l.Text)); !starts[entryLine(n)] {
			t.Fatalf("%s: %q without its entry start", name, l.Text)
		}
	}
}

func TestPageBackWithBeforeLosesNoLine(t *testing.T) {
	content := traced()
	for _, c := range []struct {
		name   string
		limit  uint32
		budget int
	}{{"limit 6", 6, 1 << 20}, {"budget 300", 0, 300}} {
		for _, step := range []int{1000, DefaultStep} { // each page walks back from the end: a page of steps each
			f, _ := pageFile(t, fixed(content), WithStep(step))
			seen := map[string]int{}
			q := msg.LogPageQuery{Limit: c.limit}
			for i := 0; ; i++ {
				if i > 400 {
					t.Fatalf("%s, step %d: paging back does not end", c.name, step)
				}
				lines := page(t, f, q, c.budget)
				if len(lines) == 0 {
					break
				}
				checkLines(t, c.name, lines, pageTexts(lines)) // each line at its entry's time
				for _, l := range lines {
					seen[string(l.Text)]++
				}
				q.Before = at(lines[0].TS)
			}
			for _, l := range fixtureLines(content) {
				if seen[l] != 1 {
					t.Fatalf("%s, step %d: %q seen %d times", c.name, step, l, seen[l])
				}
			}
		}
	}
}

func TestPageWithoutAnyEntryStartIsBoundedByThePage(t *testing.T) {
	// A non-default pattern: no line parses as an entry start. 22000 lines of 37 bytes, about 800 KB.
	var b strings.Builder
	for i := range 22000 {
		fmt.Fprintf(&b, "INFO something happened, line %06d\n", i)
	}
	content := b.String()
	all := fixtureLines(content)
	const budget, lineLen = 1000, 36
	fit := budget / lineLen // 27 lines; the 28th does not fit
	for _, step := range []int{DefaultStep, 256} {
		for name, q := range map[string]msg.LogPageQuery{
			"page":      {},
			"live poll": {After: at(now.Add(-time.Minute))},
		} {
			f, s := pageFile(t, fixed(content), WithStep(step))
			lines := page(t, f, q, budget)
			if got := pageTexts(lines); len(got) != fit || got[0] != all[len(all)-fit] || got[fit-1] != all[len(all)-1] {
				t.Fatalf("step %d, %s: %d lines, %q", step, name, len(got), got)
			}
			for _, l := range lines {
				if !l.TS.Equal(now) {
					t.Fatalf("step %d, %s: %q at %v", step, name, l.Text, l.TS)
				}
			}
			// The walk reads only until the page is full: its 27 lines and the 28th, which does not fit, plus the
			// '\n' before it, are 28*37+1 bytes from the end. That is one 64 KiB step, or five 256-byte steps; the
			// whole file would be more than 3000.
			if got, most := len(s.ranges()), (28*(lineLen+1)+1+step-1)/step; got > most {
				t.Fatalf("step %d, %s: %d requests, want at most %d", step, name, got, most)
			}
		}
	}
}

func TestPageBackFromAFetchTimePageEnds(t *testing.T) {
	// Paging back from a page stamped with the fetch time asks for lines before it, which a page that fills before
	// any entry start (each line again at the fetch time) does not have: the page is empty, not the same lines again.
	var b strings.Builder
	for i := range 2000 {
		fmt.Fprintf(&b, "INFO something happened, line %06d\n", i)
	}
	f, s := pageFile(t, fixed(b.String()))
	if lines := page(t, f, msg.LogPageQuery{Before: at(now)}, 1000); len(lines) != 0 {
		t.Fatalf("%d lines: %q", len(lines), pageTexts(lines))
	}
	if got := s.ranges(); len(got) != 1 {
		t.Fatalf("requests %q", got)
	}
}

func TestPageFullInsideALongTraceReadsNoFurther(t *testing.T) {
	// Entry 2 and two lines of entry 1's 2000-line trace fill a 3-line page. The one read ends at the '\n' before the
	// first of those two lines, so a walk that stops as soon as the page is full makes no second request.
	var b strings.Builder
	b.WriteString(entryLine(1) + "\n")
	for k := 1; k <= 2000; k++ {
		fmt.Fprintf(&b, "\tat com.x.E1.run(E.java:%d)\n", k)
	}
	b.WriteString(entryLine(2) + "\n")
	content := b.String()
	all := fixtureLines(content)
	step := len(strings.Join(all[len(all)-3:], "\n")) + 2 // the 3 lines, their 3 '\n's, and the '\n' before them
	f, s := pageFile(t, fixed(content), WithStep(step))
	checkLines(t, "full", page(t, f, msg.LogPageQuery{Limit: 3}, 1<<20), all[len(all)-1:])
	if got := s.ranges(); len(got) != 1 {
		t.Fatalf("%d requests", len(got))
	}
}

func TestPageEntryLargerThanAPage(t *testing.T) {
	var b strings.Builder
	b.WriteString(entryLine(1) + "\n")
	for k := 1; k <= 20; k++ {
		fmt.Fprintf(&b, "\tat com.x.E1.run(E.java:%d)\n", k)
	}
	b.WriteString(entryLine(2) + "\n")
	content := b.String()
	all := fixtureLines(content) // entry 1's trace is all[1:21]
	budget := 0
	for _, l := range all[16:21] {
		budget += len(l)
	}
	for _, step := range []int{DefaultStep, 13} {
		f, _ := pageFile(t, fixed(content), WithStep(step))
		// Entry 2 is passed over; entry 1 alone is more than the page, whose lines are then its newest trace lines.
		for name, c := range map[string]struct {
			q      msg.LogPageQuery
			budget int
		}{
			"limit":  {msg.LogPageQuery{Before: at(entryTS(2)), Limit: 5}, 1 << 20},
			"budget": {msg.LogPageQuery{Before: at(entryTS(2))}, budget},
		} {
			checkLines(t, fmt.Sprintf("step %d, %s", step, name), page(t, f, c.q, c.budget), all[16:21])
		}
	}
}

func TestPageCarriesNoMoreThanThePageHasRoomFor(t *testing.T) {
	// Short lines fill part of the page, then lines longer than the room left are read back over many steps.
	long := func(i int) string { return entryLine(i) + " " + strings.Repeat("x", 3000) + "\n" }
	content := long(1) + long(2) + entryLine(3) + "\n" + strings.Repeat("\tat com.x.E3.run(E.java:1)\n", 5)
	const budget, step = 1000, 100
	for name, q := range map[string]msg.LogPageQuery{
		"after kept lines": {},                       // entry 3 is kept, then entry 2 is read with little room left
		"on an empty page": {Before: at(entryTS(3))}, // entry 2 is read with the whole budget as room
	} {
		w := &walk{q: q, budget: budget, now: now, tail: true}
		most := 0
		for end := len(content); ; {
			first := max(end-step, 0)
			buf := []byte(content[first:end])
			done := w.read(buf, first == 0)
			clear(buf) // no line may keep a read's bytes, or every read would stay in memory
			// The text kept and held, and the carry with its spare capacity, stay within one page budget (and the
			// timestamp floor).
			if c := cap(w.head); c > w.keep() || w.cost+w.heldCost+c > budget+maxStamp+1 {
				t.Fatalf("%s: carry capacity %d, keep %d, cost %d+%d", name, c, w.keep(), w.cost, w.heldCost)
			}
			most = max(most, cap(w.head))
			if done || first == 0 {
				break
			}
			end = first
		}
		if most < 200 {
			t.Fatalf("%s: the carry never grew (%d), so nothing was tested", name, most)
		}
		slices.Reverse(w.kept)
		if name == "after kept lines" {
			checkLines(t, name, w.kept, fixtureLines(content)[2:])
		}
		if name == "on an empty page" && (len(w.kept) != 1 || !w.kept[0].Truncated || string(w.kept[0].Text) != long(2)[:budget]) {
			t.Fatalf("%s: %+v", name, w.kept)
		}
	}
}

func TestPageAtTheFileStartWithoutAnEntryUsesTheFetchTime(t *testing.T) {
	content := "banner one\nbanner two\n" + entryLine(1) + "\n" + entryLine(2) + "\n"
	for _, step := range []int{DefaultStep, 5} {
		f, _ := pageFile(t, fixed(content), WithStep(step))
		lines := page(t, f, msg.LogPageQuery{}, 1<<20)
		if got := pageTexts(lines); len(got) != 4 || got[0] != "banner one" || got[1] != "banner two" {
			t.Fatalf("step %d: %q", step, got)
		}
		for i, want := range []time.Time{now, now, entryTS(1), entryTS(2)} {
			if !lines[i].TS.Equal(want) {
				t.Errorf("step %d: line %d at %v, want %v", step, i, lines[i].TS, want)
			}
		}
	}
}

func TestPageKeepsWithinTheTextBudget(t *testing.T) {
	const budget = 1000
	for _, step := range []int{DefaultStep, 100} {
		f, _ := pageFile(t, fixed(hundred()), WithStep(step))
		lines := page(t, f, msg.LogPageQuery{}, budget)
		total := 0
		for _, l := range lines {
			total += len(l.Text)
		}
		k := len(lines)
		if total > budget || k == 0 {
			t.Fatalf("step %d: %d lines, %d bytes of text, budget %d", step, k, total, budget)
		}
		checkLines(t, fmt.Sprintf("step %d", step), lines, entryRange(101-k, 100)) // the newest
		if total+len(entryLine(100-k)) <= budget {
			t.Fatalf("step %d: entry %d would still fit", step, 100-k)
		}
	}
}

func TestPageOnAnEmptyFile(t *testing.T) {
	goEmpty, _ := pageFile(t, fixed("")) // http.ServeContent answers a suffix range on an empty file 206 `bytes 0--1/0`
	files := map[string]*File{
		"http.ServeContent": goEmpty,
		"200, Content-Length 0": handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Length", "0") // http.ServeContent's answer to any other range on an empty file
			w.WriteHeader(http.StatusOK)
		}),
		"spring 416": handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Range", "bytes */0")
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		}),
	}
	for name, f := range files {
		lines, err := f.Page(context.Background(), msg.LogPageQuery{Limit: 10}, 1000)
		if err != nil || len(lines) != 0 {
			t.Errorf("%s: %q, %v", name, pageTexts(lines), err)
		}
	}
}

func TestPageLeavesAnUnterminatedTailForTheNextPoll(t *testing.T) {
	for _, step := range []int{DefaultStep, 16} {
		// A continuation line being written: when it ends, it goes with entry 100.
		f, s := pageFile(t, fixed(hundred()+"partial"), WithStep(step))
		checkLines(t, "before the end", page(t, f, msg.LogPageQuery{Limit: 10}, 1<<20), entryRange(91, 100))
		s.set(fixed(hundred() + "partial\n"))
		lines := page(t, f, msg.LogPageQuery{Limit: 10}, 1<<20)
		if got := pageTexts(lines); len(got) != 10 || got[9] != "partial" || !lines[9].TS.Equal(entryTS(100)) {
			t.Fatalf("step %d, after the end: %+v", step, lines)
		}
		checkLines(t, "after the end", lines[:9], entryRange(92, 100))

		// An entry being written, and the console's live poll for what is newer than its newest line.
		g, s := pageFile(t, fixed(hundred()+entryLine(101)), WithStep(step))
		poll := msg.LogPageQuery{After: at(entryTS(100))}
		if lines := page(t, g, poll, 1<<20); len(lines) != 0 {
			t.Fatalf("step %d, poll before the end: %q", step, pageTexts(lines))
		}
		s.set(fixed(hundred() + entryLine(101) + "\n"))
		checkLines(t, "poll after the end", page(t, g, poll, 1<<20), entryRange(101, 101))
	}
}

func TestPageEndsWhenTheFileShrinks(t *testing.T) {
	const step = 256
	content := hundred()
	// The first read holds the lines that start inside it after its first '\n'; the segment before that '\n' is not
	// known to be a whole line until the read before it, which the shrink discards.
	p := len(content) - step
	var want []string
	off := 0
	for _, l := range fixtureLines(content) {
		if off > p {
			want = append(want, l)
		}
		off += len(l) + 1
	}
	if len(want) < 2 {
		t.Fatalf("fixture: %d lines in the first read", len(want))
	}

	for name, later := range map[string]string{
		"replaced, short":    content[:100],             // the next range is past its end: 416
		"truncated a little": content[:len(content)-10], // the next range is inside it: 206, but a smaller total
	} {
		f, s := pageFile(t, func(n int) string {
			if n == 0 {
				return content
			}
			return later
		}, WithStep(step))
		checkLines(t, name, page(t, f, msg.LogPageQuery{}, 1<<20), want)
		if got := s.ranges(); len(got) != 2 {
			t.Fatalf("%s: requests %q", name, got)
		}
	}

	// A shrink while lines are held: entry 100 is kept, and entry 99's last two trace lines, held for its start in the
	// read before, are not returned.
	tr := traced()
	lines := fixtureLines(tr)
	held := len(lines[len(lines)-6]) + 1 + len(lines[len(lines)-5]) + 1
	entry100 := len(strings.Join(lines[len(lines)-4:], "\n")) + 1
	f, s := pageFile(t, func(n int) string {
		if n == 0 {
			return tr
		}
		return tr[:100]
	}, WithStep(entry100+held+5)) // the first read starts 5 bytes before the end of entry 99's first trace line
	checkLines(t, "shrink while held", page(t, f, msg.LogPageQuery{}, 1<<20), lines[len(lines)-4:])
	if got := s.ranges(); len(got) != 2 {
		t.Fatalf("shrink while held: requests %q", got)
	}

	// A file that grows under the walk has not shrunk: the page is what the first read saw the end of.
	f, _ = pageFile(t, func(n int) string {
		if n == 0 {
			return content
		}
		return content + entryLine(101) + "\n"
	}, WithStep(step))
	checkLines(t, "grown", page(t, f, msg.LogPageQuery{Limit: 10}, 1<<20), entryRange(91, 100))
}

func TestPageCutsALineLongerThanTheBudget(t *testing.T) {
	const budget = 1000
	long := entryLine(101) + " " + strings.Repeat("x", 5000)
	exact := entryLine(101) + " " + strings.Repeat("y", budget-len(entryLine(101))-1)
	for _, step := range []int{DefaultStep, 7, 100, budget, budget + 1} {
		f, _ := pageFile(t, fixed(hundred()+long+"\n"), WithStep(step))
		lines := page(t, f, msg.LogPageQuery{}, budget)
		if len(lines) != 1 || !lines[0].Truncated || string(lines[0].Text) != long[:budget] || !lines[0].TS.Equal(entryTS(101)) {
			t.Fatalf("step %d: %+v", step, lines)
		}
		// Exactly the budget, then "\r\n": whole, not cut.
		g, _ := pageFile(t, fixed(hundred()+exact+"\r\n"), WithStep(step))
		lines = page(t, g, msg.LogPageQuery{}, budget)
		if len(lines) != 1 || lines[0].Truncated || string(lines[0].Text) != exact {
			t.Fatalf("step %d, exact: %+v", step, lines)
		}
		// Exactly the budget, then a '\r' that does not end the line: cut.
		h, _ := pageFile(t, fixed(hundred()+exact+"\r"+strings.Repeat("z", 100)+"\n"), WithStep(step))
		lines = page(t, h, msg.LogPageQuery{}, budget)
		if len(lines) != 1 || !lines[0].Truncated || string(lines[0].Text) != exact {
			t.Fatalf("step %d, inner \\r: %+v", step, lines)
		}
	}

	// A budget shorter than a timestamp still finds the entry start of a line split across reads.
	f, _ := pageFile(t, fixed(hundred()), WithStep(7))
	lines := page(t, f, msg.LogPageQuery{}, 20)
	if len(lines) != 1 || !lines[0].Truncated || string(lines[0].Text) != entryLine(100)[:20] || !lines[0].TS.Equal(entryTS(100)) {
		t.Fatalf("budget 20: %+v", lines)
	}
}

func TestPageHasNoHoleWhenATraceLineDoesNotFit(t *testing.T) {
	start := func(i int) string { return entryTS(i).Format("2006-01-02T15:04:05.000Z07:00") + " one" } // 28 bytes
	trace := strings.Repeat("z", 5) + "\n" + strings.Repeat("x", 50) + "\n" + strings.Repeat("y", 10) + "\n"
	for _, step := range []int{DefaultStep, 9} {
		// The x line does not fit beside the y line. The z line and the entry start would, but the page cannot skip
		// the x line, so the page is the y line alone. No entry start has been read: it takes the fetch time.
		f, _ := pageFile(t, fixed(start(1)+"\n"+trace), WithStep(step))
		lines := page(t, f, msg.LogPageQuery{}, 40)
		if len(lines) != 1 || string(lines[0].Text) != strings.Repeat("y", 10) || !lines[0].TS.Equal(now) {
			t.Fatalf("step %d: %+v", step, lines)
		}
		// Entry 2 passed over is an entry start read: the walk goes back to entry 1's start for its time.
		g, _ := pageFile(t, fixed(start(1)+"\n"+trace+start(2)+"\n"), WithStep(step))
		lines = page(t, g, msg.LogPageQuery{Before: at(entryTS(2))}, 40)
		if len(lines) != 1 || string(lines[0].Text) != strings.Repeat("y", 10) || !lines[0].TS.Equal(entryTS(1)) {
			t.Fatalf("step %d, before 2: %+v", step, lines)
		}
	}
}

func TestPageCountsAnEmptyLineAgainstTheBudget(t *testing.T) {
	content := entryLine(1) + "\n" + strings.Repeat("\n", 5000)
	for _, step := range []int{DefaultStep, 64} {
		f, _ := pageFile(t, fixed(content), WithStep(step))
		lines := page(t, f, msg.LogPageQuery{}, 100)
		if len(lines) != 100 {
			t.Fatalf("step %d: %d lines for a 100-byte budget", step, len(lines))
		}
		for _, l := range lines {
			// The page fills before entry 1's start is read, so they take the fetch time.
			if len(l.Text) != 0 || !l.TS.Equal(now) {
				t.Fatalf("step %d: %q at %v", step, l.Text, l.TS)
			}
		}
	}
}

// wrongSuffix serves a 100-byte file of lines, answering the suffix range (`bytes=-…`) with Content-Range cr and the
// file's bytes [a, b), and any other range through http.ServeContent.
func wrongSuffix(cr string, a, b int) http.HandlerFunc {
	content := strings.Repeat("abcdefghi\n", 10)
	return func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Header.Get("Range"), "bytes=-") {
			http.ServeContent(w, r, "", time.Time{}, strings.NewReader(content))
			return
		}
		w.Header().Set("Content-Range", cr)
		w.WriteHeader(http.StatusPartialContent)
		w.Write([]byte(content[a:b]))
	}
}

func TestPageUnavailableAnswers(t *testing.T) {
	const secret = "s3cr3t-token-in-a-log-line"
	answers := map[string]http.HandlerFunc{
		"404": func(w http.ResponseWriter, r *http.Request) { http.NotFound(w, r) },
		"500": func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte(secret))
		},
		"whole file 200": func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(secret + "\n"))
		},
		"416 with a size": func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Range", "bytes */300")
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		},
		// The suffix answered with the wrong bytes; every later range is answered right, so only the suffix can fail.
		"suffix not at the end": wrongSuffix("bytes 0-19/100", 0, 20),
		"suffix too short":      wrongSuffix("bytes 90-99/100", 90, 100),
		"bad Content-Range": func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Range", "bytes "+secret+"/100")
			w.WriteHeader(http.StatusPartialContent)
			w.Write([]byte(secret))
		},
	}
	for name, h := range answers {
		f := handlerFile(t, h, WithStep(20))
		lines, err := f.Page(context.Background(), msg.LogPageQuery{}, 1000)
		if !errors.Is(err, ErrUnavailable) || lines != nil || strings.Contains(err.Error(), secret) {
			t.Errorf("%s: %q, %v", name, pageTexts(lines), err)
		}
	}

	// A later read that fails fails the page: a part of it is not what was asked for.
	content := hundred()
	var mu sync.Mutex
	n := 0
	f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		n++
		first := n == 1
		mu.Unlock()
		if !first {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		http.ServeContent(w, r, "", time.Time{}, strings.NewReader(content))
	}, WithStep(256))
	if lines, err := f.Page(context.Background(), msg.LogPageQuery{}, 1<<20); !errors.Is(err, ErrUnavailable) || lines != nil {
		t.Fatalf("later 500: %d lines, %v", len(lines), err)
	}
}

func TestPageStopsWhenTheContextEnds(t *testing.T) {
	content := hundred()
	var mu sync.Mutex
	n := 0
	f := handlerFile(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		n++
		first := n == 1
		mu.Unlock()
		if !first {
			<-r.Context().Done() // the walk's second read never answers
			return
		}
		http.ServeContent(w, r, "", time.Time{}, strings.NewReader(content))
	}, WithStep(256))
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	lines, err := f.Page(ctx, msg.LogPageQuery{}, 1<<20)
	if !errors.Is(err, ErrUnavailable) || !errors.Is(err, context.DeadlineExceeded) || lines != nil {
		t.Fatalf("%d lines, %v", len(lines), err)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("took %v", d)
	}
}
