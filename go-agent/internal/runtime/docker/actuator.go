package docker

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// ActuatorAddr is where the placement's Spring Boot actuator answers: the port its own launch spec declares,
// mapped to the host as ScrapeAddr maps any container port. SM discovers the port, it never invents one (SM-D21).
func (a *Adapter) ActuatorAddr(ctx context.Context, p runtime.Placement) (string, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return "", err
	}
	var args, env []string
	if insp.Config != nil {
		args = append(append([]string{}, insp.Config.Entrypoint...), insp.Config.Cmd...)
		env = insp.Config.Env
	}
	port, err := actuatorPort(args, env)
	if err != nil {
		return "", err
	}
	return a.ScrapeAddr(ctx, p, port)
}

// actuatorPort is the port the actuator listens on, by Spring Boot's own precedence: a management port wins
// whatever its source (the --management.server.port= argument, else MANAGEMENT_SERVER_PORT), then the server
// port the same way, then 8080. A port that is declared but not a number in 1-65535 (a "${PORT}" placeholder,
// 0, -1) is refused, never replaced by a lower-precedence one: the MI would not be listening there.
func actuatorPort(args, env []string) (int, error) {
	for _, src := range []struct{ arg, env string }{
		{argManagementPort, envManagementPort},
		{argServerPort, envServerPort},
	} {
		v, key, ok := declared(args, env, src.arg, src.env)
		if !ok {
			continue
		}
		port, err := strconv.Atoi(v)
		if err != nil || port < 1 || port > 65535 {
			return 0, fmt.Errorf("%w (%s)", ErrNoActuatorPort, key)
		}
		return port, nil
	}
	return defaultActuatorPort, nil
}

// declared is the value of a launch argument, else of an environment variable, and the name it was declared by.
func declared(args, env []string, argPrefix, envKey string) (value, key string, ok bool) {
	for _, a := range args {
		if v, found := cut(a, argPrefix); found {
			return v, strings.TrimSuffix(argPrefix, "="), true
		}
	}
	for _, e := range env {
		if k, v, found := strings.Cut(e, "="); found && k == envKey {
			return v, envKey, true
		}
	}
	return "", "", false
}
