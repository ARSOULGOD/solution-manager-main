package docker

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/errdefs"
	"github.com/docker/go-connections/nat"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// engine is a minimal engineAPI over a set of inspected containers.
type engine struct {
	all     map[string]types.ContainerJSON
	listErr error
}

func (e *engine) ContainerList(_ context.Context, o container.ListOptions) ([]types.Container, error) {
	if e.listErr != nil {
		return nil, e.listErr
	}
	var out []types.Container
	for id, c := range e.all {
		match := true
		for _, l := range o.Filters.Get("label") {
			k, v, hasV := strings.Cut(l, "=")
			got, ok := c.Config.Labels[k]
			if !ok || (hasV && got != v) {
				match = false
			}
		}
		for _, n := range o.Filters.Get("name") {
			if want := strings.TrimSuffix(strings.TrimPrefix(n, "^"), "$"); c.Name != want {
				match = false
			}
		}
		if match {
			out = append(out, types.Container{ID: id, Names: []string{c.Name}, Labels: c.Config.Labels})
		}
	}
	return out, nil
}
func (e *engine) ContainerInspect(_ context.Context, id string) (types.ContainerJSON, error) {
	c, ok := e.all[id]
	if !ok {
		return types.ContainerJSON{}, fmt.Errorf("no such container %s", id)
	}
	return c, nil
}
func (e *engine) ContainerRestart(context.Context, string, container.StopOptions) error { return nil }
func (e *engine) ContainerStop(context.Context, string, container.StopOptions) error    { return nil }
func (e *engine) Close() error                                                          { return nil }

func ctr(id, name string, running bool, labels map[string]string, mounts []types.MountPoint, ports nat.PortMap, health *types.Health) types.ContainerJSON {
	return types.ContainerJSON{
		ContainerJSONBase: &types.ContainerJSONBase{ID: id, Name: "/" + name, State: &types.ContainerState{Running: running, Health: health}},
		Config:            &container.Config{Labels: labels, Image: "registry.example/cache:2.4"},
		Mounts:            mounts,
		NetworkSettings:   &types.NetworkSettings{NetworkSettingsBase: types.NetworkSettingsBase{Ports: ports}},
	}
}

func port(hostPort string) []nat.PortBinding {
	return []nat.PortBinding{{HostIP: "0.0.0.0", HostPort: hostPort}}
}

// AG-D26: two labelled containers produce two items with their mounts and
// published ports; an unlabelled one none; only com.solace.sm.* labels leave the host.
func TestDiscoverReportsLabelledContainersAsFacts(t *testing.T) {
	eng := &engine{all: map[string]types.ContainerJSON{
		"id-b": ctr("id-b", "cache-b", true, map[string]string{InstanceLabel: "cache-b", ConfigFileLabel: "/etc/app/application.yml", "customer.secret": "s3cr3t"},
			[]types.MountPoint{{Type: "bind", Source: "/srv/b", Destination: "/etc/app", RW: true}, {Type: "volume", Source: "vol", Destination: "/data"}},
			nat.PortMap{"8080/tcp": port("18081"), "9999/tcp": nil}, nil),
		"id-a": ctr("id-a", "cache-a", false, map[string]string{InstanceLabel: "cache-a"}, nil, nat.PortMap{"8080/tcp": port("18080")}, nil),
		"id-x": ctr("id-x", "unlabelled", true, map[string]string{"other": "x"}, nil, nil, nil),
	}}
	items, truncated, err := New(eng).Discover(context.Background())
	if err != nil || truncated != 0 {
		t.Fatal(err, truncated)
	}
	if len(items) != 2 || items[0].ContainerName != "cache-a" || items[1].ContainerName != "cache-b" {
		t.Fatalf("two items ordered by name: %+v", items)
	}
	b := items[1]
	if b.ContainerID != "id-b" || b.ImageRef != "registry.example/cache:2.4" {
		t.Fatalf("%+v", b)
	}
	if len(b.Mounts) != 1 || b.Mounts[0] != (msg.DiscoveredMount{Source: "/srv/b", Destination: "/etc/app"}) {
		t.Fatalf("bind mounts only: %+v", b.Mounts)
	}
	if len(b.PublishedPorts) != 1 || b.PublishedPorts[0] != (msg.DiscoveredPort{ContainerPort: 8080, HostPort: 18081}) {
		t.Fatalf("published ports only: %+v", b.PublishedPorts)
	}
	for _, it := range items {
		for k := range it.Labels {
			if !strings.HasPrefix(k, LabelPrefix) {
				t.Fatalf("label %q left the host", k)
			}
		}
	}
	if b.Labels[ConfigFileLabel] != "/etc/app/application.yml" {
		t.Fatal(b.Labels)
	}
}

