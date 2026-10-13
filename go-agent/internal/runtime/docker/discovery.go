package docker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	dockerclient "github.com/docker/docker/client"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// Labels a container sets to opt in (AG-D26). They are deployment configuration (I1).
const (
	LabelPrefix     = "com.solace.sm."
	InstanceLabel   = LabelPrefix + "instance"
	ConfigFileLabel = LabelPrefix + "config-file"

	// MaxDiscoveryItems caps one DiscoveryReport; the excess is counted, not sent.
	MaxDiscoveryItems = 64

	maxProbeBody = 64 << 10
)

// probeTimeout bounds one declared-probe GET; a variable so a test can shorten it.
var probeTimeout = 3 * time.Second

var ErrNoConfigFileLabel = errors.New("docker: the container carries no " + ConfigFileLabel + " label")

// Discover lists every container (running or stopped) labelled
// com.solace.sm.instance and reports FACTS about it: id, name, image reference,
// only the com.solace.sm.* labels (a customer's other labels may carry secrets
// and never leave the host, I6), bind mounts and published ports. It derives no
// placement field and names no environment, organization or target (AG-D26
// item 3, I5). Items are ordered by container name and capped at 64.
func (a *Adapter) Discover(ctx context.Context) ([]msg.DiscoveredContainer, int, error) {
	f := filters.NewArgs()
	f.Add("label", InstanceLabel)
	list, err := a.cli.ContainerList(ctx, container.ListOptions{All: true, Filters: f})
	if err != nil {
		return nil, 0, err
	}
	var out []msg.DiscoveredContainer
	for _, c := range list {
		insp, err := a.cli.ContainerInspect(ctx, c.ID)
		if dockerclient.IsErrNotFound(err) {
			continue // removed between the list and the inspect: it is simply not there any more
		}
		if err != nil {
			return nil, 0, err
		}
		if insp.Config == nil || insp.Config.Labels[InstanceLabel] == "" {
			continue // the filter is advisory for a fake; the label is the contract
		}
		out = append(out, discovered(insp))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ContainerName < out[j].ContainerName })
	truncated := 0
	if len(out) > MaxDiscoveryItems {
		truncated = len(out) - MaxDiscoveryItems
		out = out[:MaxDiscoveryItems]
	}
	return out, truncated, nil
}

func discovered(insp types.ContainerJSON) msg.DiscoveredContainer {
	d := msg.DiscoveredContainer{ContainerID: insp.ID, ContainerName: name(insp), Labels: map[string]string{}}
	if insp.Config != nil {
		d.ImageRef = insp.Config.Image
		for k, v := range insp.Config.Labels {
			if strings.HasPrefix(k, LabelPrefix) {
				d.Labels[k] = v
			}
		}
	}
	for _, m := range insp.Mounts {
		if m.Type == "bind" {
			d.Mounts = append(d.Mounts, msg.DiscoveredMount{Source: m.Source, Destination: m.Destination})
		}
	}
	sort.Slice(d.Mounts, func(i, j int) bool { return d.Mounts[i].Destination < d.Mounts[j].Destination })
	if ns := insp.NetworkSettings; ns != nil {
		for port, bindings := range ns.Ports {
			for _, b := range bindings {
				cp, err1 := strconv.ParseUint(port.Port(), 10, 32)
				hp, err2 := strconv.ParseUint(b.HostPort, 10, 32)
				if err1 != nil || err2 != nil || hp == 0 {
					continue
				}
				d.PublishedPorts = append(d.PublishedPorts, msg.DiscoveredPort{ContainerPort: uint32(cp), HostPort: uint32(hp)})
			}
		}
		sort.Slice(d.PublishedPorts, func(i, j int) bool {
			if d.PublishedPorts[i].ContainerPort != d.PublishedPorts[j].ContainerPort {
				return d.PublishedPorts[i].ContainerPort < d.PublishedPorts[j].ContainerPort
			}
			return d.PublishedPorts[i].HostPort < d.PublishedPorts[j].HostPort
		})
	}
	return d
}

