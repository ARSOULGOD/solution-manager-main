package collect

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/logs/ring"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 10, 1, 10, 0, 0, 0, time.UTC)

func ln(sec int, text string) msg.LogLine {
	return msg.LogLine{TS: t0.Add(time.Duration(sec) * time.Second), Stream: "stdout", Text: []byte(text)}
}

type session struct {
	lines []msg.LogLine
	err   error
}

type fakeSource struct {
	mu       sync.Mutex
	sessions []session
	places   []runtime.Placement // resolved in order; the last one repeats
	resolves int
	sinces   []time.Time
	ids      []string
	resolveE []error // per resolve call, nil entries mean success
}

func (f *fakeSource) ResolveByName(_ context.Context, _, bindingID string) (runtime.Placement, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	i := f.resolves
	f.resolves++
	if i < len(f.resolveE) && f.resolveE[i] != nil {
		return runtime.Placement{}, f.resolveE[i]
	}
	j := min(i, len(f.places)-1)
	return f.places[j], nil
}

func (f *fakeSource) FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error) {
	f.mu.Lock()
	f.sinces = append(f.sinces, since)
	f.ids = append(f.ids, p.ContainerID)
	var s *session
	if len(f.sessions) > 0 {
		s, f.sessions = &f.sessions[0], f.sessions[1:]
	}
	f.mu.Unlock()
	lines, errs := make(chan msg.LogLine), make(chan error, 1)
	go func() {
		if s == nil { // nothing scripted: an idle live stream
			<-ctx.Done()
			close(errs)
			close(lines)
			return
		}
		for _, l := range s.lines {
			select {
			case lines <- l:
			case <-ctx.Done():
				close(errs)
				close(lines)
				return
			}
		}
		if s.err != nil {
			errs <- s.err
		}
		close(errs)
		close(lines)
	}()
	return lines, errs
}

func openRing(t *testing.T, cap uint64) *ring.Ring {
	t.Helper()
	s, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	r, err := ring.Open(s, "p1", cap)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { r.Close() })
	return r
}

func page(t *testing.T, r *ring.Ring) ([]msg.LogLine, []msg.LogGap) {
	t.Helper()
	l, g, err := r.Page(nil, nil, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	return l, g
}

func texts(ls []msg.LogLine) []string {
	var out []string
	for _, l := range ls {
		out = append(out, string(l.Text))
	}
	return out
}

func noBackoff(int) time.Duration { return 0 }

// run starts Run and returns a stop that cancels it and waits.
func run(t *testing.T, c *Collector) (stop func() error) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- c.Run(ctx, "java1", "b1") }()
	return func() error {
		cancel()
		select {
		case err := <-done:
			return err
		case <-time.After(5 * time.Second):
			t.Fatal("Run did not stop on cancel")
			return nil
		}
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for i := 0; i < 500; i++ {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func count(r *ring.Ring) int { l, _, _ := r.Page(nil, nil, 0, 0); return len(l) }

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)) }

func TestFollowsAndAppendsLinesWithDockerTimestamps(t *testing.T) {
	r := openRing(t, 1<<20)
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}}, sessions: []session{{lines: []msg.LogLine{ln(1, "a"), ln(2, "b")}}}}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "two lines", func() bool { return count(r) == 2 })
	if err := stop(); !errors.Is(err, context.Canceled) {
		t.Fatalf("stop: %v", err)
	}
	got, _ := page(t, r)
	if !got[0].TS.Equal(t0.Add(time.Second)) || !got[1].TS.Equal(t0.Add(2*time.Second)) {
		t.Fatalf("timestamps are Docker's, not the agent clock: %+v", got)
	}
}

func TestResumesFromLastStoredTimestampAfterRestart(t *testing.T) {
	r := openRing(t, 1<<20)
	if err := r.Append(ln(5, "old")); err != nil {
		t.Fatal(err)
	}
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}}}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "a follow", func() bool { src.mu.Lock(); defer src.mu.Unlock(); return len(src.sinces) > 0 })
	stop()
	if !src.sinces[0].Equal(t0.Add(5 * time.Second)) {
		t.Fatalf("since = %v", src.sinces[0])
	}
}

