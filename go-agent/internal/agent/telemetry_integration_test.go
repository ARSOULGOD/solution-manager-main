//go:build integration

package agent

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
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
	if !reflect.DeepEqual(open.CapabilitiesSupported, []msg.Capability{msg.CapReportOnly}) {
		t.Fatalf("advertised %v", open.CapabilitiesSupported)
	}
	out := &syncBuffer{}
	go gw.Console(strings.NewReader("restart\n"), out)
	waitOutput(t, out, "restart-1 gen=1 -> REFUSED (CAPABILITY_MISSING)", 10*time.Second)
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
