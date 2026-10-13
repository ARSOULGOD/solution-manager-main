//go:build integration

package agent

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func acks(gw *fakegw.Server) map[string]msg.CommandAck {
	out := map[string]msg.CommandAck{}
	for _, m := range gw.Received("CommandAck") {
		a := m.(msg.CommandAck)
		out[a.CommandID] = a
	}
	return out
}

// Plan 015 T2.3 (b), (c): OPERATION_REMATERIALIZE end to end over the real wire. A
// differing precondition writes nothing and acks REFUSED/TARGET_MOVED; the right one
// writes and acks APPLIED with the container's StartedAt (host clock).
func TestIntegrationRematerializePreconditionAndStartedAt(t *testing.T) {
	ad := newFakeAdapter(t)
	if err := os.WriteFile(ad.target(), []byte("operator: edit\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cur := configwriter.SHA256([]byte("operator: edit\n"))
	wrong := "0000000000000000000000000000000000000000000000000000000000000000"
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "moved", BindingID: "binding-1", Generation: 1, Operation: "rematerialize", Payload: "a: 1\n", ExpectedCurrentSHA256: &wrong}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "ok", BindingID: "binding-1", Generation: 2, Operation: "rematerialize", Payload: "a: 2\n", ExpectedCurrentSHA256: &cur}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 2, 30*time.Second) {
		t.Fatal("no acks")
	}
	a := acks(gw)
	if m := a["moved"]; m.Outcome != msg.OutcomeRefused || m.Detail.Code != msg.DetailOf(msg.ErrTargetMoved) || m.ContainerStartedAt != nil {
		t.Fatalf("%+v", m)
	}
	if o := a["ok"]; o.Outcome != msg.OutcomeApplied || o.ContainerStartedAt == nil || !o.ContainerStartedAt.Equal(ad.started.UTC().Truncate(time.Microsecond)) && o.ContainerStartedAt.Sub(ad.started).Abs() > time.Millisecond {
		t.Fatalf("%+v (host started %v)", o, ad.started)
	}
	if b, _ := os.ReadFile(ad.target()); string(b) != "a: 2\n" {
		t.Fatalf("only the second offer wrote: %q", b)
	}
	// the spool is gone for both terminal outcomes
	for _, gen := range []uint64{1, 2} {
		if _, err := os.Stat(filepath.Join(h.dir, filepath.FromSlash(commands.DirRel("binding-1", gen)))); err == nil {
			t.Fatalf("generation %d: the spool outlived its terminal outcome", gen)
		}
	}
}

// Plan 015 T2.3 (e): the DP's TelemetryDeclarations land in the live set, with the
// declared probe, and the agent advertises the feature capabilities that let the
// gateway send them.
func TestIntegrationDeclarationsArriveOnTheWire(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Declarations: &fakegw.DeclarationsStep{BindingID: "binding-1", Interval: 5 * time.Second,
		Metrics:     []fakegw.MetricStep{{Name: "cache.size", MetricType: "gauge", Unit: "entries", Port: 8080, Path: "/api/cache/size", Pointer: "/size"}},
		HealthProbe: &fakegw.ProbeStep{Kind: "ACTUATOR_HEALTH", Path: "/actuator/health", HostPort: 18080, Strength: "READINESS"}}}}
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", newFakeAdapter(t))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if p := h.loop.d.Declarations.Probe("binding-1"); p != nil {
			if p.HostPort != 18080 || p.Strength != msg.StrengthReadiness || len(h.loop.d.Declarations.Bindings()["binding-1"].Metrics) != 1 {
				t.Fatalf("%+v %+v", p, h.loop.d.Declarations.Bindings())
			}
			open := gw.Received("SessionOpen")[0].(msg.SessionOpen)
			for _, want := range []msg.Capability{msg.CapDiscovery, msg.CapRematerialize, msg.CapDeclarations} {
				found := false
				for _, c := range open.CapabilitiesSupported {
					found = found || c == want
				}
				if !found {
					t.Fatalf("SessionOpen must advertise %s: %v", want, open.CapabilitiesSupported)
				}
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("declarations never arrived")
}

