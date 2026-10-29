//go:build integration

package agent

import (
	"context"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/logs/logfile"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// miAgentAdapter is the filesystem-only fakeAdapter whose MI serves its log file at mi (logfileServer, in
// loop_test.go); with gone set, Resolve finds no container.
type miAgentAdapter struct {
	*fakeAdapter
	mi   *logfileServer
	gone atomic.Bool
}

func (a *miAgentAdapter) Resolve(ctx context.Context, b string) (runtime.Placement, error) {
	if a.gone.Load() {
		return runtime.Placement{}, fmt.Errorf("fake: %w", runtime.ErrContainerNotFound)
	}
	return a.fakeAdapter.Resolve(ctx, b)
}

func (a *miAgentAdapter) ActuatorAddr(context.Context, runtime.Placement) (string, error) {
	return a.mi.addr(), nil
}

// startLogsAgent runs an agent against gw whose MI log file is mi, and waits for its Logs stream when it has the
// logs role.
func startLogsAgent(t *testing.T, gw *fakegw.Server, mi *logfileServer, roles Roles) (*agentHandle, *miAgentAdapter) {
	t.Helper()
	ad := &miAgentAdapter{fakeAdapter: newFakeAdapter(t), mi: mi}
	h := startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Adapter = ad
		bc.Roles = roles
	})
	if roles.Logs {
		waitLogsStream(t, gw)
	}
	return h, ad
}

var logsOnly = Roles{Logs: true}

// creditOnRequest is the scenario for exports: one chunk before the first LogCredit, the rest only as the test grants.
func creditOnRequest() *fakegw.Scenario {
	sc := fakegw.DefaultScenario()
	sc.Limits.InitialCredits = 1
	sc.Limits.MaxBatchBytes = 2048
	sc.Policy.Logs.CreditsPerChunk = 0
	return sc
}

func requestLogs(t *testing.T, gw *fakegw.Server, r msg.LogRequest) {
	t.Helper()
	if r.PlacementRef == "" {
		r.PlacementRef = "binding-1"
	}
	if err := gw.RequestLogs(r); err != nil {
		t.Fatal(err)
	}
}

func askPage(t *testing.T, gw *fakegw.Server, id string, q msg.LogPageQuery) msg.LogPage {
	t.Helper()
	requestLogs(t, gw, msg.LogRequest{RequestID: id, Page: &q})
	return waitPage(t, gw, id)
}

func waitPage(t *testing.T, gw *fakegw.Server, id string) msg.LogPage {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		for _, m := range gw.Received("LogPage") {
			if p := m.(msg.LogPage); p.RequestID == id {
				return p
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("no LogPage for %s", id)
	panic("unreachable")
}

// chunksFor is every LogChunk received for id, in arrival order.
func chunksFor(gw *fakegw.Server, id string) []msg.LogChunk {
	var out []msg.LogChunk
	for _, m := range gw.Received("LogChunk") {
		if c := m.(msg.LogChunk); c.RequestID == id {
			out = append(out, c)
		}
	}
	return out
}

// waitChunks waits for n chunks of id.
func waitChunks(t *testing.T, gw *fakegw.Server, id string, n int) []msg.LogChunk {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cs := chunksFor(gw, id); len(cs) >= n {
			return cs
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("fewer than %d chunks for %s: %d", n, id, len(chunksFor(gw, id)))
	panic("unreachable")
}

// waitFinal waits for id's final chunk and returns every chunk of it.
func waitFinal(t *testing.T, gw *fakegw.Server, id string) []msg.LogChunk {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cs := chunksFor(gw, id); len(cs) > 0 && cs[len(cs)-1].Final {
			return cs
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("no final chunk for %s among %d", id, len(chunksFor(gw, id)))
	panic("unreachable")
}

// stillAt fails if id gains a chunk beyond n within a bounded window. A negative cannot be awaited: on loopback a
// spurious chunk would arrive within milliseconds, and each test's positive half (a credit brings the rest) is what
// makes the window meaningful.
func stillAt(t *testing.T, gw *fakegw.Server, id string, n int) {
	t.Helper()
	time.Sleep(300 * time.Millisecond)
	if got := len(chunksFor(gw, id)); got != n {
		t.Fatalf("%s: %d chunks, want %d", id, got, n)
	}
}

func texts(ls []msg.LogLine) []string {
	var out []string
	for _, l := range ls {
		out = append(out, string(l.Text))
	}
	return out
}

func chunkTexts(cs []msg.LogChunk) []string {
	var out []string
	for _, c := range cs {
		out = append(out, texts(c.Lines)...)
	}
	return out
}

func sameLines(t *testing.T, what string, got, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s: %d lines, want %d", what, len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("%s: line %d = %q, want %q", what, i, got[i], want[i])
		}
	}
}

func TestPageEndToEndAgainstFakeGateway(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 8)...)
	gw := startGateway(t, fakegw.DefaultScenario())
	startLogsAgent(t, gw, mi, logsOnly)

	p := askPage(t, gw, "req-page", msg.LogPageQuery{Limit: 5})
	if p.Unavailable != msg.UnavailableNone {
		t.Fatalf("page %+v", p)
	}
	sameLines(t, "page", texts(p.Lines), miLines(3, 8)) // the newest, oldest first
	for i, l := range p.Lines {
		if l.Stream != logfile.Stream || !l.TS.Equal(miEpoch.Add(time.Duration(3+i)*time.Second)) {
			t.Fatalf("line %d: stream %q ts %v", i, l.Stream, l.TS)
		}
	}
	if n := gw.LogsStreams(); n != 1 {
		t.Fatalf("Logs streams opened = %d, want 1", n)
	}
}

