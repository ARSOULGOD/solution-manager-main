package logfile

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
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
	cases := []struct {
		name string
		q    msg.LogPageQuery
		want []string
	}{
		// The oldest lines of the page are entry 99's trace: their start is further back than the lines kept.
		{"limit 6", msg.LogPageQuery{Limit: 6}, all[len(all)-6:]},
		// Entry 95's trace sits between 95 and 96, and goes with entry 95.
		{"after 95", msg.LogPageQuery{After: at(entryTS(95))}, all[95*4:]},
		// Entry 20's trace goes with entry 20; the page ends with entry 19's.
		{"before 20", msg.LogPageQuery{Before: at(entryTS(20)), Limit: 7}, all[19*4-7 : 19*4]},
	}
	// Steps shorter than an entry with its trace, so reads end inside stack traces.
	for _, step := range []int{13, 100, DefaultStep} {
		f, _ := pageFile(t, fixed(content), WithStep(step))
		for _, c := range cases {
			lines := page(t, f, c.q, 1<<20)
			for _, l := range lines {
				if l.TS.IsZero() || l.TS.Equal(now) {
					t.Fatalf("step %d, %s: %q has no time of its own: %v", step, c.name, l.Text, l.TS)
				}
			}
			checkLines(t, fmt.Sprintf("step %d, %s", step, c.name), lines, c.want)
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

	// A file that grows under the walk has not shrunk: the page is what the first read saw the end of.
	f, _ := pageFile(t, func(n int) string {
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
	start := entryTS(1).Format("2006-01-02T15:04:05.000Z07:00") + " one" // 28 bytes
	content := start + "\n" + strings.Repeat("x", 50) + "\n" + strings.Repeat("y", 10) + "\n"
	for _, step := range []int{DefaultStep, 9} {
		f, _ := pageFile(t, fixed(content), WithStep(step))
		// The x line does not fit beside the y line; the entry start would, but the page cannot skip the x line.
		lines := page(t, f, msg.LogPageQuery{}, 40)
		if len(lines) != 1 || string(lines[0].Text) != strings.Repeat("y", 10) || !lines[0].TS.Equal(entryTS(1)) {
			t.Fatalf("step %d: %+v", step, lines)
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
			if len(l.Text) != 0 || !l.TS.Equal(entryTS(1)) {
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