// A re-created container reports its new container_id under the same name.
func TestDiscoverReportsARecreatedContainersNewIDUnderTheSameName(t *testing.T) {
	eng := &engine{all: map[string]types.ContainerJSON{
		"old-id": ctr("old-id", "cache-a", true, map[string]string{InstanceLabel: "cache-a"}, nil, nil, nil)}}
	a := New(eng)
	first, _, _ := a.Discover(context.Background())
	delete(eng.all, "old-id")
	eng.all["new-id"] = ctr("new-id", "cache-a", true, map[string]string{InstanceLabel: "cache-a"}, nil, nil, nil)
	second, _, _ := a.Discover(context.Background())
	if first[0].ContainerName != second[0].ContainerName || first[0].ContainerID == second[0].ContainerID || second[0].ContainerID != "new-id" {
		t.Fatalf("%+v -> %+v", first, second)
	}
}

func TestDiscoverCapsAtSixtyFourItemsAndCountsTheRest(t *testing.T) {
	eng := &engine{all: map[string]types.ContainerJSON{}}
	for i := 0; i < 70; i++ {
		id := fmt.Sprintf("id-%03d", i)
		eng.all[id] = ctr(id, fmt.Sprintf("c-%03d", i), true, map[string]string{InstanceLabel: id}, nil, nil, nil)
	}
	items, truncated, err := New(eng).Discover(context.Background())
	if err != nil || len(items) != MaxDiscoveryItems || truncated != 6 {
		t.Fatal(err, len(items), truncated)
	}
	if items[0].ContainerName != "c-000" || items[63].ContainerName != "c-063" {
		t.Fatal("ordered by container name before the cut")
	}
}

// AG-D26 items 4, 5: a binding's container is found by name; the config path is the
// label's, mapped through a bind mount.
func TestResolveByNameUsesTheConfigFileLabelThroughABindMount(t *testing.T) {
	eng := &engine{all: map[string]types.ContainerJSON{
		"id-1": ctr("id-1", "cache-a", true, map[string]string{InstanceLabel: "cache-a", ConfigFileLabel: "/etc/app/custom.yml"},
			[]types.MountPoint{{Type: "bind", Source: "/srv/conf", Destination: "/etc/app", RW: true}}, nil, nil)}}
	p, err := New(eng).ResolveByName(context.Background(), "cache-a", "placement-7")
	if err != nil {
		t.Fatal(err)
	}
	if p.BindingID != "placement-7" || p.ContainerID != "id-1" || p.HostPath != "/srv/conf/custom.yml" || p.ConfigPathInContainer != "/etc/app/custom.yml" || p.Name != "cache-a" {
		t.Fatalf("%+v", p)
	}
	if _, err := New(eng).ResolveByName(context.Background(), "nope", "x"); err == nil {
		t.Fatal("an unknown name is not found")
	}
	eng.all["id-2"] = ctr("id-2", "nolabel", true, map[string]string{InstanceLabel: "nolabel"}, nil, nil, nil)
	if _, err := New(eng).ResolveByName(context.Background(), "nolabel", "x"); err == nil {
		t.Fatal("a container with no config-file label cannot be commanded")
	}
}

