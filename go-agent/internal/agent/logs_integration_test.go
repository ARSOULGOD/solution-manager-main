//go:build integration

package agent

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// logFeed is a scripted container log: FollowLogs replays every pushed line
// whose timestamp is at or after `since` (Docker's Since), then waits for
// more. No Docker is involved.
type logFeed struct {
	mu        sync.Mutex
	cond      *sync.Cond
	lines     []msg.LogLine
	delivered int // lines handed to a collector, high-water mark
}

func newLogFeed() *logFeed {
	f := &logFeed{}
	f.cond = sync.NewCond(&f.mu)
	return f
}

var feedEpoch = time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)

// push appends lines of the given text, one second apart in feed time.
func (f *logFeed) push(texts ...string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, tx := range texts {
		f.lines = append(f.lines, msg.LogLine{TS: feedEpoch.Add(time.Duration(len(f.lines)) * time.Second), Stream: "stdout", Text: []byte(tx)})
	}
	f.cond.Broadcast()
}

func (f *logFeed) size() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.lines)
}

// waitDelivered blocks until n lines have been received by the collector.
// The collector stores a line before it receives the next, so when n lines
// have been received the first n-1 are stored: callers end the feed with a
// barrier line and expect only the lines before it.
func (f *logFeed) waitDelivered(n int) {
	deadline := time.Now().Add(10 * time.Second)
	f.mu.Lock()
	defer f.mu.Unlock()
	for f.delivered < n && time.Now().Before(deadline) {
		stop := time.AfterFunc(50*time.Millisecond, f.cond.Broadcast)
		f.cond.Wait()
		stop.Stop()
	}
}

func (f *logFeed) follow(ctx context.Context, since time.Time) (<-chan msg.LogLine, <-chan error) {
	out, errs := make(chan msg.LogLine), make(chan error)
	stop := context.AfterFunc(ctx, func() { f.mu.Lock(); f.cond.Broadcast(); f.mu.Unlock() })
	go func() {
		defer stop()
		defer close(errs)
		defer close(out)
		idx := 0
		f.mu.Lock()
		for idx < len(f.lines) && !since.IsZero() && f.lines[idx].TS.Before(since) {
			idx++
		}
		f.mu.Unlock()
		for {
			f.mu.Lock()
			for idx >= len(f.lines) && ctx.Err() == nil {
				f.cond.Wait()
			}
			if ctx.Err() != nil {
				f.mu.Unlock()
				return
			}
			l := f.lines[idx]
			f.mu.Unlock()
			select {
			case out <- l:
			case <-ctx.Done():
				return
			}
			idx++
			f.mu.Lock()
			f.delivered = max(f.delivered, idx)
			f.cond.Broadcast()
			f.mu.Unlock()
		}
	}()
	return out, errs
}

// logAdapter is the filesystem-only fakeAdapter that can also follow logs.
type logAdapter struct {
	*fakeAdapter
	feed *logFeed
}

func (a logAdapter) FollowLogs(ctx context.Context, _ runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error) {
	return a.feed.follow(ctx, since)
}

func startLogsAgent(t *testing.T, gw *fakegw.Server, feed *logFeed, roles Roles, defaultCap uint64) *agentHandle {
	t.Helper()
	ad := logAdapter{fakeAdapter: newFakeAdapter(t), feed: feed}
	return startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Adapter = ad
		bc.Roles = roles
		bc.Logs = &LogsConfig{DefaultMaxBytes: defaultCap}
	})
}

func logPages(gw *fakegw.Server) []msg.LogPage {
	var out []msg.LogPage
	for _, m := range gw.Received("LogPage") {
		out = append(out, m.(msg.LogPage))
	}
	return out
}

func logChunks(gw *fakegw.Server) []msg.LogChunk {
	var out []msg.LogChunk
	for _, m := range gw.Received("LogChunk") {
		out = append(out, m.(msg.LogChunk))
	}
	return out
}

