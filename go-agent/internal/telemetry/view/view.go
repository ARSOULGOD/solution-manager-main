// Package view is what the control loop publishes for the telemetry pipeline
// (AG-D21): the session, the lease's bindings and the DP-minted ids, as one
// immutable value replaced whole, so a reader never touches reducer state
// and never sees a session from one publish and ids from another.
package view

import (
	"context"
	"sync"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Stream is the Telemetry RPC as the sender uses it; *wire.TelemetryStream
// satisfies it.
type Stream interface {
	Send(msg.TelemetryBatch) error
	Recv() (msg.TelemetryInbound, error)
	Close() error
}

// SessionInfo is present only while the session is ACTIVE. Open opens the
// Telemetry stream on that session's own connection.
type SessionInfo struct {
	ID     string
	Limits msg.Limits
	Open   func(ctx context.Context) (Stream, error)
}

// IDLookup resolves a sample's identity to its DP-minted ids; reports.IDTable
// satisfies it.
type IDLookup interface {
	IDs(binding, replicaRef, incarnation string) (replicaID, incarnationID string, ok bool)
}

type Snapshot struct {
	Session  *SessionInfo
	Bindings map[string]bool // the current lease's binding_ids
	IDs      IDLookup
	Halted   bool
}

func (s Snapshot) InLease(binding string) bool { return !s.Halted && s.Bindings[binding] }

func (s Snapshot) Lookup(binding, replicaRef, incarnation string) (string, string, bool) {
	if s.IDs == nil {
		return "", "", false
	}
	return s.IDs.IDs(binding, replicaRef, incarnation)
}

type View interface {
	Current() Snapshot
	Changed() <-chan struct{}
}

type Hub struct {
	mu      sync.Mutex
	cur     Snapshot
	changed chan struct{}
}

func NewHub() *Hub { return &Hub{changed: make(chan struct{})} }

func (h *Hub) Publish(s Snapshot) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.cur = s
	close(h.changed)
	h.changed = make(chan struct{})
}

func (h *Hub) Current() Snapshot {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.cur
}

// Changed is closed by the next Publish. Take it before reading Current, so a
// publish between the two is never missed.
func (h *Hub) Changed() <-chan struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.changed
}