func TestLogsLivePollWithAfterGetsOnlyNewLines(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 5)...)
	gw := startGateway(t, fakegw.DefaultScenario())
	startLogsAgent(t, gw, mi, logsOnly)

	first := askPage(t, gw, "poll-1", msg.LogPageQuery{Limit: 100})
	sameLines(t, "first poll", texts(first.Lines), miLines(0, 5))
	mi.add(miLines(5, 8)...)
	after := first.Lines[len(first.Lines)-1].TS
	second := askPage(t, gw, "poll-2", msg.LogPageQuery{Limit: 100, After: &after})
	sameLines(t, "poll after the newest line shown", texts(second.Lines), miLines(5, 8))
}

func TestLogsPageBackWithBefore(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 20)...)
	gw := startGateway(t, fakegw.DefaultScenario())
	startLogsAgent(t, gw, mi, logsOnly)

	newest := askPage(t, gw, "back-1", msg.LogPageQuery{Limit: 5})
	sameLines(t, "newest page", texts(newest.Lines), miLines(15, 20))
	before := newest.Lines[0].TS
	older := askPage(t, gw, "back-2", msg.LogPageQuery{Limit: 5, Before: &before})
	sameLines(t, "the page before it", texts(older.Lines), miLines(10, 15))
}

// dataDirPaths is every path under dir, relative to it.
func dataDirPaths(t *testing.T, dir string) map[string]bool {
	t.Helper()
	paths := map[string]bool{}
	err := filepath.WalkDir(dir, func(p string, _ fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(dir, p)
		paths[rel] = true
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return paths
}

func TestExportEndToEndWithCreditsAndFinal(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 60)...)
	snapshot := mi.size()
	gw := startGateway(t, creditOnRequest())
	h, _ := startLogsAgent(t, gw, mi, logsOnly)
	// Design §8: after pages and a download, nothing new exists under <data-dir>. The snapshot is taken once the
	// agent's own records are on disk. The lease is written just after the reducer accepts it (effects run after the
	// state swap), so the test waits for its file, not only for the phase.
	waitAuthority(t, h.loop, authority.PhaseActive, 10*time.Second)
	lease := filepath.Join(h.dir, "session", "lease.json")
	for deadline := time.Now().Add(10 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		if _, err := os.Stat(lease); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the agent never wrote its lease record")
		}
	}
	before := dataDirPaths(t, h.dir)

	newest := askPage(t, gw, "req-page", msg.LogPageQuery{Limit: 5})
	sameLines(t, "page", texts(newest.Lines), miLines(55, 60))
	older := askPage(t, gw, "req-page-back", msg.LogPageQuery{Limit: 5, Before: &newest.Lines[0].TS})
	sameLines(t, "the page before it", texts(older.Lines), miLines(50, 55))

	requestLogs(t, gw, msg.LogRequest{RequestID: "req-exp", Export: &msg.LogExportQuery{}})
	first := waitChunks(t, gw, "req-exp", 1)[0]
	stillAt(t, gw, "req-exp", 1) // initial_credits 1: one chunk, then the agent waits
	if first.Final || len(first.Lines) == 0 || first.Seq == 0 {
		t.Fatalf("first chunk %+v", first)
	}
	mi.add(miLines(60, 70)...) // written after the export began: not in its snapshot

	if err := gw.GrantLogCredit("req-exp", 100); err != nil {
		t.Fatal(err)
	}
	cs := waitFinal(t, gw, "req-exp")
	for i, c := range cs {
		if i > 0 && c.Seq <= cs[i-1].Seq {
			t.Fatalf("chunk seq %d after %d", c.Seq, cs[i-1].Seq)
		}
		if c.Final != (i == len(cs)-1) {
			t.Fatalf("Final on chunk %d of %d", i, len(cs))
		}
	}
	sameLines(t, "export", chunkTexts(cs), miLines(0, 60))
	if last := cs[len(cs)-1].Seq; last != snapshot {
		t.Fatalf("final seq %d, want the snapshot's size %d", last, snapshot)
	}

	var added []string
	for p := range dataDirPaths(t, h.dir) {
		if !before[p] {
			added = append(added, p)
		}
	}
	if len(added) > 0 {
		slices.Sort(added)
		t.Fatalf("the pages and the download left new paths under the data dir: %v", added)
	}
}