func texts(ls []msg.LogLine) []string {
	var out []string
	for _, l := range ls {
		out = append(out, string(l.Text))
	}
	return out
}

func numbered(prefix string, n int) []string {
	var out []string
	for i := 1; i <= n; i++ {
		out = append(out, fmt.Sprintf("%s %03d %s", prefix, i, strings.Repeat("x", 60)))
	}
	return out
}

var logsOnly = Roles{Logs: true}

func TestPageEndToEndAgainstFakeGateway(t *testing.T) {
	feed := newLogFeed()
	want := numbered("page", 5)
	feed.push(want...)
	feed.push("barrier")
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Hold: func() { feed.waitDelivered(feed.size()) }},
		{LogRequest: &fakegw.LogRequestStep{Placement: "binding-1", RequestID: "req-page", Page: &fakegw.LogPageStep{Limit: 100}}},
		{ExpectLogPage: &fakegw.ExpectLogPageStep{RequestID: "req-page", MinLines: 5, Timeout: 10 * time.Second}},
	}
	gw := startGateway(t, sc)
	startLogsAgent(t, gw, feed, logsOnly, 1<<20)

	if !gw.WaitFor("LogPage", 1, 15*time.Second) {
		t.Fatal("no LogPage")
	}
	p := logPages(gw)[0]
	got := texts(p.Lines)
	if p.RequestID != "req-page" || p.Unavailable != msg.UnavailableNone || len(got) < 5 {
		t.Fatalf("page %+v", p)
	}
	for i, w := range want { // oldest to newest, as stored
		if got[i] != w {
			t.Fatalf("line %d = %q, want %q", i, got[i], w)
		}
	}
	if n := gw.LogsStreams(); n != 1 {
		t.Fatalf("Logs streams opened = %d, want 1", n)
	}
}

