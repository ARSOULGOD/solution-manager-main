package agent

import (
	"errors"
	"slices"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/discovery"
	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
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
	Discovery discovery.State
}

func Reduce(cfg Config, s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	var all, effs []reactor.Effect
	quiescing := s.Session.Quiescing
	in := SessionInputs{LeaseRemaining: s.Authority.Remaining(env.DPNow())}
	in.JournalDepth = uint32(s.Commands.OpenCount())
	s.Session, effs = reduceSession(cfg, s.Session, ev, env, in)
	all = append(all, effs...)
	s.Authority, effs = authority.Reduce(s.Authority, ev, env)
	all = append(all, effs...)
	if !(quiescing && offersWork(ev)) {
		// A draining agent takes no new work: the command stays OFFERED at the DP
		// and is re-notified on the next session (AGW-D18).
		s.Commands, effs = commands.Reduce(s.Commands, ev, env, gate{State: s.Authority, supported: cfg.Capabilities})
		all = append(all, effs...)
	}
	s.Reports, effs = reports.Reduce(s.Reports, ev, env)
	all = append(all, effs...)
	s.Discovery, effs = discovery.Reduce(s.Discovery, ev, env, s.Session.Phase == PhaseActive && slices.Contains(cfg.Capabilities, msg.CapDiscovery))
	all = append(all, effs...)
	if s.Session.Quiescing && s.Session.Phase == PhaseDraining {
		s, effs = maybeCompleteDrain(s, ev, env)
		all = append(all, effs...)
	}
	return s, all
}

// offersWork reports whether the event would start new work on the agent.
func offersWork(ev reactor.Event) bool {
	w, ok := ev.(reactor.WireIn)
	if !ok {
		return false
	}
	switch w.Msg.(type) {
	case msg.GenerationAvailable, msg.CommandOffer:
		return true
	}
	return false
}

// maybeCompleteDrain finishes a planned drain once the agent is quiet — no
// command running or queued and every report acknowledged — or when the drain
// deadline timer fires. A finished operation that will be followed by an
// observation waits for that observation, so its report is flushed before the
// connection closes.
func maybeCompleteDrain(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	forced := false
	if tf, ok := ev.(reactor.TimerFired); ok && tf.Kind == reactor.DrainDeadline {
		forced = true
	}
	if fin, ok := ev.(reactor.OpFinished); ok && fin.Result.Facts != nil && fin.Result.Placement != nil && !forced {
		return s, nil
	}
	if !forced && !(s.Commands.Idle() && s.Reports.PendingCount() == 0) {
		return s, nil
	}
	var effs []reactor.Effect
	s.Session, effs = completeDrain(s.Session, env, s.Commands.Unfinished())
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
	st.Discovery, err = discovery.Load(s)
	if err != nil {
		return st, err
	}
	return st, nil
}
