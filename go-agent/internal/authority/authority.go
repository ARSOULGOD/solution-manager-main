// Package authority is the lease reducer: SM-D28's authority machine as the
// agent-gateway design consumes it (§4.6, §4.8, §4.9). All arithmetic is in DP
// time (env.DPNow()).
package authority

import (
	"errors"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	relLease   = "session/lease.json"
	renewRetry = 30 * time.Second
)

type Phase string

const (
	PhaseNone             Phase = "NONE"
	PhaseActive           Phase = "ACTIVE"
	PhaseRenewDegraded    Phase = "RENEW_DEGRADED"
	PhaseLeaseExpired     Phase = "LEASE_EXPIRED"
	PhaseClockSuspect     Phase = "CLOCK_SUSPECT"
	PhaseRevoked          Phase = "REVOKED"
	PhaseReEnrollRequired Phase = "RE_ENROLL_REQUIRED"
)

// PendingRenew is a LeaseRenew whose terminal reply has not arrived. Its
// message_id is reused on every resend (AGW-D15 adoption/takeover).
type PendingRenew struct {
	MessageID string `json:"message_id"`
	Nonce     string `json:"nonce"`
}

type State struct {
	Phase          Phase
	Grant          *msg.LeaseGrant
	Pending        *PendingRenew
	LeafThumbprint string
}

type Record struct {
	SchemaVersion int            `json:"schema_version"`
	Grant         msg.LeaseGrant `json:"grant"`
	Pending       *PendingRenew  `json:"pending,omitempty"`
}

func (r Record) Version() int { return r.SchemaVersion }

func Load(s *store.Store, leafThumbprint string) (State, error) {
	st := State{Phase: PhaseNone, LeafThumbprint: leafThumbprint}
	rec, err := store.LoadJSON[Record](s, relLease, 1)
	if errors.Is(err, store.ErrNotFound) {
		return st, nil
	}
	if err != nil {
		return st, err
	}
	g := rec.Grant
	st.Grant, st.Pending = &g, rec.Pending
	return st, nil
}

// ---- read-only helpers ----

func (s State) Active() bool { return s.Phase == PhaseActive && s.Grant != nil }

func (s State) Allows(c msg.Capability) bool { return s.Grant != nil && s.Grant.Has(c) }

func (s State) HasBinding(id string) bool { return s.Grant != nil && s.Grant.HasBinding(id) }

func (s State) Remaining(dpNow time.Time) time.Duration {
	if s.Grant == nil {
		return 0
	}
	if d := s.Grant.ExpiresAt.Sub(dpNow); d > 0 {
		return d
	}
	return 0
}

func (s State) ceiling(op msg.Operation) time.Duration {
	if s.Grant == nil {
		return 0
	}
	return s.Grant.VerbCeilings[op]
}

// Admissible is gateway §4.6's inequality: expires_at − now ≥ ceiling + margin.
func (s State) Admissible(op msg.Operation, dpNow time.Time) bool {
	if s.Grant == nil {
		return false
	}
	return s.Grant.ExpiresAt.Sub(dpNow) >= s.ceiling(op)+s.Grant.AdmissionMargin
}

// StepDeadline is min(start + ceiling, expires_at).
func (s State) StepDeadline(op msg.Operation, start time.Time) time.Time {
	d := start.Add(s.ceiling(op))
	if s.Grant != nil && s.Grant.ExpiresAt.Before(d) {
		return s.Grant.ExpiresAt
	}
	return d
}

// RefusalDetail mirrors the DP's offer gate locally; "" means admissible.
func (s State) RefusalDetail(op msg.Operation, bindingID string, dpNow time.Time) msg.DetailCode {
	if !s.Active() {
		return msg.DetailLeaseNotActive
	}
	if !s.Allows(op.RequiredCapability()) {
		return msg.DetailOf(msg.ErrCapabilityMissing)
	}
	if !s.HasBinding(bindingID) {
		return msg.DetailOf(msg.ErrBindingNotInLease)
	}
	// A verb the lease carries no ceiling for has a zero ceiling: it would be admitted, armed with a
	// zero-length step timer, acked as timed out and still executed by the worker. Refuse it before
	// dispatch with a code (AG-D27 item 5, review R-7).
	if s.ceiling(op) <= 0 {
		return msg.DetailOf(msg.ErrCapabilityMismatch)
	}
	if !s.Admissible(op, dpNow) {
		return msg.DetailAdmissionBlocked
	}
	return msg.DetailNone
}

// ---- reducer ----

