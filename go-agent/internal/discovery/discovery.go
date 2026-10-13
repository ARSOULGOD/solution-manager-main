// Package discovery owns the agent side of auto-registration (plan 015 T2.3,
// AG-D26): it reports FACTS about the containers that opted in with the
// com.solace.sm.instance label, and remembers which container NAME the DP made
// which placement (binding) so a command's container is found by name, and a
// re-created container keeps its placement.
//
// The agent derives no placement field and names no environment, organization or
// target: the DP decides from the agent's enrolment (I5).
package discovery

import (
	"context"
	"errors"
	"reflect"
	"sync"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// ErrUnsupported: the runtime adapter cannot discover (nothing is reported).
var ErrUnsupported = errors.New("discovery: the runtime adapter cannot discover")

const relBindings = "discovery/bindings.json"

// Bindings is the persisted container-name -> binding-id map.
type Bindings struct {
	SchemaVersion int               `json:"schema_version"`
	ByName        map[string]string `json:"by_name"`
}

func (b Bindings) Version() int { return b.SchemaVersion }

type State struct {
	Bindings Bindings
	// Active is true between SessionAccept and the end of the connection. A report
	// is only sent on an active session.
	Active bool
	// Sent is the snapshot the gateway last saw on this session (nil: none yet,
	// so the next scan is sent whatever it holds). SentMessageID names it.
	Sent          []msg.DiscoveredContainer
	SentMessageID string
	HaveSent      bool
}

func Load(s *store.Store) (State, error) {
	st := State{Bindings: Bindings{SchemaVersion: 1, ByName: map[string]string{}}}
	b, err := store.LoadJSON[Bindings](s, relBindings, 1)
	switch {
	case err == nil:
		if b.ByName == nil {
			b.ByName = map[string]string{}
		}
		st.Bindings = b
	case errors.Is(err, store.ErrNotFound), errors.Is(err, store.ErrSchemaVersion):
		// self-healing: discovery re-registers by name
	default:
		return st, err
	}
	return st, nil
}

// Reduce handles four events: the session becoming active (forget what was sent,
// so the next scan is sent in full), the connection ending, a scan result
// (Discovered) and the gateway's DiscoveryAck.
func Reduce(s State, ev reactor.Event, env reactor.Env, active bool) (State, []reactor.Effect) {
	switch e := ev.(type) {
	case reactor.Disconnected:
		s.Active, s.Sent, s.HaveSent, s.SentMessageID = false, nil, false, ""
		return s, nil

	case reactor.Discovered:
		if !active {
			return s, nil
		}
		s.Active = true
		items := e.Items
		if s.HaveSent && reflect.DeepEqual(items, s.Sent) {
			return s, nil // "whenever the set or any container_id changes"
		}
		s.Sent, s.HaveSent = items, true
		envl := env.Env()
		s.SentMessageID = envl.MessageID
		return s, []reactor.Effect{reactor.Send{Msg: msg.DiscoveryReport{Envelope: envl, Items: items}}}

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.SessionAccept:
			s.Active, s.Sent, s.HaveSent, s.SentMessageID = true, nil, false, ""
		case msg.DiscoveryAck:
			return s.ack(m)
		}
	}
	return s, nil
}

// ack records the placement ids the DP assigned. A result is matched to the
// container it answers by id in the report it replies to; REGISTERED sets the
// name -> binding mapping, RETIRED removes it.
func (s State) ack(m msg.DiscoveryAck) (State, []reactor.Effect) {
	if m.InReplyToMessageID != "" && m.InReplyToMessageID != s.SentMessageID {
		return s, nil // an answer to a snapshot that is no longer the current one
	}
	nameOf := map[string]string{}
	for _, c := range s.Sent {
		nameOf[c.ContainerID] = c.ContainerName
	}
	by := map[string]string{}
	for k, v := range s.Bindings.ByName {
		by[k] = v
	}
	changed := false
	for _, r := range m.Results {
		name, ok := nameOf[r.ContainerID]
		if !ok {
			continue
		}
		switch {
		case r.State == msg.DiscoveryRegistered && r.BindingID != "":
			for other, b := range by { // one name per binding id
				if b == r.BindingID && other != name {
					delete(by, other)
					changed = true
				}
			}
			if by[name] != r.BindingID {
				by[name], changed = r.BindingID, true
			}
		case r.State == msg.DiscoveryRetired:
			if _, had := by[name]; had {
				delete(by, name)
				changed = true
			}
		}
	}
	if !changed {
		return s, nil
	}
	s.Bindings.ByName = by
	return s, []reactor.Effect{reactor.Persist{Rec: store.JSON{RelPath: relBindings, Value: s.Bindings}}}
}

// Names is the thread-safe view of the bindings map that adapter calls on other
// goroutines read; the loop refreshes it after reducing.
type Names struct {
	mu     sync.RWMutex
	byName map[string]string
}

func NewNames() *Names { return &Names{byName: map[string]string{}} }

func (n *Names) Set(byName map[string]string) {
	c := make(map[string]string, len(byName))
	for k, v := range byName {
		c[k] = v
	}
	n.mu.Lock()
	n.byName = c
	n.mu.Unlock()
}

// NameOf is the container name registered for a binding.
func (n *Names) NameOf(binding string) (string, bool) {
	n.mu.RLock()
	defer n.mu.RUnlock()
	found := ""
	for name, b := range n.byName {
		if b == binding {
			if found != "" {
				return "", false // ambiguous: never a map-order accident
			}
			found = name
		}
	}
	return found, found != ""
}

// Adapter makes the runtime adapter find a registered binding's container by
// name, and falls back to the adapter's own lookup (the development
// com.solace.sm.binding-id label, AG-D26 item 5) for any other binding. The
// optional capabilities of the inner adapter are forwarded.
type Adapter struct {
	runtime.Adapter
	Names *Names
}

func Wrap(inner runtime.Adapter, names *Names) runtime.Adapter {
	if inner == nil {
		return nil
	}
	return &Adapter{Adapter: inner, Names: names}
}

func (a *Adapter) Resolve(ctx context.Context, bindingID string) (runtime.Placement, error) {
	if name, ok := a.Names.NameOf(bindingID); ok {
		if nr, ok := a.Adapter.(runtime.NameResolver); ok {
			return nr.ResolveByName(ctx, name, bindingID)
		}
	}
	return a.Adapter.Resolve(ctx, bindingID)
}

func (a *Adapter) WriteConfigIf(ctx context.Context, p runtime.Placement, payload []byte, sha string, expected *string, pr runtime.Progress) (runtime.WriteResult, error) {
	if cw, ok := a.Adapter.(runtime.ConditionalWriter); ok {
		return cw.WriteConfigIf(ctx, p, payload, sha, expected, pr)
	}
	return runtime.WriteResult{}, runtime.ErrNoConditionalWrite
}

func (a *Adapter) ObserveHealth(ctx context.Context, p runtime.Placement, probe *msg.HealthProbe) msg.HealthObservation {
	if ho, ok := a.Adapter.(runtime.HealthObserver); ok {
		return ho.ObserveHealth(ctx, p, probe)
	}
	return msg.HealthObservation{} // State == HealthUnspecified: nothing observed
}

func (a *Adapter) Discover(ctx context.Context) ([]msg.DiscoveredContainer, int, error) {
	if d, ok := a.Adapter.(runtime.Discoverer); ok {
		return d.Discover(ctx)
	}
	return nil, 0, ErrUnsupported
}
