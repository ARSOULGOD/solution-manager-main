// Package docker implements runtime.Adapter against the Docker Engine API.
// Binding → container resolution is AG-D20: the label com.solace.sm.binding-id
// identifies the container, the config path comes from the artifact's own
// launch arguments or environment (SM-D21: SM discovers, never invents), and
// the host path is that container path mapped through a bind mount.
package docker

import (
	"context"
	"errors"
	"fmt"
	"maps"
	"net"
	"path"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	dockerclient "github.com/docker/docker/client"
	"github.com/docker/go-connections/nat"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

const (
	BindingLabel   = "com.solace.sm.binding-id"
	configFileName = "application.yml"

	argAdditionalLocation = "--spring.config.additional-location="
	argConfigLocation     = "--spring.config.location="
	envAdditionalLocation = "SPRING_CONFIG_ADDITIONAL_LOCATION"
	envConfigLocation     = "SPRING_CONFIG_LOCATION"
	envApplicationJSON    = "SPRING_APPLICATION_JSON"
)

var (
	ErrNotFound         = errors.New("docker: no container carries the binding label")
	ErrAmbiguous        = errors.New("docker: more than one container carries the binding label")
	ErrNoDeclaredPath   = errors.New("docker: the container declares no external config location")
	ErrNoBindMount      = errors.New("docker: the declared config directory is not a bind mount")
	ErrPortUnreachable  = errors.New("docker: port is neither published nor reachable on a container network")
)

type engineAPI interface {
	ContainerList(ctx context.Context, opts container.ListOptions) ([]types.Container, error)
	ContainerInspect(ctx context.Context, id string) (types.ContainerJSON, error)
	ContainerRestart(ctx context.Context, id string, opts container.StopOptions) error
	ContainerStop(ctx context.Context, id string, opts container.StopOptions) error
	Close() error
}

type Adapter struct{ cli engineAPI }

func New(cli engineAPI) *Adapter { return &Adapter{cli: cli} }

func NewFromEnv() (*Adapter, func() error, error) {
	cli, err := dockerclient.NewClientWithOpts(dockerclient.FromEnv, dockerclient.WithAPIVersionNegotiation())
	if err != nil {
		return nil, nil, err
	}
	return &Adapter{cli: cli}, cli.Close, nil
}

func (a *Adapter) Resolve(ctx context.Context, bindingID string) (runtime.Placement, error) {
	f := filters.NewArgs()
	f.Add("label", BindingLabel+"="+bindingID)
	list, err := a.cli.ContainerList(ctx, container.ListOptions{All: true, Filters: f})
	if err != nil {
		return runtime.Placement{}, err
	}
	switch {
	case len(list) == 0:
		return runtime.Placement{}, fmt.Errorf("%w: %s=%s", ErrNotFound, BindingLabel, bindingID)
	case len(list) > 1:
		return runtime.Placement{}, fmt.Errorf("%w: %s=%s matches %d", ErrAmbiguous, BindingLabel, bindingID, len(list))
	}
	insp, err := a.cli.ContainerInspect(ctx, list[0].ID)
	if err != nil {
		return runtime.Placement{}, err
	}
	dir, mechanism := declaredConfigDir(insp)
	if dir == "" {
		return runtime.Placement{}, fmt.Errorf("%w (container %s)", ErrNoDeclaredPath, name(insp))
	}
	host, err := hostPathFor(insp, dir)
	if err != nil {
		return runtime.Placement{}, err
	}
	return runtime.Placement{
		BindingID: bindingID, ContainerID: insp.ID, Name: name(insp),
		ConfigPathInContainer: path.Join(dir, configFileName),
		HostPath:              filepath.Join(host, configFileName),
		Mechanism:             mechanism, UID: -1, GID: -1,
	}, nil
}

func name(insp types.ContainerJSON) string { return strings.TrimPrefix(insp.Name, "/") }

// declaredConfigDir reads the artifact's own launch spec; SM never invents one.
func declaredConfigDir(insp types.ContainerJSON) (dir, mechanism string) {
	if insp.Config == nil {
		return "", ""
	}
	args := append(append([]string{}, insp.Config.Entrypoint...), insp.Config.Cmd...)
	for _, a := range args {
		if v, ok := cut(a, argAdditionalLocation); ok {
			return normalizeDir(v), "additional-location"
		}
		if v, ok := cut(a, argConfigLocation); ok {
			return normalizeDir(v), "overwrite-in-place"
		}
	}
	for _, e := range insp.Config.Env {
		k, v, found := strings.Cut(e, "=")
		if !found {
			continue
		}
		switch k {
		case envAdditionalLocation:
			return normalizeDir(v), "additional-location"
		case envConfigLocation:
			return normalizeDir(v), "overwrite-in-place"
		}
	}
	return "", ""
}

func cut(arg, prefix string) (string, bool) {
	if strings.HasPrefix(arg, prefix) {
		return strings.TrimPrefix(arg, prefix), true
	}
	return "", false
}

// normalizeDir turns "file:/etc/app/" or "/etc/app/application.yml" into "/etc/app".
func normalizeDir(v string) string {
	v = strings.TrimPrefix(v, "file:")
	if i := strings.IndexByte(v, ','); i >= 0 {
		v = v[:i] // only the first location is SM's
	}
	if strings.HasSuffix(v, "/") {
		return path.Clean(v)
	}
	if path.Ext(v) != "" {
		return path.Dir(v)
	}
	return path.Clean(v)
}

// hostPathFor maps a container directory through a bind mount.
func hostPathFor(insp types.ContainerJSON, containerDir string) (string, error) {
	best := ""
	bestLen := -1
	for _, m := range insp.Mounts {
		if m.Type != "bind" {
			continue
		}
		dest := path.Clean(m.Destination)
		if dest == containerDir || strings.HasPrefix(containerDir+"/", dest+"/") {
			if len(dest) > bestLen {
				rel := strings.TrimPrefix(containerDir, dest)
				best, bestLen = filepath.Join(m.Source, filepath.FromSlash(rel)), len(dest)
			}
		}
	}
	if best == "" {
		return "", fmt.Errorf("%w: %s", ErrNoBindMount, containerDir)
	}
	return best, nil
}

func (a *Adapter) Preflight(ctx context.Context, p runtime.Placement) (msg.PreflightResult, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return msg.PreflightResult{}, err
	}
	res := msg.PreflightResult{
		ContainerID: p.ContainerID, ImageDigest: imageDigest(insp),
		ConfigPathInContainer: p.ConfigPathInContainer, HostPath: p.HostPath, Mechanism: p.Mechanism,
		CompetingSources: competingSources(insp), Writable: writable(insp, p),
	}
	return res, nil
}

