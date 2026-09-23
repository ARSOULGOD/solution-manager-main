package agent

import (
	"errors"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/reports"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// State is the composite the reducers share. Reduce dispatches in the fixed
// order session → authority → commands → reports; each later reducer sees
// the earlier ones' new state.
type State struct {
	Session   SessionState
	Authority authority.State
	Commands  commands.State
	Reports   reports.State
}

func Reduce(cfg Config, s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	var all, effs []reactor.Effect
	in := SessionInputs{LeaseRemaining: s.Authority.Remaining(env.DPNow())}
	in.JournalDepth = uint32(s.Commands.OpenCount())
	s.Session, effs = reduceSession(cfg, s.Session, ev, env, in)
	all = append(all, effs...)
	s.Authority, effs = authority.Reduce(s.Authority, ev, env)
	all = append(all, effs...)
	s.Commands, effs = commands.Reduce(s.Commands, ev, env, s.Authority)
	all = append(all, effs...)
	s.Reports, effs = reports.Reduce(s.Reports, ev, env)
	all = append(all, effs...)
	return s, all
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
	st.Authority, err = authority.Load(s, id.Thumbprint())
	if err != nil {
		return st, err
	}
	st.Commands, err = commands.Load(s)
	if err != nil {
		return st, err
	}
	st.Reports, err = reports.Load(s)
	if err != nil {
		return st, err
	}
	return st, nil
}
