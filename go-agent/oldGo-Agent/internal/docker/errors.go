package docker

import (
	"errors"
	"fmt"
)

var (
	// ErrNotFound means no container with the given name/id exists.
	ErrNotFound = errors.New("container not found")
	// ErrDaemonUnavailable means the Docker engine could not be reached.
	ErrDaemonUnavailable = errors.New("docker daemon unavailable")
	// ErrPermission means the client lacks access to the Docker socket/API.
	ErrPermission = errors.New("docker permission denied")
)

func wrapOp(op, name string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%s %q: %w", op, name, err)
}
