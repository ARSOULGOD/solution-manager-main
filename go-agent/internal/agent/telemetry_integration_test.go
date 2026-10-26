//go:build integration

package agent

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
)

func ptrTo(s string) *string { return &s }

// fastDecl bypasses declare's 1 s interval floor so tests run in tenths of a second.
func fastDecl() *declare.File {
	return &declare.File{SchemaVersion: 1, Bindings: map[string]declare.Binding{"binding-1": {
		Interval: 100 * time.Millisecond, Timeout: 100 * time.Millisecond,
		Metrics: []declare.Metric{{Name: "cache.hit_rate", MetricType: "gauge", Unit: "ratio",
			Source: declare.Source{Kind: "http_json", Port: 8081, Path: "/api/cache/stats", Pointer: ptrTo("/hitRate"), OnUnavailable: "degrade"}}},
	}}}
}

// jsonTarget serves {"hitRate": <value>} and returns its host:port.
func jsonTarget(t *testing.T) (string, *atomic.Value) {
	var v atomic.Value
	v.Store(0.93)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = fmt.Fprintf(w, `{"hitRate": %v}`, v.Load())
	}))
	t.Cleanup(srv.Close)
	return strings.TrimPrefix(srv.URL, "http://"), &v
}

func startTelemetryAgent(t *testing.T, gw *fakegw.Server, dir string, roles Roles, bytes int64) (*agentHandle, *fakeAdapter) {
	t.Helper()
	addr, _ := jsonTarget(t)
	ad := newFakeAdapter(t)
	ad.scrape = addr
	h := startAgent(t, gw, dir, func(bc *BuildConfig) {
		bc.Adapter = ad
		bc.Roles = roles
		bc.Telemetry = &TelemetryConfig{Declarations: fastDecl(), BufferBytes: bytes, BufferAge: time.Hour}
	})
	return h, ad
}

func batches(gw *fakegw.Server) []msg.TelemetryBatch {
	var out []msg.TelemetryBatch
	for _, m := range gw.Received("TelemetryBatch") {
		out = append(out, m.(msg.TelemetryBatch))
	}
	return out
}

// telemetryScenario is the default scenario with a short report interval: the
// first actual-state report, which mints the ids every sample carries, is sent
// one report interval after SessionAccept (ruling R3).
func telemetryScenario() *fakegw.Scenario {
	sc := fakegw.DefaultScenario()
	sc.Limits.ReportInterval = 500 * time.Millisecond
	return sc
}

var both = Roles{Control: true, Telemetry: true}

func TestIntegrationTelemetryBatchesCarryMintedIDs(t *testing.T) {
	gw := startGateway(t, telemetryScenario())
	h, _ := startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("no telemetry batch")
	}
	reg := h.loop.Snapshot().Reports
	want := map[string]string{
		"binding_id":     "binding-1",
		"replica_id":     reg.ReplicaID("binding-1", "java1"),
		"incarnation_id": reg.IncarnationID("binding-1", "java1", "container-abc"),
	}
	b := batches(gw)[0]
	if want["replica_id"] == "" || len(b.Samples) != 1 || !reflect.DeepEqual(b.Samples[0].Labels, want) || b.Samples[0].Value != 0.93 {
		t.Fatalf("batch %+v, want labels %v", b, want)
	}
}

func TestIntegrationTelemetryPauseBuffersResumeDrainsInOrder(t *testing.T) {
	gw := startGateway(t, telemetryScenario())
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("no telemetry batch")
	}
	gw.PauseTelemetry()
	time.Sleep(1500 * time.Millisecond) // credits run dry, then ~10 ticks buffer on disk
	before := len(batches(gw))
	time.Sleep(500 * time.Millisecond)
	if n := len(batches(gw)); n != before {
		t.Fatalf("sent %d batches while paused and out of credits", n-before)
	}
	gw.ResumeTelemetry(4)
	if !gw.WaitFor("TelemetryBatch", before+8, 10*time.Second) {
		t.Fatalf("buffer did not drain: %d batches", len(batches(gw)))
	}
	last := uint64(0)
	for _, b := range batches(gw) {
		if b.BatchSeq <= last {
			t.Fatalf("seq %d after %d: out of order", b.BatchSeq, last)
		}
		last = b.BatchSeq
	}
}

func TestIntegrationTelemetryTinyBufferEmitsTheGapRange(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.InitialCredits = 0 // nothing leaves until ResumeTelemetry
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 400) // one ~230-byte batch fits, two do not
	time.Sleep(1500 * time.Millisecond)
	gw.ResumeTelemetry(1)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("no telemetry batch after resume")
	}
	b := batches(gw)[0]
	want := []msg.GapMarker{{FromSeq: 1, ToSeq: b.BatchSeq - 1, Reason: "DROPPED_OLDEST"}}
	if b.BatchSeq < 3 || !reflect.DeepEqual(b.GapMarkers, want) {
		t.Fatalf("first batch seq %d gaps %+v, want %+v", b.BatchSeq, b.GapMarkers, want)
	}
}

