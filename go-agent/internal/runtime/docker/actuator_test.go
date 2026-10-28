package docker

import (
	"context"
	"errors"
	"testing"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/go-connections/nat"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

func TestActuatorPortPrecedence(t *testing.T) {
	for _, tc := range []struct {
		name      string
		args, env []string
		want      int
	}{
		{"nothing", nil, nil, 8080},
		{"server port env", nil, []string{"SERVER_PORT=9000"}, 9000},
		{"management env beats server env", nil, []string{"SERVER_PORT=9000", "MANAGEMENT_SERVER_PORT=9100"}, 9100},
		{"server arg beats server env", []string{"--server.port=9200"}, []string{"SERVER_PORT=9000"}, 9200},
		{"management arg beats management env", []string{"--management.server.port=9300"}, []string{"MANAGEMENT_SERVER_PORT=9100"}, 9300},
		{"a management port wins whatever its source", []string{"--server.port=9200"}, []string{"MANAGEMENT_SERVER_PORT=9100"}, 9100},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := actuatorPort(tc.args, tc.env)
			if err != nil || got != tc.want {
				t.Fatalf("got %d %v, want %d", got, err, tc.want)
			}
		})
	}
}

func TestActuatorPortRefusesUnusableValues(t *testing.T) {
	for _, tc := range []struct {
		name string
		args []string
		env  []string
	}{
		{"zero", nil, []string{"SERVER_PORT=0"}},
		{"negative", nil, []string{"SERVER_PORT=-1"}},
		{"placeholder", nil, []string{"SERVER_PORT=${PORT}"}},
		{"above 65535", nil, []string{"SERVER_PORT=70000"}},
		{"unusable arg", []string{"--server.port=0"}, nil},
		// A management port that is set and unusable is not a reason to look at the server port.
		{"no fallback from the management port", nil, []string{"SERVER_PORT=9000", "MANAGEMENT_SERVER_PORT=-1"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := actuatorPort(tc.args, tc.env)
			if !errors.Is(err, runtime.ErrNoActuatorPort) {
				t.Fatalf("got %d %v, want ErrNoActuatorPort", got, err)
			}
		})
	}
}

func TestActuatorAddrMapsThePortLikeScrapeAddr(t *testing.T) {
	insp := func(env []string, ports nat.PortMap) types.ContainerJSON {
		return types.ContainerJSON{
			ContainerJSONBase: &types.ContainerJSONBase{ID: "c1", Name: "/java1"},
			Config:            &container.Config{Env: env},
			NetworkSettings:   &types.NetworkSettings{NetworkSettingsBase: types.NetworkSettingsBase{Ports: ports}},
		}
	}
	for _, tc := range []struct {
		name string
		insp types.ContainerJSON
		want string
	}{
		{"the declared port", insp([]string{"SERVER_PORT=9000"}, nat.PortMap{"9000/tcp": {{HostIP: "0.0.0.0", HostPort: "18080"}}}), "127.0.0.1:18080"},
		{"the default port", insp(nil, nat.PortMap{"8080/tcp": {{HostIP: "0.0.0.0", HostPort: "18081"}}}), "127.0.0.1:18081"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := New(&fakeEngine{inspect: map[string]types.ContainerJSON{"c1": tc.insp}})
			got, err := a.ActuatorAddr(context.Background(), runtime.Placement{ContainerID: "c1", Name: "java1"})
			if err != nil || got != tc.want {
				t.Fatalf("got %q %v, want %q", got, err, tc.want)
			}
		})
	}

	// An unusable declared port is refused, not guessed at.
	bad := insp([]string{"SERVER_PORT=${PORT}"}, nat.PortMap{"8080/tcp": {{HostPort: "18080"}}})
	a := New(&fakeEngine{inspect: map[string]types.ContainerJSON{"c1": bad}})
	if _, err := a.ActuatorAddr(context.Background(), runtime.Placement{ContainerID: "c1", Name: "java1"}); !errors.Is(err, runtime.ErrNoActuatorPort) {
		t.Fatalf("want ErrNoActuatorPort, got %v", err)
	}

	// A container whose config cannot be read has no launch args or env to honour: the default port.
	noConfig := types.ContainerJSON{
		ContainerJSONBase: &types.ContainerJSONBase{ID: "c1", Name: "/java1"},
		NetworkSettings:   &types.NetworkSettings{NetworkSettingsBase: types.NetworkSettingsBase{Ports: nat.PortMap{"8080/tcp": {{HostPort: "18082"}}}}},
	}
	a = New(&fakeEngine{inspect: map[string]types.ContainerJSON{"c1": noConfig}})
	if got, err := a.ActuatorAddr(context.Background(), runtime.Placement{ContainerID: "c1", Name: "java1"}); err != nil || got != "127.0.0.1:18082" {
		t.Fatalf("got %q %v", got, err)
	}
}