func competingSources(insp types.ContainerJSON) []string {
	var out []string
	if insp.Config == nil {
		return nil
	}
	for _, e := range insp.Config.Env {
		k, _, _ := strings.Cut(e, "=")
		switch k {
		case envApplicationJSON, envConfigLocation:
			out = append(out, k)
		}
	}
	return out
}

func writable(insp types.ContainerJSON, p runtime.Placement) bool {
	dir := path.Dir(p.ConfigPathInContainer)
	for _, m := range insp.Mounts {
		if m.Type == "bind" && (path.Clean(m.Destination) == dir || strings.HasPrefix(dir+"/", path.Clean(m.Destination)+"/")) {
			return m.RW
		}
	}
	return false
}

func imageDigest(insp types.ContainerJSON) string {
	if insp.ContainerJSONBase == nil {
		return ""
	}
	return insp.Image
}

func (a *Adapter) WriteConfig(_ context.Context, p runtime.Placement, payload []byte, sha string, progress runtime.Progress) (runtime.WriteResult, error) {
	return configwriter.Write(p.HostPath, payload, sha, p.UID, p.GID, progress)
}

func (a *Adapter) Restart(ctx context.Context, p runtime.Placement) error {
	return a.cli.ContainerRestart(ctx, p.ContainerID, container.StopOptions{})
}

func (a *Adapter) Stop(ctx context.Context, p runtime.Placement) error {
	return a.cli.ContainerStop(ctx, p.ContainerID, container.StopOptions{})
}

func (a *Adapter) Observe(ctx context.Context, p runtime.Placement) (runtime.ReplicaFacts, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return runtime.ReplicaFacts{}, err
	}
	f := runtime.ReplicaFacts{ReplicaRef: name(insp), Incarnation: insp.ID, ImageDigest: imageDigest(insp)}
	if insp.State != nil {
		f.Running, f.ExitCode = insp.State.Running, insp.State.ExitCode
		if t, err := time.Parse(time.RFC3339Nano, insp.State.StartedAt); err == nil {
			f.StartedAt = t.UTC()
		}
	}
	return f, nil
}

// ScrapeAddr is where a container port is fetched from (spec §3.4): its
// published binding first — on Docker Desktop the only way in from the host
// (docs.docker.com, "Networking on Docker Desktop", fetched 2026-10-01) —
// then its IP, for an agent attached to the container's own network, which is
// the control architecture.md §10 prescribes for Cache.
func (a *Adapter) ScrapeAddr(ctx context.Context, p runtime.Placement, port int) (string, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return "", err
	}
	if ns := insp.NetworkSettings; ns != nil {
		for _, b := range ns.Ports[nat.Port(fmt.Sprintf("%d/tcp", port))] {
			if b.HostPort == "" {
				continue
			}
			host := b.HostIP
			if host == "" || host == "0.0.0.0" || host == "::" {
				host = "127.0.0.1"
			}
			return net.JoinHostPort(host, b.HostPort), nil
		}
		for _, name := range slices.Sorted(maps.Keys(ns.Networks)) {
			if ep := ns.Networks[name]; ep != nil && ep.IPAddress != "" {
				return net.JoinHostPort(ep.IPAddress, strconv.Itoa(port)), nil
			}
		}
	}
	return "", fmt.Errorf("%w: %d (container %s)", ErrPortUnreachable, port, p.Name)
}