func TestIntegrationTelemetryAgentRestartSendsUnacked(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.InitialCredits = 0
	gw := startGateway(t, sc)
	h, _ := startTelemetryAgent(t, gw, "", both, 1<<20)
	time.Sleep(800 * time.Millisecond) // several batches buffered, none sent
	h.stop()
	startTelemetryAgent(t, gw, h.dir, both, 1<<20)
	deadline := time.Now().Add(10 * time.Second)
	for !gw.WaitFor("TelemetryBatch", 1, 200*time.Millisecond) && time.Now().Before(deadline) {
		gw.ResumeTelemetry(16) // repeated until the new session's stream is open
	}
	if !gw.WaitFor("TelemetryBatch", 4, 10*time.Second) {
		t.Fatalf("only %d batches", len(batches(gw)))
	}
	if b := batches(gw)[0]; b.BatchSeq != 1 {
		t.Fatalf("the buffer did not survive the restart: first seq %d", b.BatchSeq)
	}
}

func TestIntegrationTelemetryDisconnectResumesFromTheWatermark(t *testing.T) {
	gw := startGateway(t, telemetryScenario())
	out := &syncBuffer{}
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 3, 10*time.Second) {
		t.Fatal("no telemetry")
	}
	gw.Console(strings.NewReader("disconnect\n"), out)
	waitOutput(t, out, "session closed", 5*time.Second)
	before := len(batches(gw))
	if !gw.WaitFor("TelemetryBatch", before+3, 30*time.Second) {
		t.Fatal("telemetry did not resume after reconnect")
	}
	seen := map[uint64]int{}
	for _, b := range batches(gw) {
		seen[b.BatchSeq]++
	}
	resent := 0
	for _, n := range seen {
		if n > 1 {
			resent++
		}
	}
	if resent > 1 { // at most the one batch in flight at the disconnect
		t.Fatalf("%d batches resent after reconnect: %v", resent, seen)
	}
}

func TestIntegrationTelemetryOnlyRoleRefusesCommands(t *testing.T) {
	gw := startGateway(t, telemetryScenario())
	h, _ := startTelemetryAgent(t, gw, "", Roles{Telemetry: true}, 1<<20)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	open := gw.Received("SessionOpen")[0].(msg.SessionOpen)
	if !reflect.DeepEqual(open.CapabilitiesSupported, []msg.Capability{msg.CapReportOnly, msg.CapDeclarations}) { // plus plan 015 T2.3's feature capability
		t.Fatalf("advertised %v", open.CapabilitiesSupported)
	}
	out := &syncBuffer{}
	go gw.Console(strings.NewReader("restart\n"), out)
	waitOutput(t, out, "restart-binding-1-1 gen=1 -> REFUSED (CAPABILITY_MISSING)", 10*time.Second)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("a telemetry-only agent must still send telemetry")
	}
}

func TestIntegrationTelemetryRejectDropsTheBatchAsAGap(t *testing.T) {
	sc := telemetryScenario()
	sc.Policy.Telemetry.RejectNext = "UNKNOWN_REPLICA"
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 2, 10*time.Second) {
		t.Fatal("too few batches")
	}
	b := batches(gw)[1]
	if b.BatchSeq != 2 || !reflect.DeepEqual(b.GapMarkers, []msg.GapMarker{{FromSeq: 1, ToSeq: 1, Reason: "UNKNOWN_REPLICA"}}) {
		t.Fatalf("second batch %+v", b)
	}
}

func TestIntegrationTelemetryAgentRestartSkipsAckedBatches(t *testing.T) {
	gw := startGateway(t, telemetryScenario())
	h, _ := startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 3, 10*time.Second) {
		t.Fatal("no telemetry")
	}
	h.stop()
	before := batches(gw)
	n0 := len(before)
	var w uint64 // the gateway's watermark: it commits every batch it receives, in order
	for _, b := range before {
		if b.BatchSeq > w {
			w = b.BatchSeq
		}
	}
	startTelemetryAgent(t, gw, h.dir, both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", n0+3, 10*time.Second) {
		t.Fatalf("telemetry did not resume after restart: %d batches", len(batches(gw)))
	}
	var seqs []uint64
	atW := 0
	for _, b := range batches(gw)[n0:] {
		if b.BatchSeq < w {
			t.Fatalf("batch %d resent after restart; watermark %d", b.BatchSeq, w)
		}
		if b.BatchSeq == w {
			atW++
		}
		if len(seqs) == 0 || seqs[len(seqs)-1] != b.BatchSeq {
			seqs = append(seqs, b.BatchSeq)
		}
	}
	if atW > 1 {
		t.Fatalf("watermark batch %d sent %d times after restart", w, atW)
	}
	if seqs[0] != w && seqs[0] != w+1 {
		t.Fatalf("first seq after restart %d, want %d or %d", seqs[0], w, w+1)
	}
	for i := 1; i < len(seqs); i++ {
		if seqs[i] != seqs[i-1]+1 {
			t.Fatalf("seqs after restart not contiguous: %v", seqs)
		}
	}
}

