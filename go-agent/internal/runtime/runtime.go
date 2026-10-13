// Package runtime is the seam between the command reducer and the host: the
// agent decides what to do, an Adapter does it to a container.
package runtime

import (
	"context"
	"errors"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Sentinels the adapters wrap with %w. They live here so the agent can template
// them without importing an adapter (review finding 15).
var (
	ErrContainerNotFound  = errors.New("docker: no container carries the binding label")
	ErrContainerAmbiguous = errors.New("docker: more than one container carries the binding label")
	ErrNoDeclaredPath     = errors.New("docker: the container declares no external config location")
	ErrNoBindMount        = errors.New("docker: the declared config directory is not a bind mount")
	ErrPortUnreachable    = errors.New("docker: port is neither published nor reachable on a container network")
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

// Progress reports that a write reached a journal step. It is asynchronous:
// the agent enqueues the step for its loop and returns at once, and the
// journal record is written when the loop gets to it. So the journal may LAG
// the filesystem but never LEADS it — PERSISTED is reported only after the
// rename and the parent-directory fsync. A lagging journal is safe because
// writes are content-hash idempotent: on resume, a target that already holds
// the bytes is reported PERSISTED without being rewritten.
type Progress func(step msg.ProgressStep) error

type WriteResult struct{ Hash string }

type Adapter interface {
	Resolve(ctx context.Context, bindingID string) (Placement, error)
	Preflight(ctx context.Context, p Placement) (msg.PreflightResult, error)
	WriteConfig(ctx context.Context, p Placement, payload []byte, sha256 string, progress Progress) (WriteResult, error)
	Restart(ctx context.Context, p Placement) error
	Stop(ctx context.Context, p Placement) error
	Observe(ctx context.Context, p Placement) (ReplicaFacts, error)
	// ScrapeAddr is the host:port the agent fetches a container port from.
	ScrapeAddr(ctx context.Context, p Placement, port int) (string, error)
}

type OpResult struct {
	Outcome       msg.Outcome
	Detail        msg.AckDetail
	Facts         *ReplicaFacts
	EffectiveHash string
	Placement     *Placement
	// Health is the probe facts observed after the operation, nil when the
	// adapter cannot observe health or nothing was observed.
	Health *msg.HealthObservation
}

// HealthObserver is implemented by an adapter that can run the DP-declared
// health probe (OR-130 D-4). With probe == nil nothing is probed (OR-101): the
// observation then carries only the container's own health state as a hint.
// The facts come from the probe alone; the container's Docker health state rides
// along informationally and never changes them.
type HealthObserver interface {
	ObserveHealth(ctx context.Context, p Placement, probe *msg.HealthProbe) msg.HealthObservation
}

// ErrNoConditionalWrite: the adapter behind a forwarding wrapper has no conditional writer; the caller falls
// back to WriteConfig (after its own precondition read).
var ErrNoConditionalWrite = errors.New("runtime: the adapter cannot write conditionally")

// ConditionalWriter is implemented by an adapter that evaluates OPERATION_REMATERIALIZE's precondition on
// the very read the write is based on (closing the check-then-write window). When the file is not the
// expected one it returns an error that wraps configwriter.ErrTargetMoved and writes nothing.
type ConditionalWriter interface {
	WriteConfigIf(ctx context.Context, p Placement, payload []byte, sha256 string, expectedCurrent *string, progress Progress) (WriteResult, error)
}

// Discoverer is implemented by an adapter that can list the containers that
// opted in with the com.solace.sm.instance label (AG-D26). Truncated counts the
// items beyond the 64-item cap of one report.
type Discoverer interface {
	Discover(ctx context.Context) (items []msg.DiscoveredContainer, truncated int, err error)
}

// NameResolver finds a binding's container by name (AG-D26 item 4: a re-created
// container keeps its placement by name).
type NameResolver interface {
	ResolveByName(ctx context.Context, containerName, bindingID string) (Placement, error)
}