func TestLogsExportCancelStopsChunks(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 60)...)
	gw := startGateway(t, creditOnRequest())
	startLogsAgent(t, gw, mi, logsOnly)

	requestLogs(t, gw, msg.LogRequest{RequestID: "req-cancel", Export: &msg.LogExportQuery{}})
	waitChunks(t, gw, "req-cancel", 1)
	if err := gw.CancelLog("req-cancel"); err != nil {
		t.Fatal(err)
	}
	if err := gw.GrantLogCredit("req-cancel", 100); err != nil { // after the cancel: an unknown id
		t.Fatal(err)
	}
	stillAt(t, gw, "req-cancel", 1)

	// The stream and the agent's slots are intact: the next request is answered.
	if p := askPage(t, gw, "after-cancel", msg.LogPageQuery{Limit: 1}); p.Unavailable != msg.UnavailableNone || len(p.Lines) != 1 {
		t.Fatalf("page after a cancel %+v", p)
	}
}

func TestLogsExportResumesFromTheLastSeq(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 60)...)
	gw := startGateway(t, creditOnRequest())
	startLogsAgent(t, gw, mi, logsOnly)

	requestLogs(t, gw, msg.LogRequest{RequestID: "req-1", Export: &msg.LogExportQuery{}})
	waitChunks(t, gw, "req-1", 1)
	if err := gw.GrantLogCredit("req-1", 2); err != nil {
		t.Fatal(err)
	}
	cut := waitChunks(t, gw, "req-1", 3)
	if err := gw.CancelLog("req-1"); err != nil { // interrupted: the DP resumes from the last chunk it has
		t.Fatal(err)
	}
	stillAt(t, gw, "req-1", 3)
	if cut[2].Final {
		t.Fatal("the export ended before it could be interrupted")
	}

	requestLogs(t, gw, msg.LogRequest{RequestID: "req-2", Export: &msg.LogExportQuery{FromSeq: cut[2].Seq}})
	// Connect and Logs are separate streams: a credit sent right after the request can reach the agent first and is
	// dropped (2026-10-20 design §4.1). Wait for the first chunk, which initial_credits pays for, then grant.
	waitChunks(t, gw, "req-2", 1)
	if err := gw.GrantLogCredit("req-2", 100); err != nil {
		t.Fatal(err)
	}
	rest := waitFinal(t, gw, "req-2")
	sameLines(t, "the interrupted export and its resume", append(chunkTexts(cut), chunkTexts(rest)...), miLines(0, 60))
}

func TestLogsExportRotationMidwayEndsIncomplete(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 60)...)
	gw := startGateway(t, creditOnRequest())
	startLogsAgent(t, gw, mi, logsOnly)

	requestLogs(t, gw, msg.LogRequest{RequestID: "req-rot", Export: &msg.LogExportQuery{}})
	first := waitChunks(t, gw, "req-rot", 1)[0]
	mi.rotate(miLines(100, 101)...) // the MI rolled its file over: a new, shorter one
	if err := gw.GrantLogCredit("req-rot", 100); err != nil {
		t.Fatal(err)
	}
	cs := waitFinal(t, gw, "req-rot")
	last := cs[len(cs)-1]
	if len(cs) != 2 || len(last.Lines) != 0 || last.Seq != first.Seq {
		t.Fatalf("%d chunks; final %+v", len(cs), last)
	}
	if len(last.Gaps) != 1 || last.Gaps[0].Reason != msg.GapCollectionPaused {
		t.Fatalf("a rotation midway must end the export incomplete: %+v", last.Gaps)
	}
}