// healthyAdapter observes the probe and records which probe it was given.
type healthyAdapter struct {
	*fakeAdapter
	probes chan *msg.HealthProbe
}

func (h *healthyAdapter) ObserveHealth(_ context.Context, _ runtime.Placement, probe *msg.HealthProbe) msg.HealthObservation {
	select {
	case h.probes <- probe:
	default:
	}
	o := msg.HealthObservation{State: msg.HealthNotDeclared, ObservedAt: time.Now()}
	if probe != nil {
		o.HTTPStatus, o.ActuatorStatus, o.Strength = 503, "DOWN", probe.Strength
	}
	return o
}

// Plan 015 T2.3 (a), (a'): the periodic report carries the declared probe's facts
// and the host-clock start time. Before any probe is declared nothing is probed.
func TestIntegrationReportCarriesProbeFactsAndStartTime(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.ReportInterval = 200 * time.Millisecond
	sc.Steps = []fakegw.Step{
		{Declarations: &fakegw.DeclarationsStep{BindingID: "binding-1", Interval: 5 * time.Second,
			HealthProbe: &fakegw.ProbeStep{Kind: "ACTUATOR_HEALTH", Path: "/actuator/health", HostPort: 18080, Strength: "READINESS"}}},
	}
	gw := startGateway(t, sc)
	ad := &healthyAdapter{fakeAdapter: newFakeAdapter(t), probes: make(chan *msg.HealthProbe, 64)}
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		for _, m := range gw.Received("ActualStateReportBatch") {
			for _, it := range m.(msg.ActualStateReportBatch).Items {
				if it.Health != nil && it.Health.HTTPStatus == 503 {
					if it.Health.ActuatorStatus != "DOWN" || it.Health.Strength != msg.StrengthReadiness {
						t.Fatalf("%+v", it.Health)
					}
					if it.ContainerStartedAt == nil || it.ContainerStartedAt.Sub(ad.started).Abs() > time.Millisecond {
						t.Fatalf("StartedAt on the host clock, untouched: %v vs %v", it.ContainerStartedAt, ad.started)
					}
					return
				}
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("no report carried the declared probe's facts")
}

// discoveringAdapter reports one labelled container and resolves it by name.
type discoveringAdapter struct {
	*fakeAdapter
	byName chan string
}

func (d *discoveringAdapter) Discover(context.Context) ([]msg.DiscoveredContainer, int, error) {
	return []msg.DiscoveredContainer{{ContainerID: "container-abc", ContainerName: "java1", ImageRef: "img:1",
		Labels: map[string]string{"com.solace.sm.instance": "java1"}}}, 0, nil
}

func (d *discoveringAdapter) ResolveByName(_ context.Context, name, binding string) (runtime.Placement, error) {
	select {
	case d.byName <- name + "->" + binding:
	default:
	}
	return d.fakeAdapter.Resolve(context.Background(), binding)
}

// Plan 015 T2.3 (d): a DiscoveryReport after SessionAccept; the DP's placement id
// for the container is persisted by name, survives a restart, and a command for that
// placement finds its container by name.
func TestIntegrationDiscoveryRegistersAndTheContainerIsFoundByName(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.Discovery = fakegw.DiscoveryPolicy{Mode: "register", Bindings: map[string]string{"java1": "binding-1"}}
	sc.Steps = []fakegw.Step{
		{Expect: &fakegw.ExpectStep{Kind: "DiscoveryReport", Timeout: 20 * time.Second}},
		{Sleep: durPtr(500 * time.Millisecond)},
		{Offer: &fakegw.OfferStep{CommandID: "pf", BindingID: "binding-1", Generation: 1, Operation: "preflight"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := &discoveringAdapter{fakeAdapter: newFakeAdapter(t), byName: make(chan string, 8)}
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("DiscoveryReport", 1, 20*time.Second) {
		t.Fatal("no DiscoveryReport after SessionAccept")
	}
	rep := gw.Received("DiscoveryReport")[0].(msg.DiscoveryReport)
	if len(rep.Items) != 1 || rep.Items[0].ContainerName != "java1" {
		t.Fatalf("%+v", rep)
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) && h.loop.Snapshot().Discovery.Bindings.ByName["java1"] != "binding-1" {
		time.Sleep(20 * time.Millisecond)
	}
	if got := h.loop.Snapshot().Discovery.Bindings.ByName["java1"]; got != "binding-1" {
		t.Fatalf("name -> binding not recorded: %v", h.loop.Snapshot().Discovery.Bindings.ByName)
	}
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	select {
	case got := <-ad.byName:
		if got != "java1->binding-1" {
			t.Fatal(got)
		}
	default:
		t.Fatal("the command's container must be found by name through the registered binding")
	}
	h.stop()
	// the mapping survives a restart (persisted)
	st := store.MustOpen(h.dir)
	if !st.Exists("discovery/bindings.json") {
		t.Fatal("the binding map must be persisted")
	}
}

// OR-129: a RECEIVED command whose payload is in the spool resumes after a restart
// WITHOUT a CommandPull, runs, acks, and the spool is purged.
func TestIntegrationResumeFromSpoolWithoutCommandPull(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "data")
	st := store.MustOpen(dir)
	payload := "from: spool\n"
	hash := configwriter.SHA256([]byte(payload))
	if err := st.Save(store.Blob{RelPath: commands.PayloadRel("binding-1", 1), Data: []byte(payload)}); err != nil {
		t.Fatal(err)
	}
	if err := st.Save(commands.Record{SchemaVersion: 1, RolloutGeneration: 1, IdempotencyKey: "k", CommandID: "resumed", BindingID: "binding-1",
		Operation: msg.OpApply, ConfigHash: hash, Step: msg.StepReceived, ReceivedAck: true}); err != nil {
		t.Fatal(err)
	}
	gw := startGateway(t, nil)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, dir, ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 30*time.Second) {
		t.Fatal("the resumed command never finished")
	}
	if a := acks(gw)["resumed"]; a.Outcome != msg.OutcomeApplied {
		t.Fatalf("%+v", a)
	}
	if gw.Count("CommandPull") != 0 {
		t.Fatal("a resume from the spool must not CommandPull")
	}
	if b, _ := os.ReadFile(ad.target()); string(b) != payload {
		t.Fatalf("%q", b)
	}
	if _, err := os.Stat(filepath.Join(dir, filepath.FromSlash(commands.DirRel("binding-1", 1)))); err == nil {
		t.Fatal("the spool is purged once the command is terminal")
	}
}

// Plan 015 T2.3 (e), AG-D28: with NO --telemetry-config file, the declarations the DP
// pushes drive the collectors, and a later message replaces the binding's set whole.
func TestIntegrationWireDeclarationsDriveTheCollectors(t *testing.T) {
	sc := telemetryScenario()
	sc.Steps = []fakegw.Step{{Declarations: &fakegw.DeclarationsStep{BindingID: "binding-1", Interval: time.Second,
		Metrics: []fakegw.MetricStep{{Name: "cache.hit_rate", MetricType: "gauge", Unit: "ratio", Port: 8081, Path: "/api/cache/stats", Pointer: "/hitRate"}}}}}
	gw := startGateway(t, sc)
	addr, _ := jsonTarget(t)
	ad := newFakeAdapter(t)
	ad.scrape = addr
	h := startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Adapter = ad
		bc.Roles = both
		bc.Telemetry = &TelemetryConfig{BufferBytes: 1 << 20, BufferAge: time.Hour} // no file
	})
	if !gw.WaitFor("TelemetryBatch", 1, 15*time.Second) {
		t.Fatal("the wire-declared metric was never collected")
	}
	if s := batches(gw)[0].Samples; len(s) != 1 || s[0].SeriesKey != "cache.hit_rate" {
		t.Fatalf("%+v", s)
	}
	// a replacement set swaps the collector: the new name appears
	renamed := msg.TelemetryDeclarations{BindingID: "binding-1", Interval: time.Second, Metrics: []msg.TelemetryMetric{{
		Name: "cache.hit_rate2", MetricType: msg.MetricGauge, Unit: "ratio",
		HTTPJSON: &msg.HTTPJSONSource{Port: 8081, Path: "/api/cache/stats", Pointer: "/hitRate", OnUnavailable: msg.OnUnavailableDegrade}}}}
	if _, err := h.loop.d.Declarations.Replace(renamed); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		for _, b := range batches(gw) {
			for _, s := range b.Samples {
				if s.SeriesKey == "cache.hit_rate2" {
					return
				}
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("the replacement set never took over")
}

// Review finding 12 / OR-112's same-clock rule: with the DP's clock 1 h ahead of the host's, the ack's and
// the report's container_started_at are still the host's raw StartedAt (no Clock.Offset applied), while the
// older restart_observed_at is offset-corrected.
func TestIntegrationStartedAtIsTheHostClockUnderSkew(t *testing.T) {
	const skew = time.Hour
	sc := fakegw.DefaultScenario()
	sc.Lease.Duration = 24 * time.Hour
	sc.Limits.SkewTolerance = 3 * time.Hour // the skew is deliberate
	sc.Limits.HeartbeatInterval = 100 * time.Millisecond
	sc.Limits.ReportInterval = 200 * time.Millisecond
	sc.Policy.Heartbeat = fakegw.HeartbeatPolicy{OffsetMs: skew.Milliseconds(), State: "OK"}
	sc.Steps = []fakegw.Step{
		{Sleep: durPtr(1500 * time.Millisecond)}, // let the heartbeat establish the skew first
		{Offer: &fakegw.OfferStep{CommandID: "skewed", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: "a: 1\n"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 30 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) && h.loop.Clock().Offset() < 30*time.Minute {
		time.Sleep(20 * time.Millisecond)
	}
	if h.loop.Clock().Offset() < 30*time.Minute {
		t.Fatal("the skew never took effect")
	}
	if !gw.WaitFor("CommandAck", 1, 30*time.Second) {
		st := h.loop.Snapshot()
		t.Fatalf("no ack (authority %s, session %s, journal %+v, pulls %d)", st.Authority.Phase, st.Session.Phase, st.Commands.Journal.Records, gw.Count("CommandPull"))
	}
	a := acks(gw)["skewed"]
	if a.Outcome != msg.OutcomeApplied || a.ContainerStartedAt == nil || a.ContainerStartedAt.Sub(ad.started).Abs() > time.Millisecond {
		t.Fatalf("the ack carries the raw host StartedAt: %+v (host %v)", a, ad.started)
	}
	for end := time.Now().Add(15 * time.Second); time.Now().Before(end); time.Sleep(50 * time.Millisecond) {
		for _, m := range gw.Received("ActualStateReportBatch") {
			for _, it := range m.(msg.ActualStateReportBatch).Items {
				if it.RestartObservedAt == nil || it.ContainerStartedAt == nil {
					continue
				}
				if d := it.ContainerStartedAt.Sub(ad.started).Abs(); d > time.Millisecond {
					t.Fatalf("report StartedAt is not the host's raw value: off by %v", d)
				}
				if off := it.RestartObservedAt.Sub(*it.ContainerStartedAt); off < 30*time.Minute {
					t.Fatalf("restart_observed_at stays offset-corrected, the new field does not: %v apart", off)
				}
				return
			}
		}
	}
	t.Fatal("no report carried both fields")
}