func TestExportEndToEndWithCreditsAndFinal(t *testing.T) {
	feed := newLogFeed()
	feed.push(numbered("export", 60)...)
	feed.push("barrier")
	sc := fakegw.DefaultScenario()
	sc.Limits.InitialCredits = 1
	sc.Limits.MaxBatchBytes = 2048
	sc.Policy.Logs.CreditsPerChunk = 0 // credit only on GrantLogCredit
	sc.Steps = []fakegw.Step{{Hold: func() { feed.waitDelivered(feed.size()) }}}
	gw := startGateway(t, sc)
	startLogsAgent(t, gw, feed, logsOnly, 1<<20)

	// The request may only be sent once the agent's Logs stream is open and the feed has landed.
	feed.waitDelivered(feed.size())
	waitLogsStream(t, gw)
	if err := gw.RequestLogs(msg.LogRequest{RequestID: "req-exp", PlacementRef: "binding-1", Export: &msg.LogExportQuery{}}); err != nil {
		t.Fatal(err)
	}
	if !gw.WaitFor("LogChunk", 1, 10*time.Second) {
		t.Fatal("no first chunk")
	}
	// initial_credits 1: one chunk, then the agent waits. A negative cannot be
	// awaited, so allow a bounded window (several round trips on loopback,
	// where a spurious second chunk would arrive within milliseconds); the
	// positive half below (credit -> the rest arrives) is what makes it meaningful.
	time.Sleep(300 * time.Millisecond)
	if n := len(logChunks(gw)); n != 1 {
		t.Fatalf("%d chunks before any credit, want exactly 1", n)
	}
	first := logChunks(gw)[0]
	if first.Final || len(first.Lines) == 0 || first.Seq == 0 {
		t.Fatalf("first chunk %+v", first)
	}

	if err := gw.GrantLogCredit("req-exp", 100); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		cs := logChunks(gw)
		if cs[len(cs)-1].Final {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	cs := logChunks(gw)
	if !cs[len(cs)-1].Final {
		t.Fatalf("no final chunk among %d", len(cs))
	}
	var all []string
	for i, c := range cs {
		if i > 0 && c.Seq <= cs[i-1].Seq {
			t.Fatalf("chunk seq %d after %d", c.Seq, cs[i-1].Seq)
		}
		if c.Final != (i == len(cs)-1) {
			t.Fatalf("Final on chunk %d of %d", i, len(cs))
		}
		all = append(all, texts(c.Lines)...)
	}
	want := numbered("export", 60)
	if len(all) < 60 {
		t.Fatalf("exported %d lines, want >= 60", len(all))
	}
	for i, w := range want {
		if all[i] != w {
			t.Fatalf("exported line %d = %q, want %q", i, all[i], w)
		}
	}

	// Resume: from_seq = the first chunk's seq continues right after that chunk.
	n := len(cs)
	if err := gw.RequestLogs(msg.LogRequest{RequestID: "req-resume", PlacementRef: "binding-1", Export: &msg.LogExportQuery{FromSeq: first.Seq}}); err != nil {
		t.Fatal(err)
	}
	// Connect and Logs are separate streams: a credit sent right after the
	// request can reach the agent first and is dropped (design 4.1). Wait for
	// the first chunk, which initial_credits pays for, then grant.
	waitChunkFor(t, gw, "req-resume")
	if err := gw.GrantLogCredit("req-resume", 100); err != nil {
		t.Fatal(err)
	}
	var resumed []string
	deadline = time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		resumed = resumed[:0]
		done := false
		for _, c := range logChunks(gw)[n:] {
			if c.RequestID == "req-resume" {
				resumed = append(resumed, texts(c.Lines)...)
				done = done || c.Final
			}
		}
		if done {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if skipped := len(all) - len(resumed); skipped != len(first.Lines) {
		t.Fatalf("resume skipped %d lines, want %d (the first chunk)", skipped, len(first.Lines))
	}
}

func TestRetentionAppliedLiveTrimsAndGaps(t *testing.T) {
	feed := newLogFeed()
	feed.push(numbered("early", 40)...)
	feed.push("barrier-1")
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Hold: func() { feed.waitDelivered(feed.size()) }},
		{LogRetention: &fakegw.LogRetentionStep{Placement: "binding-1", MaxBytes: 4096}},
		{LogRequest: &fakegw.LogRequestStep{Placement: "binding-1", RequestID: "req-small", Page: &fakegw.LogPageStep{Limit: 1000}}},
		{ExpectLogPage: &fakegw.ExpectLogPageStep{RequestID: "req-small", Timeout: 10 * time.Second}},
		{LogRetention: &fakegw.LogRetentionStep{Placement: "binding-1", MaxBytes: 0}},
		{LogRequest: &fakegw.LogRequestStep{Placement: "binding-1", RequestID: "req-off", Page: &fakegw.LogPageStep{Limit: 10}}},
		{ExpectLogPage: &fakegw.ExpectLogPageStep{RequestID: "req-off", Unavailable: "NOT_COLLECTING", Timeout: 10 * time.Second}},
	}
	gw := startGateway(t, sc)
	startLogsAgent(t, gw, feed, logsOnly, 1<<20)

	if !gw.WaitFor("LogPage", 1, 15*time.Second) {
		t.Fatal("no LogPage after retention")
	}
	small := logPages(gw)[0]
	if small.RequestID != "req-small" || small.Unavailable != msg.UnavailableNone {
		t.Fatalf("page %+v", small)
	}
	if n := len(small.Lines); n == 0 || n >= 41 {
		t.Fatalf("page after a 4 KiB cap holds %d lines, want fewer than the 41 collected and more than 0", n)
	}
	if last := string(small.Lines[len(small.Lines)-1].Text); last != "barrier-1" {
		t.Fatalf("newest line %q, want the barrier: a trim keeps the newest", last)
	}
	if len(small.Gaps) == 0 {
		t.Fatalf("trimmed ring reported no gap marker")
	}
	for _, g := range small.Gaps {
		if g.Reason != msg.GapCapReduced && g.Reason != msg.GapDroppedOldest {
			t.Fatalf("gap reason %q", g.Reason)
		}
	}

	// Cap 0: collection off, stored logs deleted, the page says so.
	if !gw.WaitFor("LogPage", 2, 15*time.Second) {
		t.Fatal("no LogPage after cap 0")
	}
	off := logPages(gw)[1]
	if off.RequestID != "req-off" || off.Unavailable != msg.UnavailableNotCollecting || len(off.Lines) != 0 {
		t.Fatalf("page after cap 0: %+v", off)
	}
}

