// Package reports owns actual-state reporting: item minting (AG-D17), the
// per-replica sequence counter, the periodic cadence (AG-D18) and the
// DP-minted id registry the telemetry role will label with.
package reports

import (
	"errors"
	"maps"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	relCursor   = "reports/cursor.json"
	relPending  = "reports/pending.json"
	relRegistry = "reports/registry.json"
)

type Cursor struct {
	SchemaVersion int               `json:"schema_version"`
	Sequences     map[string]uint64 `json:"sequences"`
}

func (c Cursor) Version() int { return c.SchemaVersion }

type Pending struct {
	SchemaVersion int                     `json:"schema_version"`
	Items         []msg.ActualStateReport `json:"items"`
	SentMessageID string                  `json:"sent_message_id,omitempty"`
}

func (p Pending) Version() int { return p.SchemaVersion }

type Registry struct {
	SchemaVersion  int               `json:"schema_version"`
	ReplicaIDs     map[string]string `json:"replica_ids"`
	IncarnationIDs map[string]string `json:"incarnation_ids"`
}

func (r Registry) Version() int { return r.SchemaVersion }

type Observation struct {
	BindingID           string
	ReplicaRef          string
	Incarnation         string
	PersistedGeneration uint64
	RestartObservedAt   *time.Time
	EffectiveHash       string
	VersionObservation  msg.VersionObservation
	Correlation         *msg.OperationCorrelation
	Health              *msg.HealthObservation
	ContainerStartedAt  *time.Time
}

type State struct {
	Cursor   Cursor
	Pending  Pending
	Registry Registry
}

func replicaKey(binding, replica string) string { return binding + "|" + replica }
func incarnationKey(binding, replica, inc string) string {
	return binding + "|" + replica + "|" + inc
}

func Load(s *store.Store) (State, error) {
	st := State{
		Cursor:   Cursor{SchemaVersion: 1, Sequences: map[string]uint64{}},
		Pending:  Pending{SchemaVersion: 1},
		Registry: Registry{SchemaVersion: 1, ReplicaIDs: map[string]string{}, IncarnationIDs: map[string]string{}},
	}
	// The cursor and the pending list self-heal; a corrupt one is not fatal
	// (the counter reseeds, items are re-observed), unlike identity or apply.
	if c, err := store.LoadJSON[Cursor](s, relCursor, 1); err == nil {
		if c.Sequences == nil {
			c.Sequences = map[string]uint64{}
		}
		st.Cursor = c
	} else if !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrSchemaVersion) {
		return st, err
	}
	if p, err := store.LoadJSON[Pending](s, relPending, 1); err == nil {
		st.Pending = p
	} else if !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrSchemaVersion) {
		return st, err
	}
	if r, err := store.LoadJSON[Registry](s, relRegistry, 1); err == nil {
		if r.ReplicaIDs == nil {
			r.ReplicaIDs = map[string]string{}
		}
		if r.IncarnationIDs == nil {
			r.IncarnationIDs = map[string]string{}
		}
		st.Registry = r
	} else if !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrSchemaVersion) {
		return st, err
	}
	return st, nil
}

func (s State) PendingCount() int { return len(s.Pending.Items) }

func (s State) ReplicaID(binding, replicaRef string) string {
	return s.Registry.ReplicaIDs[replicaKey(binding, replicaRef)]
}

func (s State) IncarnationID(binding, replicaRef, incarnation string) string {
	return s.Registry.IncarnationIDs[incarnationKey(binding, replicaRef, incarnation)]
}

func Reduce(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	switch e := ev.(type) {
	case reactor.Observed:
		obs, ok := e.Obs.(Observation)
		if !ok {
			return s, nil
		}
		return s.observe(obs, env)

	case reactor.TimerFired:
		if e.Kind != reactor.ReportDue {
			return s, nil
		}
		// AG-D18: the cadence runs whether or not anything changed; the loop
		// supplies a fresh Observed alongside this tick when it can.
		effs := s.flush(env)
		return s, append(effs, reactor.ArmTimer{Kind: reactor.ReportDue, After: env.Limits.ReportInterval})

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.SessionAccept:
			// Boot order: a full report precedes any telemetry, and the pending
			// list is re-sent on every new session.
			effs := s.flush(env)
			after := m.Limits.ReportInterval
			if after <= 0 {
				after = env.Limits.ReportInterval
			}
			return s, append(effs, reactor.ArmTimer{Kind: reactor.ReportDue, After: after})
		case msg.ReportAck:
			return s.ack(m, env)
		}
	}
	return s, nil
}

