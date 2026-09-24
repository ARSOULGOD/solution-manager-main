package docker

import "context"

// Info is a snapshot of a container's identity and runtime state.
type Info struct {
	Name   string
	ID     string
	State  string // e.g. running, exited, created
	Status string // human-readable status from Docker (e.g. "Up 2 minutes")
}

// Controller manages lifecycle of pre-created containers by name.
type Controller interface {
	Start(ctx context.Context, name string) error
	Stop(ctx context.Context, name string) error
	Restart(ctx context.Context, name string) error
	Status(ctx context.Context, name string) (Info, error)
}