func TestAgentWithoutLogsRoleNeverReceivesLogMessages(t *testing.T) {
	feed := newLogFeed()
	feed.push("never collected")
	sc := fakegw.DefaultScenario()
	sc.Limits.HeartbeatInterval = 100 * time.Millisecond
	// Forced: the fake sends these although the session did not advertise
	// logs, so the agent's own behaviour is under test, not the fake's gating.
	sc.Steps = []fakegw.Step{
		{LogRetention: &fakegw.LogRetentionStep{Placement: "binding-1", MaxBytes: 4096, Force: true}},
		{LogRequest: &fakegw.LogRequestStep{Placement: "binding-1", RequestID: "req-none", Page: &fakegw.LogPageStep{Limit: 10}, Force: true}},
	}
	gw := startGateway(t, sc)
	h := startLogsAgent(t, gw, feed, Roles{Control: true}, 1<<20)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)

	// The steps have run once both forced messages are in the transcript.
	waitOut(t, gw, "LogRetention")
	waitOut(t, gw, "LogRequest")
	// The control session stays healthy: heartbeats keep being answered after them.
	hb := gw.Count("Heartbeat")
	if !gw.WaitFor("Heartbeat", hb+2, 10*time.Second) {
		t.Fatal("no heartbeat after the forced log messages")
	}
	if h.loop.Snapshot().Session.Phase != PhaseActive {
		t.Fatalf("phase %s after log messages", h.loop.Snapshot().Session.Phase)
	}

	if n := gw.LogsStreams(); n != 0 {
		t.Fatalf("Logs stream opened %d times by an agent without the role", n)
	}
	if n := gw.Count("LogPage") + gw.Count("LogChunk"); n != 0 {
		t.Fatalf("%d log messages from an agent without the role", n)
	}
	if _, err := os.Stat(filepath.Join(h.dir, "logs")); !os.IsNotExist(err) {
		t.Fatalf("a log ring was created without the role: %v", err)
	}
	if _, err := os.Stat(filepath.Join(h.dir, "logs-dp-cap")); !os.IsNotExist(err) {
		t.Fatalf("a DP cap was persisted without the role: %v", err)
	}
	// And the fake's own gating, directly.
	if err := gw.RequestLogs(msg.LogRequest{RequestID: "r", PlacementRef: "binding-1", Page: &msg.LogPageQuery{Limit: 1}}); err == nil {
		t.Fatal("RequestLogs to a session that did not advertise logs must fail")
	}
	if err := gw.SetLogRetention("binding-1", 1); err == nil {
		t.Fatal("SetLogRetention to a session that did not advertise logs must fail")
	}
}

// waitOut waits for the gateway to have sent a message of kind.
func waitOut(t *testing.T, gw *fakegw.Server, kind string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		for _, e := range gw.Transcript() {
			if e.Dir == "out" && e.Kind == kind {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("gateway never sent %s", kind)
}

// waitLogsStream waits for the agent's Logs stream to be open.
func waitLogsStream(t *testing.T, gw *fakegw.Server) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if gw.LogsStreams() >= 1 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("the agent never opened its Logs stream")
}

func waitChunkFor(t *testing.T, gw *fakegw.Server, id string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		for _, c := range logChunks(gw) {
			if c.RequestID == id {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("no chunk for %s", id)
}