func Reduce(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	if s.Phase == PhaseRevoked || s.Phase == PhaseReEnrollRequired {
		return s, nil
	}
	switch e := ev.(type) {
	case reactor.Resumed:
		return s.recompute(env, nil)

	case reactor.EnrollResult:
		// A fresh (first-time) enroll mints a new certificate. LeafThumbprint
		// is otherwise fixed at Load — computed from identity.Thumbprint()
		// before the identity has a leaf cert — so without this, valid()
		// compares every subsequent grant's CnfThumbprint against "" and
		// rejects it forever. A restart doesn't hit this: Load reads the
		// persisted cert before LoadState captures the thumbprint.
		if e.Err == nil && e.Resp != nil && len(e.Resp.CertificateChain) > 0 {
			s.LeafThumbprint = identity.Thumbprint(e.Resp.CertificateChain[0])
		}
		return s, nil

	case reactor.TimerFired:
		switch e.Kind {
		case reactor.RenewDue:
			if s.Phase != PhaseActive || s.Grant == nil {
				return s, nil
			}
			return s.sendRenew(env)
		case reactor.LeaseExpiry:
			if s.Phase == PhaseActive || s.Phase == PhaseRenewDegraded {
				s.Phase = PhaseLeaseExpired
				return s, []reactor.Effect{reactor.CancelTimer{Kind: reactor.RenewDue}}
			}
		}
		return s, nil

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.SessionAccept:
			return s.acceptGrant(m.Lease, env)
		case msg.LeaseGrant:
			return s.acceptGrant(m, env)
		case msg.Error:
			switch m.Code {
			case msg.ErrLeaseRenewRequired:
				if s.Grant == nil {
					return s, nil
				}
				return s.sendRenew(env)
			case msg.ErrLeaseRenewRefused:
				s.Pending = nil
				if s.Phase == PhaseActive {
					s.Phase = PhaseRenewDegraded
				}
				return s, []reactor.Effect{s.persist(), reactor.CancelTimer{Kind: reactor.RenewDue}}
			}
		case msg.HeartbeatAck:
			suspect := m.ClockState == msg.ClockSuspect || absDur(env.Offset) > env.Limits.SkewTolerance
			if suspect {
				if s.Phase == PhaseActive || s.Phase == PhaseRenewDegraded {
					s.Phase = PhaseClockSuspect
					return s, []reactor.Effect{reactor.CancelTimer{Kind: reactor.RenewDue}}
				}
				return s, nil
			}
			if s.Phase == PhaseClockSuspect {
				return s.recompute(env, nil)
			}
		case msg.Revoke:
			s.Phase, s.Grant, s.Pending = PhaseRevoked, nil, nil
			return s, []reactor.Effect{reactor.Delete{Rel: relLease}, reactor.CancelTimer{Kind: reactor.RenewDue}, reactor.CancelTimer{Kind: reactor.LeaseExpiry}}
		case msg.SessionReject:
			if m.Code == msg.ErrReEnrollRequired {
				s.Phase, s.Grant, s.Pending = PhaseReEnrollRequired, nil, nil
				return s, []reactor.Effect{reactor.Delete{Rel: relLease}, reactor.CancelTimer{Kind: reactor.RenewDue}, reactor.CancelTimer{Kind: reactor.LeaseExpiry}}
			}
		}
	}
	return s, nil
}

func absDur(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}

func (s State) persist() reactor.Effect {
	rec := Record{SchemaVersion: 1, Pending: s.Pending}
	if s.Grant != nil {
		rec.Grant = *s.Grant
	}
	return reactor.Persist{Rec: store.JSON{RelPath: relLease, Value: rec}}
}

func (s State) valid(g msg.LeaseGrant) bool {
	if g.CnfThumbprint != s.LeafThumbprint || !g.ExpiresAt.After(g.IssuedAt) {
		return false
	}
	if s.Grant != nil && (g.AgentRevocationEpoch < s.Grant.AgentRevocationEpoch || g.FleetRevocationEpoch < s.Grant.FleetRevocationEpoch) {
		return false
	}
	return true
}

// acceptGrant stores a validated grant and re-derives phase and timers. A
// grant carrying the nonce of a pending renewal is the same lease returned
// on failover, so the pending request is still owed under its id.
func (s State) acceptGrant(g msg.LeaseGrant, env reactor.Env) (State, []reactor.Effect) {
	if !s.valid(g) {
		return s, nil
	}
	if s.Pending != nil && s.Pending.Nonce != g.Nonce {
		s.Pending = nil
	}
	s.Grant = &g
	return s.recompute(env, []reactor.Effect{s.persist()})
}

// recompute derives the phase from the stored grant and DP time, arming timers.
func (s State) recompute(env reactor.Env, effs []reactor.Effect) (State, []reactor.Effect) {
	if s.Grant == nil {
		s.Phase = PhaseNone
		return s, effs
	}
	dpNow := env.DPNow()
	if !dpNow.Before(s.Grant.ExpiresAt) {
		s.Phase = PhaseLeaseExpired
		return s, append(effs, reactor.CancelTimer{Kind: reactor.RenewDue}, reactor.CancelTimer{Kind: reactor.LeaseExpiry})
	}
	s.Phase = PhaseActive
	renewAt := s.Grant.IssuedAt.Add(s.Grant.ExpiresAt.Sub(s.Grant.IssuedAt) / 2)
	renewIn := renewAt.Sub(dpNow)
	if renewIn < 0 {
		renewIn = 0
	}
	return s, append(effs,
		reactor.ArmTimer{Kind: reactor.RenewDue, After: renewIn},
		reactor.ArmTimer{Kind: reactor.LeaseExpiry, After: s.Grant.ExpiresAt.Sub(dpNow)},
	)
}

func (s State) sendRenew(env reactor.Env) (State, []reactor.Effect) {
	if s.Pending == nil || s.Pending.Nonce != s.Grant.Nonce {
		s.Pending = &PendingRenew{MessageID: env.NewID(), Nonce: s.Grant.Nonce}
	}
	return s, []reactor.Effect{
		s.persist(),
		reactor.Send{Msg: msg.LeaseRenew{Envelope: msg.Envelope{MessageID: s.Pending.MessageID, SentAt: env.Now}, NonceConsumed: s.Pending.Nonce, AgentClock: env.Now}},
		reactor.ArmTimer{Kind: reactor.RenewDue, After: env.Jitter(renewRetry)},
	}
}