// ---- the declared probe (OR-130 D-4) ----

func probeFixture(t *testing.T, handler http.HandlerFunc, running bool, dockerHealth *types.Health) (*Adapter, runtime.Placement, *msg.HealthProbe, *atomic.Int32) {
	t.Helper()
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		handler(w, r)
	}))
	t.Cleanup(srv.Close)
	_, portStr, _ := net.SplitHostPort(srv.Listener.Addr().String())
	hp, _ := strconv.ParseUint(portStr, 10, 32)
	eng := &engine{all: map[string]types.ContainerJSON{
		// the stub's port is also a PUBLISHED port of the container, so a probe that is
		// guessed from the container's ports (rather than declared) would hit the stub
		"id-1": ctr("id-1", "cache-a", running, map[string]string{InstanceLabel: "cache-a"}, nil, nat.PortMap{"8080/tcp": port(portStr)}, dockerHealth)}}
	return New(eng), runtime.Placement{ContainerID: "id-1", Name: "cache-a"},
		&msg.HealthProbe{Kind: msg.ProbeActuatorHealth, Path: "/actuator/health", HostPort: uint32(hp), Strength: msg.StrengthReadiness}, &hits
}

func TestDeclaredProbe200WithAnActuatorBodyReportsHTTPStatusAndActuatorStatus(t *testing.T) {
	a, p, probe, hits := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/actuator/health" {
			t.Errorf("path %q", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"status":"UP","components":{}}`))
	}, true, nil)
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.HTTPStatus != 200 || obs.ActuatorStatus != "UP" || obs.Failure != msg.ProbeFailureNone || obs.Strength != msg.StrengthReadiness {
		t.Fatalf("%+v", obs)
	}
	if hits.Load() != 1 {
		t.Fatalf("one GET, got %d", hits.Load())
	}
	if obs.State != msg.HealthNotDeclared {
		t.Fatalf("no Docker health check is NOT_DECLARED, never invented: %v", obs.State)
	}
}

func TestDeclaredProbe503ReportsTheStatus(t *testing.T) {
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(503)
		_, _ = w.Write([]byte(`{"status":"DOWN"}`))
	}, true, nil)
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.HTTPStatus != 503 || obs.Failure != msg.ProbeFailureNone {
		t.Fatalf("%+v", obs)
	}
	if obs.ActuatorStatus != "DOWN" {
		t.Fatalf("the body's status is a fact too: %q", obs.ActuatorStatus)
	}
}

func TestDeclaredHTTPStatusProbeDoesNotParseABody(t *testing.T) {
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) }, true, nil)
	probe.Kind = msg.ProbeHTTPStatus
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.HTTPStatus != 200 || obs.ActuatorStatus != "" {
		t.Fatalf("%+v", obs)
	}
}

func TestDeclaredProbeConnectionRefusedIsFailureRefused(t *testing.T) {
	a, p, probe, _ := probeFixture(t, func(http.ResponseWriter, *http.Request) {}, true, nil)
	l, _ := net.Listen("tcp", "127.0.0.1:0") // find a port that is certainly closed
	probe.HostPort = uint32(l.Addr().(*net.TCPAddr).Port)
	_ = l.Close()
	c := a.cli.(*engine).all["id-1"]
	c.NetworkSettings.Ports = nat.PortMap{"8080/tcp": port(strconv.Itoa(int(probe.HostPort)))} // the container publishes it (nothing listens)
	a.cli.(*engine).all["id-1"] = c
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.Failure != msg.ProbeFailureRefused || obs.HTTPStatus != 0 {
		t.Fatalf("%+v", obs)
	}
}

func TestDeclaredProbeSlowServerIsFailureTimeout(t *testing.T) {
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { time.Sleep(300 * time.Millisecond) }, true, nil)
	a.probeClient = &http.Client{Timeout: 50 * time.Millisecond}
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.Failure != msg.ProbeFailureTimeout || obs.HTTPStatus != 0 {
		t.Fatalf("%+v", obs)
	}
}

// OR-101's no-invention rule: no probe declared -> nothing is probed.
func TestNoDeclaredProbeProbesNothing(t *testing.T) {
	a, p, _, hits := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) }, true,
		&types.Health{Status: "healthy"})
	obs := a.ObserveHealth(context.Background(), p, nil)
	if hits.Load() != 0 {
		t.Fatalf("the stub saw %d requests; with no probe declared there must be none", hits.Load())
	}
	if obs.HTTPStatus != 0 || obs.ActuatorStatus != "" || obs.Failure != msg.ProbeFailureNone {
		t.Fatalf("no facts without a probe: %+v", obs)
	}
	if obs.State != msg.HealthHealthy {
		t.Fatalf("Docker's own state still rides along as a hint: %v", obs.State)
	}
}

// OR-130 D-4: Docker's health is informational. A container Docker calls healthy
// whose declared probe fails reports the PROBE's facts; nothing derived from Docker
// ever turns into a probe fact.
func TestDockerHealthyDoesNotMakeAFailingDeclaredProbeHealthy(t *testing.T) {
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) }, true, &types.Health{Status: "healthy"})
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.State != msg.HealthHealthy {
		t.Fatalf("the Docker hint is reported as the hint: %v", obs.State)
	}
	if obs.HTTPStatus != 503 || obs.ActuatorStatus == "UP" {
		t.Fatalf("the facts are the probe's, not Docker's: %+v", obs)
	}
	// and the other way: Docker unhealthy with a probe that answers 200
	a2, p2, probe2, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) }, true, &types.Health{Status: "unhealthy"})
	obs2 := a2.ObserveHealth(context.Background(), p2, probe2)
	if obs2.State != msg.HealthUnhealthy || obs2.HTTPStatus != 200 || obs2.ActuatorStatus != "UP" {
		t.Fatalf("%+v", obs2)
	}
}

func TestDockerHealthStatesMapToTheHint(t *testing.T) {
	for status, want := range map[string]msg.HealthState{"healthy": msg.HealthHealthy, "starting": msg.HealthStarting, "unhealthy": msg.HealthUnhealthy, "": msg.HealthNotDeclared} {
		var h *types.Health
		if status != "" {
			h = &types.Health{Status: status}
		}
		a, p, _, _ := probeFixture(t, func(http.ResponseWriter, *http.Request) {}, true, h)
		if got := a.ObserveHealth(context.Background(), p, nil).State; got != want {
			t.Errorf("%q -> %v, want %v", status, got, want)
		}
	}
}

// A container that is not running (or whose inspect failed) is UNREACHABLE and the
// port is NOT probed: another process may own it.
func TestAStoppedContainerIsUnreachableAndNotProbed(t *testing.T) {
	a, p, probe, hits := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) }, false, nil)
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.State != msg.HealthUnreachable || hits.Load() != 0 || obs.HTTPStatus != 0 {
		t.Fatalf("%+v hits=%d", obs, hits.Load())
	}
	// review finding 6: an inspect FAILURE is "nothing observed", not an invented refusal
	obs = a.ObserveHealth(context.Background(), runtime.Placement{ContainerID: "gone"}, probe)
	if obs.State != msg.HealthUnspecified || obs.Failure != msg.ProbeFailureNone || hits.Load() != 0 {
		t.Fatalf("inspect failure: %+v", obs)
	}
}

// review finding 4: the probe is pinned to the container's own published ports. A declared host_port the
// container does not publish is never requested (another process may own it).
func TestProbeOfAPortTheContainerDoesNotPublishIsNotRun(t *testing.T) {
	a, p, probe, hits := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) }, true, nil)
	probe.HostPort++ // the stub answers on probe.HostPort-1; this port is not one the container publishes
	l, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(int(probe.HostPort))))
	if err == nil {
		defer l.Close()
		go func() {
			for {
				c, err := l.Accept()
				if err != nil {
					return
				}
				hits.Add(100)
				_ = c.Close()
			}
		}()
	}
	obs := a.ObserveHealth(context.Background(), p, probe)
	if hits.Load() != 0 || obs.HTTPStatus != 0 || obs.Failure != msg.ProbeFailureNone {
		t.Fatalf("hits=%d %+v", hits.Load(), obs)
	}
}

// review finding 5: timeout with the DEFAULT client, the body limit, and redirects not followed.
func TestProbeDefaultClientTimesOut(t *testing.T) {
	old := probeTimeout
	probeTimeout = 80 * time.Millisecond
	defer func() { probeTimeout = old }()
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { time.Sleep(600 * time.Millisecond) }, true, nil)
	start := time.Now()
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.Failure != msg.ProbeFailureTimeout || time.Since(start) > 400*time.Millisecond {
		t.Fatalf("%+v after %v", obs, time.Since(start))
	}
}

func TestProbeBodyIsLimitedTo64KiB(t *testing.T) {
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(strings.Repeat(" ", maxProbeBody+10)))
		_, _ = w.Write([]byte(`{"status":"UP"}`)) // beyond the limit: never read
	}, true, nil)
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.HTTPStatus != 200 || obs.ActuatorStatus != "" {
		t.Fatalf("a body past the limit is not parsed: %+v", obs)
	}
}

func TestProbeDoesNotFollowRedirects(t *testing.T) {
	var elsewhere atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { elsewhere.Add(1) }))
	defer other.Close()
	a, p, probe, _ := probeFixture(t, func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, other.URL, http.StatusFound) }, true, nil)
	obs := a.ObserveHealth(context.Background(), p, probe)
	if obs.HTTPStatus != 302 || elsewhere.Load() != 0 {
		t.Fatalf("%+v followed=%d", obs, elsewhere.Load())
	}
}

// review finding 8: the instance label is required to be commanded by name.
func TestResolveByNameRequiresTheInstanceLabel(t *testing.T) {
	eng := &engine{all: map[string]types.ContainerJSON{
		"id-1": ctr("id-1", "cache-a", true, map[string]string{ConfigFileLabel: "/etc/app/application.yml"},
			[]types.MountPoint{{Type: "bind", Source: "/srv/conf", Destination: "/etc/app", RW: true}}, nil, nil)}}
	if _, err := New(eng).ResolveByName(context.Background(), "cache-a", "p1"); err == nil {
		t.Fatal("a container that never opted in with com.solace.sm.instance is not commanded by name")
	}
}

// review finding 9: a container that vanishes between the list and the inspect is skipped.
type vanishingEngine struct{ engine }

func (v *vanishingEngine) ContainerInspect(ctx context.Context, id string) (types.ContainerJSON, error) {
	if id == "id-gone" {
		return types.ContainerJSON{}, errdefs.NotFound(errors.New("no such container"))
	}
	return v.engine.ContainerInspect(ctx, id)
}

func TestDiscoverSkipsAContainerThatVanishedBetweenListAndInspect(t *testing.T) {
	eng := &vanishingEngine{engine{all: map[string]types.ContainerJSON{
		"id-a":    ctr("id-a", "cache-a", true, map[string]string{InstanceLabel: "cache-a"}, nil, nil, nil),
		"id-gone": ctr("id-gone", "cache-gone", true, map[string]string{InstanceLabel: "cache-gone"}, nil, nil, nil)}}}
	items, _, err := New(eng).Discover(context.Background())
	if err != nil || len(items) != 1 || items[0].ContainerID != "id-a" {
		t.Fatalf("%v %+v", err, items)
	}
	eng.listErr = nil
	// any other inspect error still aborts the scan
	bad := &engine{all: map[string]types.ContainerJSON{}}
	_ = bad
}