// observe mints an immutable item: the counter is persisted before the item
// exists, so a crash cannot reissue a sequence (AG-D17).
func (s State) observe(o Observation, env reactor.Env) (State, []reactor.Effect) {
	k := replicaKey(o.BindingID, o.ReplicaRef)
	next := s.Cursor.Sequences[k] + 1
	if next == 1 {
		// no cursor for this replica: seed from DP time so any previous
		// counter (from a lost data dir) is necessarily lower
		next = uint64(env.DPNow().UnixNano())
	}
	s.Cursor.Sequences[k] = next
	item := msg.ActualStateReport{
		ItemID: env.NewID(), Sequence: next, BindingID: o.BindingID, ReplicaRef: o.ReplicaRef, Incarnation: o.Incarnation,
		PersistedGeneration: o.PersistedGeneration, RestartObservedAt: o.RestartObservedAt, EffectiveHash: o.EffectiveHash,
		VersionObservation: o.VersionObservation, Correlation: o.Correlation,
		Health: o.Health, ContainerStartedAt: o.ContainerStartedAt,
	}
	s.Pending.Items = append(s.Pending.Items, item)
	effs := []reactor.Effect{
		reactor.Persist{Rec: store.JSON{RelPath: relCursor, Value: s.Cursor}},
		reactor.Persist{Rec: store.JSON{RelPath: relPending, Value: s.Pending}},
	}
	return s, append(effs, s.send(env)...)
}

// flush re-sends everything still pending, unchanged.
func (s State) flush(env reactor.Env) []reactor.Effect {
	if len(s.Pending.Items) == 0 {
		return nil
	}
	return s.send(env)
}

func (s State) send(env reactor.Env) []reactor.Effect {
	items := make([]msg.ActualStateReport, len(s.Pending.Items))
	copy(items, s.Pending.Items)
	return []reactor.Effect{reactor.Send{Msg: msg.ActualStateReportBatch{Envelope: env.Env(), Items: items}}}
}

func (s State) ack(m msg.ReportAck, env reactor.Env) (State, []reactor.Effect) {
	if len(m.Results) == 0 {
		return s, nil
	}
	answered := map[string]msg.ReportResult{}
	for _, r := range m.Results {
		answered[r.InReplyToItemID] = r
	}
	var remaining []msg.ActualStateReport
	changedRegistry := false
	for _, it := range s.Pending.Items {
		r, ok := answered[it.ItemID]
		if !ok {
			remaining = append(remaining, it)
			continue
		}
		// Every answered item is closed: APPLIED, STALE, REFUSED and an
		// UNTRUSTED verdict alike. The agent never retries to change a label.
		if r.ReplicaID != "" {
			s.Registry.ReplicaIDs[replicaKey(it.BindingID, it.ReplicaRef)] = r.ReplicaID
			changedRegistry = true
		}
		if r.IncarnationID != "" {
			s.Registry.IncarnationIDs[incarnationKey(it.BindingID, it.ReplicaRef, it.Incarnation)] = r.IncarnationID
			changedRegistry = true
		}
	}
	s.Pending.Items = remaining
	effs := []reactor.Effect{reactor.Persist{Rec: store.JSON{RelPath: relPending, Value: s.Pending}}}
	if changedRegistry {
		effs = append([]reactor.Effect{reactor.Persist{Rec: store.JSON{RelPath: relRegistry, Value: s.Registry}}}, effs...)
	}
	return s, effs
}

// IDTable is an immutable copy of the registry for readers off the loop
// goroutine — the telemetry pipeline. The loop takes it after reducing, on
// its own goroutine; nobody mutates it afterwards.
type IDTable struct {
	replicas     map[string]string
	incarnations map[string]string
}

func (s State) IDTable() IDTable {
	return IDTable{replicas: maps.Clone(s.Registry.ReplicaIDs), incarnations: maps.Clone(s.Registry.IncarnationIDs)}
}

// IDs is the DP-minted pair a sample is labelled with; ok only when both exist.
func (t IDTable) IDs(binding, replicaRef, incarnation string) (replicaID, incarnationID string, ok bool) {
	replicaID = t.replicas[replicaKey(binding, replicaRef)]
	incarnationID = t.incarnations[incarnationKey(binding, replicaRef, incarnation)]
	return replicaID, incarnationID, replicaID != "" && incarnationID != ""
}
