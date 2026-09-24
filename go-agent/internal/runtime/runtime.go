// Package runtime is the seam between the command reducer and the host: the
// agent decides what to do, an Adapter does it to a container.
package runtime

import (
	"context"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

type Placement struct {
	BindingID             string
	ContainerID           string
	Name                  string // replica_ref (RFC 1123 hostname grammar)
	ConfigPathInContainer string
	HostPath              string
	Mechanism             string
	UID, GID              int
}

type ReplicaFacts struct {
	ReplicaRef  string
	Incarnation string // container ID
	Running     bool
	StartedAt   time.Time
	ExitCode    int
	ImageDigest string
}

type Progress func(step msg.ProgressStep) error

type WriteResult struct{ Hash string }

type Adapter interface {
	Resolve(ctx context.Context, bindingID string) (Placement, error)
	Preflight(ctx context.Context, p Placement) (msg.PreflightResult, error)
	WriteConfig(ctx context.Context, p Placement, payload []byte, sha256 string, progress Progress) (WriteResult, error)
	Restart(ctx context.Context, p Placement) error
	Stop(ctx context.Context, p Placement) error
	Observe(ctx context.Context, p Placement) (ReplicaFacts, error)
}

type OpResult struct {
	Outcome       msg.Outcome
	Detail        msg.AckDetail
	Facts         *ReplicaFacts
	EffectiveHash string
	Placement     *Placement
}