func TestLogsContainerGoneIsContainerMissing(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 5)...)
	gw := startGateway(t, creditOnRequest())
	_, ad := startLogsAgent(t, gw, mi, logsOnly)
	ad.gone.Store(true)

	if p := askPage(t, gw, "gone-page", msg.LogPageQuery{Limit: 5}); p.Unavailable != msg.UnavailableContainerMissing || len(p.Lines) != 0 {
		t.Fatalf("page %+v", p)
	}
	requestLogs(t, gw, msg.LogRequest{RequestID: "gone-export", Export: &msg.LogExportQuery{}})
	if p := waitPage(t, gw, "gone-export"); p.Unavailable != msg.UnavailableContainerMissing {
		t.Fatalf("export %+v", p)
	}
	stillAt(t, gw, "gone-export", 0)
}

func TestLogfileNotExposedIsNotCollecting(t *testing.T) {
	mi := newLogfileServer(t, miLines(0, 5)...)
	mi.setStatus(http.StatusNotFound) // logfile not in management.endpoints.web.exposure.include
	gw := startGateway(t, creditOnRequest())
	startLogsAgent(t, gw, mi, logsOnly)

	if p := askPage(t, gw, "hidden-page", msg.LogPageQuery{Limit: 5}); p.Unavailable != msg.UnavailableNotCollecting || len(p.Lines) != 0 {
		t.Fatalf("page %+v", p)
	}
	requestLogs(t, gw, msg.LogRequest{RequestID: "hidden-export", Export: &msg.LogExportQuery{}})
	if p := waitPage(t, gw, "hidden-export"); p.Unavailable != msg.UnavailableNotCollecting {
		t.Fatalf("export %+v", p)
	}
	stillAt(t, gw, "hidden-export", 0)
}

func TestAgentWithoutLogsRoleNeverReceivesLogMessages(t *testing.T) {
	mi := newLogfileServer(t, miLine(0, "never read"))
	sc := fakegw.DefaultScenario()
	sc.Limits.HeartbeatInterval = 100 * time.Millisecond
	// Forced: the fake sends this although the session did not advertise
	// logs, so the agent's own behaviour is under test, not the fake's gating.
	sc.Steps = []fakegw.Step{
		{LogRequest: &fakegw.LogRequestStep{Placement: "binding-1", RequestID: "req-none", Page: &fakegw.LogPageStep{Limit: 10}, Force: true}},
	}
	gw := startGateway(t, sc)
	h, _ := startLogsAgent(t, gw, mi, Roles{Control: true})
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)

	// The step has run once the forced message is in the transcript.
	waitOut(t, gw, "LogRequest")
	// The control session stays healthy: heartbeats keep being answered after it.
	hb := gw.Count("Heartbeat")
	if !gw.WaitFor("Heartbeat", hb+2, 10*time.Second) {
		t.Fatal("no heartbeat after the forced log message")
	}
	if h.loop.Snapshot().Session.Phase != PhaseActive {
		t.Fatalf("phase %s after the log message", h.loop.Snapshot().Session.Phase)
	}

	if n := gw.LogsStreams(); n != 0 {
		t.Fatalf("Logs stream opened %d times by an agent without the role", n)
	}
	if n := gw.Count("LogPage") + gw.Count("LogChunk"); n != 0 {
		t.Fatalf("%d log messages from an agent without the role", n)
	}
	if _, err := os.Stat(filepath.Join(h.dir, "logs")); !os.IsNotExist(err) {
		t.Fatalf("a logs directory was created: %v", err)
	}
	// And the fake's own gating, directly.
	if err := gw.RequestLogs(msg.LogRequest{RequestID: "r", PlacementRef: "binding-1", Page: &msg.LogPageQuery{Limit: 1}}); err == nil {
		t.Fatal("RequestLogs to a session that did not advertise logs must fail")
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
