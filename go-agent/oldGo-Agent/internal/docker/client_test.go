package docker

import (
	"context"
	"errors"
	"fmt"
	"testing"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
)

type fakeEngine struct {
	startErr   error
	stopErr    error
	restartErr error
	inspectErr error
	inspect    types.ContainerJSON
	started    []string
	stopped    []string
	restarted  []string
}

func (f *fakeEngine) ContainerStart(_ context.Context, id string, _ container.StartOptions) error {
	f.started = append(f.started, id)
	return f.startErr
}

func (f *fakeEngine) ContainerStop(_ context.Context, id string, _ container.StopOptions) error {
	f.stopped = append(f.stopped, id)
	return f.stopErr
}

func (f *fakeEngine) ContainerRestart(_ context.Context, id string, _ container.StopOptions) error {
	f.restarted = append(f.restarted, id)
	return f.restartErr
}

func (f *fakeEngine) ContainerInspect(_ context.Context, id string) (types.ContainerJSON, error) {
	if f.inspectErr != nil {
		return types.ContainerJSON{}, f.inspectErr
	}
	out := f.inspect
	if out.Name == "" {
		out.Name = "/" + id
	}
	if out.ID == "" {
		out.ID = "id-" + id
	}
	return out, nil
}

func (f *fakeEngine) Close() error { return nil }

type notFoundError struct{}

func (notFoundError) Error() string  { return "no such container" }
func (notFoundError) NotFound() bool { return true }

func TestClient_StartStopRestart(t *testing.T) {
	fake := &fakeEngine{}
	c := NewForTest(fake)
	ctx := context.Background()

	if err := c.Start(ctx, "java1"); err != nil {
		t.Fatalf("Start: %v", err)
	}
	if err := c.Stop(ctx, "java1"); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if err := c.Restart(ctx, "java2"); err != nil {
		t.Fatalf("Restart: %v", err)
	}
	if got := fake.started; len(got) != 1 || got[0] != "java1" {
		t.Fatalf("started=%v", got)
	}
	if got := fake.stopped; len(got) != 1 || got[0] != "java1" {
		t.Fatalf("stopped=%v", got)
	}
	if got := fake.restarted; len(got) != 1 || got[0] != "java2" {
		t.Fatalf("restarted=%v", got)
	}
}

func TestClient_Status(t *testing.T) {
	fake := &fakeEngine{
		inspect: types.ContainerJSON{
			ContainerJSONBase: &types.ContainerJSONBase{
				ID:   "abc123",
				Name: "/java1",
				State: &types.ContainerState{
					Status:    "running",
					StartedAt: "2026-09-12T00:00:00Z",
				},
			},
		},
	}
	c := NewForTest(fake)
	info, err := c.Status(context.Background(), "java1")
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	if info.Name != "java1" || info.ID != "abc123" || info.State != "running" {
		t.Fatalf("info=%+v", info)
	}
	if info.Status == "" {
		t.Fatal("expected non-empty Status")
	}
}

func TestClient_NotFound(t *testing.T) {
	fake := &fakeEngine{startErr: notFoundError{}}
	c := NewForTest(fake)
	err := c.Start(context.Background(), "missing")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("got %v, want ErrNotFound", err)
	}
}

func TestClient_DaemonUnavailable(t *testing.T) {
	fake := &fakeEngine{inspectErr: errors.New("Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?")}
	c := NewForTest(fake)
	_, err := c.Status(context.Background(), "java1")
	if !errors.Is(err, ErrDaemonUnavailable) {
		t.Fatalf("got %v, want ErrDaemonUnavailable", err)
	}
}

func TestClient_Permission(t *testing.T) {
	fake := &fakeEngine{stopErr: errors.New("permission denied while trying to connect to the Docker daemon socket")}
	c := NewForTest(fake)
	err := c.Stop(context.Background(), "java1")
	if !errors.Is(err, ErrPermission) {
		t.Fatalf("got %v, want ErrPermission", err)
	}
}

var _ Controller = (*Client)(nil)

func TestWrapOpMessage(t *testing.T) {
	err := wrapOp("start", "java1", ErrNotFound)
	want := fmt.Sprintf("start %q: %s", "java1", ErrNotFound)
	if err == nil || err.Error() != want {
		t.Fatalf("got %v want %s", err, want)
	}
}
