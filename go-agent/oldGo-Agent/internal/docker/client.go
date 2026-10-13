package docker

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"os"
	"strings"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
)

// engineAPI is the subset of the Docker client used by Client (mockable in tests).
type engineAPI interface {
	ContainerStart(ctx context.Context, containerID string, options container.StartOptions) error
	ContainerStop(ctx context.Context, containerID string, options container.StopOptions) error
	ContainerRestart(ctx context.Context, containerID string, options container.StopOptions) error
	ContainerInspect(ctx context.Context, containerID string) (types.ContainerJSON, error)
	Close() error
}

// Client implements Controller using the Docker Engine API.
type Client struct {
	api engineAPI
}

// NewFromEnv builds a Client from DOCKER_HOST / default socket env.
func NewFromEnv() (*Client, error) {
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrDaemonUnavailable, err)
	}
	return &Client{api: cli}, nil
}

// NewForTest constructs a Client with a fake engineAPI (unit tests).
func NewForTest(api engineAPI) *Client {
	return &Client{api: api}
}

// Close releases the underlying Docker client.
func (c *Client) Close() error {
	if c.api == nil {
		return nil
	}
	return c.api.Close()
}

func (c *Client) Start(ctx context.Context, name string) error {
	err := c.api.ContainerStart(ctx, name, container.StartOptions{})
	return wrapOp("start", name, mapError(err))
}

func (c *Client) Stop(ctx context.Context, name string) error {
	err := c.api.ContainerStop(ctx, name, container.StopOptions{})
	return wrapOp("stop", name, mapError(err))
}

func (c *Client) Restart(ctx context.Context, name string) error {
	err := c.api.ContainerRestart(ctx, name, container.StopOptions{})
	return wrapOp("restart", name, mapError(err))
}

func (c *Client) Status(ctx context.Context, name string) (Info, error) {
	insp, err := c.api.ContainerInspect(ctx, name)
	if err != nil {
		return Info{}, wrapOp("status", name, mapError(err))
	}
	info := Info{
		Name: name,
		ID:   insp.ID,
	}
	if insp.Name != "" {
		info.Name = strings.TrimPrefix(insp.Name, "/")
	}
	if insp.State != nil {
		info.State = insp.State.Status
		info.Status = formatStatus(insp)
	}
	return info, nil
}

func formatStatus(insp types.ContainerJSON) string {
	if insp.State == nil {
		return ""
	}
	st := insp.State
	if st.Status == "running" && st.StartedAt != "" {
		return fmt.Sprintf("running (started %s)", st.StartedAt)
	}
	if st.Status == "exited" {
		return fmt.Sprintf("exited (%d)", st.ExitCode)
	}
	return st.Status
}

func mapError(err error) error {
	if err == nil {
		return nil
	}
	if cerrdefs.IsNotFound(err) || client.IsErrNotFound(err) || isNotFoundMessage(err) {
		return ErrNotFound
	}
	if errors.Is(err, fs.ErrPermission) || isPermission(err) {
		return fmt.Errorf("%w: %v", ErrPermission, err)
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) || errors.Is(err, os.ErrNotExist) || isDaemonUnavailable(err) {
		return fmt.Errorf("%w: %v", ErrDaemonUnavailable, err)
	}
	return err
}

func isNotFoundMessage(err error) bool {
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "no such container") || strings.Contains(msg, "not found")
}

func isPermission(err error) bool {
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "permission denied") || strings.Contains(msg, "access denied")
}

func isDaemonUnavailable(err error) bool {
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "cannot connect") ||
		strings.Contains(msg, "is the docker daemon running") ||
		strings.Contains(msg, "no such file or directory") ||
		strings.Contains(msg, "connection refused")
}
