package agent

import (
	"errors"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// State is the composite the reducers share. Later tasks add Authority,
// Commands and Reports; Reduce dispatches in that fixed order.
type State struct {
	Session SessionState
}

func Reduce(cfg Config, s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	in := SessionInputs{}
	var effs []reactor.Effect
	s.Session, effs = reduceSession(cfg, s.Session, ev, env, in)
	return s, effs
}

func LoadState(cfg Config, s *store.Store, id *identity.Identity) (State, error) {
	st := State{Session: SessionState{Phase: PhaseIdle, Enrolled: id.Enrolled(), Endpoint: cfg.BootstrapEndpoint}}
	rec, err := store.LoadJSON[endpointRecord](s, "session/endpoint", 1)
	switch {
	case err == nil:
		st.Session.Endpoint = rec.Endpoint
	case errors.Is(err, store.ErrNotFound):
	default:
		return st, err
	}
	return st, nil
}