// ResolveByName finds the binding's container by name (AG-D26 item 4): a
// re-created container keeps its placement by name. The config path is the
// com.solace.sm.config-file label's, mapped through a bind mount (the agent
// writes on the host), so that the file the agent writes is the file the DP
// recorded.
func (a *Adapter) ResolveByName(ctx context.Context, containerName, bindingID string) (runtime.Placement, error) {
	f := filters.NewArgs()
	f.Add("name", "^/"+containerName+"$")
	list, err := a.cli.ContainerList(ctx, container.ListOptions{All: true, Filters: f})
	if err != nil {
		return runtime.Placement{}, err
	}
	var ids []string
	for _, c := range list {
		for _, n := range c.Names {
			if strings.TrimPrefix(n, "/") == containerName {
				ids = append(ids, c.ID)
				break
			}
		}
	}
	switch {
	case len(ids) == 0:
		return runtime.Placement{}, fmt.Errorf("%w: name=%s", ErrNotFound, containerName)
	case len(ids) > 1:
		return runtime.Placement{}, fmt.Errorf("%w: name=%s matches %d", ErrAmbiguous, containerName, len(ids))
	}
	insp, err := a.cli.ContainerInspect(ctx, ids[0])
	if err != nil {
		return runtime.Placement{}, err
	}
	file := ""
	if insp.Config != nil {
		if insp.Config.Labels[InstanceLabel] == "" {
			return runtime.Placement{}, fmt.Errorf("%w: container %s never opted in with %s", ErrNotFound, name(insp), InstanceLabel)
		}
		file = insp.Config.Labels[ConfigFileLabel]
	}
	if file == "" || !path.IsAbs(file) {
		return runtime.Placement{}, fmt.Errorf("%w (container %s)", ErrNoConfigFileLabel, name(insp))
	}
	file = path.Clean(file)
	host, err := hostPathFor(insp, path.Dir(file))
	if err != nil {
		return runtime.Placement{}, err
	}
	mech := "label"
	if _, m := declaredConfigDir(insp); m != "" {
		mech = m
	}
	return runtime.Placement{
		BindingID: bindingID, ContainerID: insp.ID, Name: name(insp),
		ConfigPathInContainer: file, HostPath: filepath.Join(host, path.Base(file)),
		Mechanism: mech, UID: -1, GID: -1,
	}, nil
}

// ObserveHealth reports FACTS about the DP-declared probe (OR-130 D-4): a GET on
// the host loopback at probe.HostPort. With probe == nil nothing is probed and
// no request is made (OR-101: nothing is invented, "running" is not "healthy").
// The container's own Docker health state rides along as an informational hint
// and never changes the facts. A container that is not running (or whose inspect
// failed) is UNREACHABLE and is NOT probed: another process may own the port.
func (a *Adapter) ObserveHealth(ctx context.Context, p runtime.Placement, probe *msg.HealthProbe) msg.HealthObservation {
	obs := msg.HealthObservation{ObservedAt: time.Now().UTC()}
	if probe != nil {
		obs.Strength = probe.Strength
	}
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil || insp.ContainerJSONBase == nil || insp.State == nil {
		return msg.HealthObservation{} // nothing observed: no invented refusal (State == HealthUnspecified)
	}
	if !insp.State.Running {
		obs.State = msg.HealthUnreachable
		if probe != nil {
			obs.Failure = msg.ProbeFailureRefused // the container is not there to answer; the DP maps this like a refusal
		}
		return obs
	}
	obs.State = dockerHealth(insp.State)
	if probe == nil {
		return obs
	}
	// The probe is pinned to the container: the declared host port must be one the container itself
	// publishes. Otherwise another process may own the port and its answer would be taken for the container's.
	if !publishesHostPort(insp, probe.HostPort) {
		return obs
	}
	status, actuator, failure := a.runProbe(ctx, probe)
	obs.HTTPStatus, obs.ActuatorStatus, obs.Failure = status, actuator, failure
	return obs
}

func publishesHostPort(insp types.ContainerJSON, hostPort uint32) bool {
	if insp.NetworkSettings == nil {
		return false
	}
	for _, bs := range insp.NetworkSettings.Ports {
		for _, b := range bs {
			if hp, err := strconv.ParseUint(b.HostPort, 10, 32); err == nil && uint32(hp) == hostPort {
				return true
			}
		}
	}
	return false
}

func dockerHealth(st *types.ContainerState) msg.HealthState {
	if st.Health == nil {
		return msg.HealthNotDeclared
	}
	switch st.Health.Status {
	case "healthy":
		return msg.HealthHealthy
	case "starting":
		return msg.HealthStarting
	case "unhealthy":
		return msg.HealthUnhealthy
	}
	return msg.HealthNotDeclared
}

func (a *Adapter) runProbe(ctx context.Context, probe *msg.HealthProbe) (status uint32, actuator string, failure msg.ProbeFailure) {
	client := a.probeClient
	if client == nil {
		client = &http.Client{Timeout: probeTimeout, Transport: &http.Transport{Proxy: nil}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	u := "http://" + net.JoinHostPort("127.0.0.1", strconv.FormatUint(uint64(probe.HostPort), 10)) + probe.Path
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return 0, "", msg.ProbeFailureRefused
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, "", failureOf(err)
	}
	defer resp.Body.Close()
	status = uint32(resp.StatusCode)
	if probe.Kind == msg.ProbeActuatorHealth {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, maxProbeBody))
		var doc struct {
			Status string `json:"status"`
		}
		if json.Unmarshal(body, &doc) == nil {
			actuator = doc.Status
		}
	}
	return status, actuator, msg.ProbeFailureNone
}

// failureOf: why there was no response. Only two reasons exist on the wire; a
// timeout is a timeout, and anything else that kept the probe from getting an
// answer (refused, reset, unreachable) is reported as REFUSED.
func failureOf(err error) msg.ProbeFailure {
	var ne net.Error
	if errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &ne) && ne.Timeout()) {
		return msg.ProbeFailureTimeout
	}
	return msg.ProbeFailureRefused
}