func TestBoundaryLineIsNotDuplicatedOnResume(t *testing.T) {
	r := openRing(t, 1<<20)
	r.Append(ln(5, "a"))
	// Docker replays the boundary line "a" (since is inclusive), then a DISTINCT
	// line with the very same timestamp, a genuine repeat of "a", and a newer one.
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}},
		sessions: []session{{lines: []msg.LogLine{ln(5, "a"), ln(5, "b"), ln(5, "a"), ln(6, "c")}}}}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "four lines", func() bool { return count(r) == 4 })
	stop()
	l, _ := page(t, r)
	if got := texts(l); len(got) != 4 || got[0] != "a" || got[1] != "b" || got[2] != "a" || got[3] != "c" {
		t.Fatalf("got %v", got)
	}
}

func TestContainerRecreateReResolvesByNameAndRecordsGap(t *testing.T) {
	r := openRing(t, 1<<20)
	src := &fakeSource{
		places:   []runtime.Placement{{ContainerID: "c1"}, {ContainerID: "c2"}},
		sessions: []session{{lines: []msg.LogLine{ln(1, "before")}}, {lines: []msg.LogLine{ln(9, "after")}}},
	}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "both lines", func() bool { return count(r) == 2 })
	stop()
	if src.ids[0] != "c1" || src.ids[1] != "c2" {
		t.Fatalf("ids %v", src.ids)
	}
	_, gaps := page(t, r)
	if len(gaps) != 1 || gaps[0].Reason != msg.GapContainerUnreadable ||
		!gaps[0].From.Equal(t0.Add(time.Second)) || !gaps[0].To.Equal(t0.Add(9*time.Second)) {
		t.Fatalf("gaps %+v", gaps)
	}
}

func TestDockerErrorBacksOffAndRecordsContainerUnreadableGap(t *testing.T) {
	r := openRing(t, 1<<20)
	src := &fakeSource{
		places: []runtime.Placement{{ContainerID: "c1"}},
		sessions: []session{{lines: []msg.LogLine{ln(1, "x")}, err: errors.New("daemon down")},
			{err: errors.New("daemon down")}, {err: errors.New("daemon down")}, {lines: []msg.LogLine{ln(4, "y")}}},
	}
	var mu sync.Mutex
	var attempts []int
	bo := func(n int) time.Duration { mu.Lock(); attempts = append(attempts, n); mu.Unlock(); return 0 }
	stop := run(t, New(src, r, quiet(), bo))
	waitFor(t, "recovery", func() bool { return count(r) == 2 })
	stop()
	mu.Lock()
	defer mu.Unlock()
	if len(attempts) < 3 || attempts[0] != 0 || attempts[1] != 1 || attempts[2] != 2 {
		t.Fatalf("attempts grow across consecutive failures: %v", attempts)
	}
	_, gaps := page(t, r)
	if len(gaps) != 1 || gaps[0].Reason != msg.GapContainerUnreadable || !gaps[0].To.Equal(t0.Add(4*time.Second)) {
		t.Fatalf("gaps %+v", gaps)
	}
}

func TestCapZeroStopsCollection(t *testing.T) {
	r := openRing(t, 1<<20)
	if err := r.SetCap(0); err != nil {
		t.Fatal(err)
	}
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}}}
	if err := New(src, r, quiet(), noBackoff).Run(context.Background(), "java1", "b1"); err != nil {
		t.Fatalf("cap 0 is a clean stop: %v", err)
	}
	if len(src.sinces) != 0 {
		t.Fatal("must not follow while collection is off")
	}

	// turned off while following: stops on the next line instead of spinning
	r2 := openRing(t, 1<<20)
	src2 := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}}, sessions: []session{{lines: []msg.LogLine{ln(1, "a")}}}}
	done := make(chan error, 1)
	c := New(&capOffSource{fakeSource: src2, r: r2}, r2, quiet(), noBackoff)
	go func() { done <- c.Run(context.Background(), "java1", "b1") }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("did not stop when the cap went to zero mid-stream")
	}
}

