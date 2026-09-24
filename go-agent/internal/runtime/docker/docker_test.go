package docker

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

type fakeEngine struct {
	list      []types.Container
	inspect   map[string]types.ContainerJSON
	restarted []string
	stopped   []string
	failOp    error
}

func (f *fakeEngine) ContainerList(_ context.Context, opts container.ListOptions) ([]types.Container, error) {
	want := opts.Filters.Get("label")
	if len(want) == 0 {
		return f.list, nil
	}
	var out []types.Container
	for _, c := range f.list {
		for _, w := range want {
			if c.Labels[BindingLabel] != "" && (w == BindingLabel+"="+c.Labels[BindingLabel]) {
				out = append(out, c)
			}
		}
	}
	return out, nil
}
func (f *fakeEngine) ContainerInspect(_ context.Context, id string) (types.ContainerJSON, error) {
	c, ok := f.inspect[id]
	if !ok {
		return types.ContainerJSON{}, errors.New("no such container")
	}
	return c, nil
}
func (f *fakeEngine) ContainerRestart(_ context.Context, id string, _ container.StopOptions) error {
	if f.failOp != nil {
		return f.failOp
	}
	f.restarted = append(f.restarted, id)
	c := f.inspect[id]
	c.State.StartedAt = time.Now().Add(time.Second).Format(time.RFC3339Nano)
	c.State.Running = true
	f.inspect[id] = c
	return nil
}
func (f *fakeEngine) ContainerStop(_ context.Context, id string, _ container.StopOptions) error {
	if f.failOp != nil {
		return f.failOp
	}
	f.stopped = append(f.stopped, id)
	c := f.inspect[id]
	c.State.Running = false
	f.inspect[id] = c
	return nil
}
func (f *fakeEngine) Close() error { return nil }

var _ = filters.NewArgs

func engineWith(t *testing.T, hostDir string) (*fakeEngine, string) {
	t.Helper()
	id := "abc123def456"
	return &fakeEngine{
		list: []types.Container{{ID: id, Names: []string{"/java1"}, Labels: map[string]string{BindingLabel: "b1"}}},
		inspect: map[string]types.ContainerJSON{id: {
			ContainerJSONBase: &types.ContainerJSONBase{ID: id, Name: "/java1", Image: "sha256:img",
				State:      &types.ContainerState{Running: true, StartedAt: time.Now().Format(time.RFC3339Nano)},
				HostConfig: &container.HostConfig{}},
			Config: &container.Config{
				Cmd: []string{"java", "-jar", "/app.jar", "--spring.config.additional-location=file:/etc/app/"},
				Env: []string{"FOO=bar"},
			},
			Mounts: []types.MountPoint{{Type: "bind", Source: hostDir, Destination: "/etc/app", RW: true}},
		}},
	}, id
}

func TestResolveFindsContainerPathAndMount(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	a := New(eng)
	p, err := a.Resolve(context.Background(), "b1")
	if err != nil {
		t.Fatal(err)
	}
	if p.ContainerID != id || p.Name != "java1" {
		t.Fatalf("%+v", p)
	}
	if p.ConfigPathInContainer != "/etc/app/application.yml" {
		t.Fatalf("config path %q", p.ConfigPathInContainer)
	}
	if p.HostPath != filepath.Join(hostDir, "application.yml") {
		t.Fatalf("host path %q", p.HostPath)
	}
	if p.Mechanism != "additional-location" {
		t.Fatal(p.Mechanism)
	}
}

func TestResolveFromEnvVariable(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	c := eng.inspect[id]
	c.Config.Cmd = []string{"java", "-jar", "/app.jar"}
	c.Config.Env = []string{"SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/"}
	eng.inspect[id] = c
	p, err := New(eng).Resolve(context.Background(), "b1")
	if err != nil || p.HostPath != filepath.Join(hostDir, "application.yml") {
		t.Fatalf("%v %+v", err, p)
	}
}