// arrivals is when each TelemetryBatch reached the gateway after a moment, in order.
func arrivals(gw *fakegw.Server, after time.Time) []time.Time {
	var out []time.Time
	for _, e := range gw.Transcript() {
		if e.Dir == "in" && e.Kind == "TelemetryBatch" && e.At.After(after) {
			out = append(out, e.At)
		}
	}
	return out
}

// bursts splits arrivals wherever two are more than quiet apart.
func bursts(ts []time.Time, quiet time.Duration) [][]time.Time {
	var out [][]time.Time
	for i, at := range ts {
		if i == 0 || at.Sub(ts[i-1]) > quiet {
			out = append(out, nil)
		}
		out[len(out)-1] = append(out[len(out)-1], at)
	}
	return out
}

func TestIntegrationTelemetryPushIntervalSendsInBursts(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.TelemetryPushInterval = fakegw.PushSpec{Every: time.Second} // pull stays fastDecl's 100 ms
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 30, 20*time.Second) {
		t.Fatalf("only %d batches", len(batches(gw)))
	}
	bs := bursts(arrivals(gw, time.Time{}), 300*time.Millisecond)
	if len(bs) < 3 {
		t.Fatalf("%d bursts for 30 batches: batches were not held for the push tick", len(bs))
	}
	for i := 1; i < len(bs); i++ {
		if gap := bs[i][0].Sub(bs[i-1][len(bs[i-1])-1]); gap < 600*time.Millisecond {
			t.Fatalf("burst %d began %v after the previous one ended; push is 1s", i, gap)
		}
	}
}

func TestIntegrationTelemetryPushIntervalAppliesAtNextSession(t *testing.T) {
	gw := startGateway(t, telemetryScenario()) // fake-gateway's default: an explicit 0
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 5, 10*time.Second) {
		t.Fatal("no telemetry")
	}
	out := &syncBuffer{}
	gw.Console(strings.NewReader("set push 1s\n"), out)
	waitOutput(t, out, "next session: push=1s (disconnect to apply)", 5*time.Second)
	mark := time.Now()
	time.Sleep(time.Second)
	if bs := bursts(arrivals(gw, mark), 300*time.Millisecond); len(bs) != 1 || len(bs[0]) < 5 {
		t.Fatalf("the live session changed schedule before a reconnect: %d bursts", len(bs))
	}
	gw.Console(strings.NewReader("disconnect\n"), out)
	waitOutput(t, out, "session closed", 5*time.Second)
	mark = time.Now()
	deadline := time.Now().Add(30 * time.Second)
	for len(arrivals(gw, mark)) == 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	time.Sleep(3500 * time.Millisecond) // what was stored during the reconnect, then about three push ticks
	if bs := bursts(arrivals(gw, mark), 300*time.Millisecond); len(bs) < 3 {
		t.Fatalf("%d bursts after the reconnect: the new push interval was not applied", len(bs))
	}
}

func freeAddr(t *testing.T) string {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := lis.Addr().String()
	lis.Close()
	return addr
}

func metricValue(t *testing.T, body, name string) float64 {
	t.Helper()
	m := regexp.MustCompile(`(?m)^` + name + ` (\S+)$`).FindStringSubmatch(body)
	if m == nil {
		t.Fatalf("no %s in /metrics", name)
	}
	v, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

// AG-D30 against what today's Java gateway sends: no field. The agent pushes
// every minute, so a batch is stored, not sent, for the first seconds — read
// from the gauge and the buffer, not by waiting a minute.
func TestIntegrationTelemetryPushIntervalUnsetUsesTheDefault(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.TelemetryPushInterval = fakegw.PushSpec{Unset: true}
	gw := startGateway(t, sc)
	addr, _ := jsonTarget(t)
	ad := newFakeAdapter(t)
	ad.scrape = addr
	admin := freeAddr(t)
	startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Adapter, bc.Roles, bc.AdminAddr = ad, both, admin
		bc.Telemetry = &TelemetryConfig{Declarations: fastDecl(), BufferBytes: 1 << 20, BufferAge: time.Hour}
	})
	if !gw.WaitFor("ActualStateReportBatch", 1, 10*time.Second) {
		t.Fatal("no actual-state report: the ids batches need were never minted")
	}
	time.Sleep(3 * time.Second) // about 30 pulls at 100 ms
	// At most one: a batch pulled in the instant before the sender reduced the
	// session's SessionUp was stored under send-at-once and is due at once
	// (spec §4.3). Every later one waits for the first tick, a minute away.
	if n := len(batches(gw)); n > 1 {
		t.Fatalf("%d batches sent within 3 s; an unset push interval is 1 min", n)
	}
	resp, err := http.Get("http://" + admin + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if v := metricValue(t, string(body), "sm_agent_telemetry_push_interval_seconds"); v != 60 {
		t.Fatalf("push interval gauge %v, want 60", v)
	}
	if v := metricValue(t, string(body), "sm_agent_telemetry_buffer_batches"); v < 10 {
		t.Fatalf("buffer holds %v batches after about 30 pulls; they were not stored", v)
	}
}