// capOffSource zeroes the cap as the follow starts, before any line arrives.
type capOffSource struct {
	*fakeSource
	r *ring.Ring
}

func (c *capOffSource) FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error) {
	c.r.SetCap(0)
	return c.fakeSource.FollowLogs(ctx, p, since)
}

func TestMIOutputNeverReachesAgentSlog(t *testing.T) {
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	r := openRing(t, 1<<20)
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}},
		sessions: []session{{lines: []msg.LogLine{ln(1, "TOPSECRET-payload")}, err: errors.New("stream broke")},
			{lines: []msg.LogLine{ln(2, "TOPSECRET-again")}}},
		resolveE: []error{nil, errors.New("resolve broke")}}
	stop := run(t, New(src, r, log, noBackoff))
	waitFor(t, "lines", func() bool { return count(r) == 2 })
	stop()
	if bytes.Contains(buf.Bytes(), []byte("TOPSECRET")) {
		t.Fatalf("MI output reached slog: %s", buf.String())
	}
	if buf.Len() == 0 {
		t.Fatal("expected the collector to log the failures (sanity)")
	}
}

// Lines complete out of order across stdout/stderr, so the newest APPENDED
// timestamp is not the newest STORED one. Resume must use the maximum, or Docker
// replays a stored newer line that dedupe no longer recognises.
func TestOutOfOrderAppendResumesFromMaxTimestampWithoutDuplicates(t *testing.T) {
	r := openRing(t, 1<<20)
	r.Append(ln(2, "two"))
	r.Append(ln(1, "one")) // completed late
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}},
		sessions: []session{{lines: []msg.LogLine{ln(2, "two"), ln(3, "three")}}}}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "three lines", func() bool { return count(r) == 3 })
	time.Sleep(50 * time.Millisecond)
	stop()
	if !src.sinces[0].Equal(t0.Add(2 * time.Second)) {
		t.Fatalf("since = %v, want the max stored timestamp", src.sinces[0])
	}
	l, _ := page(t, r)
	got := texts(l) // storage order is completion order: two, one, three
	if len(got) != 3 || got[0] != "two" || got[1] != "one" || got[2] != "three" {
		t.Fatalf("got %v", got)
	}
}

// A live line that completes after a newer one must still be stored.
func TestLiveLateCompletingOlderLineIsKept(t *testing.T) {
	r := openRing(t, 1<<20)
	r.Append(ln(1, "seed"))
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}},
		sessions: []session{{lines: []msg.LogLine{ln(3, "late-err"), ln(2, "early-out")}}}}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "three lines", func() bool { return count(r) == 3 })
	stop()
}

// Replay follows Docker's file order, which is not timestamp order: a newer line
// can precede the replayed boundary line. The boundary must keep matching for
// the whole replay.
func TestBoundaryDedupeSurvivesNewerLineBeforeReplayedBoundary(t *testing.T) {
	r := openRing(t, 1<<20)
	r.Append(ln(1, "one"))
	r.Append(ln(2, "two"))
	src := &fakeSource{places: []runtime.Placement{{ContainerID: "c1"}},
		sessions: []session{{lines: []msg.LogLine{ln(3, "three"), ln(2, "two")}}}}
	stop := run(t, New(src, r, quiet(), noBackoff))
	waitFor(t, "three stored", func() bool { return count(r) >= 3 })
	time.Sleep(50 * time.Millisecond)
	stop()
	l, _ := page(t, r)
	if got := texts(l); len(got) != 3 || got[0] != "one" || got[1] != "two" || got[2] != "three" {
		t.Fatalf("got %v", got)
	}
}