func TestResolveRefusesWithoutDeclaredPath(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	c := eng.inspect[id]
	c.Config.Cmd = []string{"java", "-jar", "/app.jar"}
	c.Config.Env = nil
	eng.inspect[id] = c
	if _, err := New(eng).Resolve(context.Background(), "b1"); !errors.Is(err, ErrNoDeclaredPath) {
		t.Fatalf("want ErrNoDeclaredPath, got %v", err)
	}
}

func TestResolveRefusesWithoutBindMount(t *testing.T) {
	eng, id := engineWith(t, t.TempDir())
	c := eng.inspect[id]
	c.Mounts = []types.MountPoint{{Type: "volume", Name: "vol", Destination: "/etc/app"}}
	eng.inspect[id] = c
	if _, err := New(eng).Resolve(context.Background(), "b1"); !errors.Is(err, ErrNoBindMount) {
		t.Fatalf("want ErrNoBindMount, got %v", err)
	}
}

func TestResolveAmbiguousAndMissing(t *testing.T) {
	eng, _ := engineWith(t, t.TempDir())
	eng.list = append(eng.list, types.Container{ID: "second", Names: []string{"/java2"}, Labels: map[string]string{BindingLabel: "b1"}})
	if _, err := New(eng).Resolve(context.Background(), "b1"); !errors.Is(err, ErrAmbiguous) {
		t.Fatalf("want ErrAmbiguous, got %v", err)
	}
	eng2, _ := engineWith(t, t.TempDir())
	if _, err := New(eng2).Resolve(context.Background(), "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestPreflightReportsFacts(t *testing.T) {
	hostDir := t.TempDir()
	eng, _ := engineWith(t, hostDir)
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	pr, err := a.Preflight(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	if !pr.Writable || pr.HostPath != p.HostPath || pr.ConfigPathInContainer != p.ConfigPathInContainer || pr.Mechanism != "additional-location" {
		t.Fatalf("%+v", pr)
	}
}

func TestPreflightReportsCompetingSources(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	c := eng.inspect[id]
	c.Config.Env = append(c.Config.Env, "SPRING_APPLICATION_JSON={}", "SPRING_CONFIG_LOCATION=file:/other/")
	eng.inspect[id] = c
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	pr, _ := a.Preflight(context.Background(), p)
	if len(pr.CompetingSources) != 2 {
		t.Fatalf("competing sources: %v", pr.CompetingSources)
	}
}

func TestRestartObservesNewStartedAt(t *testing.T) {
	eng, _ := engineWith(t, t.TempDir())
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	before, _ := a.Observe(context.Background(), p)
	if err := a.Restart(context.Background(), p); err != nil {
		t.Fatal(err)
	}
	after, _ := a.Observe(context.Background(), p)
	if !after.Running || !after.StartedAt.After(before.StartedAt) {
		t.Fatalf("before=%v after=%v", before.StartedAt, after.StartedAt)
	}
	if after.Incarnation != p.ContainerID || after.ReplicaRef != "java1" {
		t.Fatalf("%+v", after)
	}
}

func TestStopObservesNotRunning(t *testing.T) {
	eng, _ := engineWith(t, t.TempDir())
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	if err := a.Stop(context.Background(), p); err != nil {
		t.Fatal(err)
	}
	f, _ := a.Observe(context.Background(), p)
	if f.Running {
		t.Fatal("still running")
	}
}

func TestWriteConfigDelegatesToWriter(t *testing.T) {
	hostDir := t.TempDir()
	eng, _ := engineWith(t, hostDir)
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	var steps []msg.ProgressStep
	res, err := a.WriteConfig(context.Background(), p, []byte("x: 1\n"), sha("x: 1\n"), filepath.Join(t.TempDir(), "prev"),
		func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(p.HostPath)
	if string(got) != "x: 1\n" || res.Hash != sha("x: 1\n") || len(steps) != 2 {
		t.Fatalf("%q %+v %v", got, res, steps)
	}
}

func sha(s string) string { return configwriter.SHA256([]byte(s)) }
