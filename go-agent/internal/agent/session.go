package agent

import (
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	protocolVersion     uint32 = 1
	maxMissedHeartbeats        = 3
)

type Phase string

const (
	PhaseIdle       Phase = "IDLE"
	PhaseEnrolling  Phase = "ENROLLING"
	PhaseConnecting Phase = "CONNECTING"
	PhaseOpening    Phase = "OPENING"
	PhaseActive     Phase = "ACTIVE"
	PhaseDraining   Phase = "DRAINING"
	PhaseClosed     Phase = "CLOSED"
	PhaseHalted     Phase = "HALTED"
)

type Config struct {
	BootstrapEndpoint string
	EnrollmentToken   string
	AgentVersion      string
	Capabilities      []msg.Capability
}

type SessionState struct {
	Phase            Phase
	Enrolled         bool
	Endpoint         string
	SessionID        string
	TargetID         string
	Limits           msg.Limits
	Attempt          int
	HaltCode         msg.ErrorCode
	MissedHeartbeats int
}

// SessionInputs are facts from sibling reducers the session needs on the wire.
type SessionInputs struct {
	Resume         msg.Resume
	LeaseRemaining time.Duration
	JournalDepth   uint32
}

type endpointRecord struct {
	SchemaVersion int    `json:"schema_version"`
	Endpoint      string `json:"endpoint"`
}

func (r endpointRecord) Version() int { return r.SchemaVersion }

func reduceSession(cfg Config, s SessionState, ev reactor.Event, env reactor.Env, in SessionInputs) (SessionState, []reactor.Effect) {
	if s.Phase == PhaseHalted {
		return s, nil
	}
	switch e := ev.(type) {
	case reactor.Resumed:
		if s.Endpoint == "" {
			s.Endpoint = cfg.BootstrapEndpoint
		}
		if !s.Enrolled {
			s.Phase = PhaseEnrolling
			return s, []reactor.Effect{reactor.Enroll{Endpoint: s.Endpoint}}
		}
		s.Phase = PhaseConnecting
		return s, []reactor.Effect{reactor.Dial{Endpoint: s.Endpoint}}

	case reactor.EnrollResult:
		if e.Err != nil {
			code, retry := msg.CodeOf(e.Err)
			if msg.Classify(code) == msg.ClassHalt {
				return halt(s, code)
			}
			return retryLater(s, env, retry)
		}
		s.Enrolled = true
		if e.Resp.ConnectEndpoint != "" {
			s.Endpoint = e.Resp.ConnectEndpoint
		}
		s.Phase = PhaseConnecting
		s.Attempt = 0
		return s, []reactor.Effect{
			reactor.Persist{Rec: store.JSON{RelPath: "session/endpoint", Value: endpointRecord{SchemaVersion: 1, Endpoint: s.Endpoint}}},
			reactor.Dial{Endpoint: s.Endpoint},
		}

	case reactor.TimerFired:
		switch e.Kind {
		case reactor.ReconnectDue:
			if s.Phase == PhaseEnrolling {
				return s, []reactor.Effect{reactor.Enroll{Endpoint: s.Endpoint}}
			}
			if s.Phase == PhaseClosed {
				s.Phase = PhaseConnecting
				return s, []reactor.Effect{reactor.Dial{Endpoint: s.Endpoint}}
			}
		case reactor.HeartbeatDue:
			if s.Phase != PhaseActive {
				return s, nil
			}
			if s.MissedHeartbeats >= maxMissedHeartbeats {
				return closeAndRetry(s, env, nil)
			}
			s.MissedHeartbeats++
			return s, []reactor.Effect{
				reactor.Send{Msg: msg.Heartbeat{
					Envelope:             msg.Envelope{MessageID: env.NewID(), SentAt: env.Now},
					AgentClock:           env.Now,
					JournalDepth:         in.JournalDepth,
					LeaseRemainingAtSend: in.LeaseRemaining,
				}},
				reactor.ArmTimer{Kind: reactor.HeartbeatDue, After: s.Limits.HeartbeatInterval},
			}
		}
		return s, nil

	case reactor.Connected:
		if s.Phase != PhaseConnecting {
			return s, nil
		}
		s.Phase = PhaseOpening
		return s, []reactor.Effect{reactor.Send{Msg: msg.SessionOpen{
			Envelope:              msg.Envelope{MessageID: env.NewID(), SentAt: env.Now},
			ProtocolVersion:       protocolVersion,
			AgentVersion:          cfg.AgentVersion,
			CapabilitiesSupported: cfg.Capabilities,
			Resume:                in.Resume,
			AgentClock:            env.Now,
		}}}

	case reactor.Disconnected:
		switch s.Phase {
		case PhaseDraining:
			s.Phase = PhaseClosed
			return s, nil
		case PhaseClosed, PhaseIdle, PhaseEnrolling:
			return s, nil
		default:
			return closeAndRetry(s, env, nil)
		}

	case reactor.Shutdown:
		var effs []reactor.Effect
		if s.Phase == PhaseActive || s.Phase == PhaseOpening {
			effs = append(effs, reactor.Send{Msg: msg.Teardown{Envelope: msg.Envelope{MessageID: env.NewID(), SentAt: env.Now}, Reason: "shutdown"}})
		}
		s.Phase = PhaseClosed
		return s, append(effs, reactor.Close{Reason: "shutdown"})

	case reactor.WireIn:
		return reduceSessionWire(s, e.Msg, env)
	}
	return s, nil
}

func reduceSessionWire(s SessionState, m msg.Inbound, env reactor.Env) (SessionState, []reactor.Effect) {
	switch m := m.(type) {
	case msg.SessionAccept:
		if s.Phase != PhaseOpening {
			return s, nil
		}
		s.Phase = PhaseActive
		s.SessionID, s.TargetID, s.Limits = m.SessionID, m.AssignedTargetID, m.Limits
		s.Attempt, s.MissedHeartbeats = 0, 0
		return s, []reactor.Effect{reactor.ArmTimer{Kind: reactor.HeartbeatDue, After: m.Limits.HeartbeatInterval}}

	case msg.SessionReject:
		if msg.Classify(m.Code) == msg.ClassHalt {
			s, effs := halt(s, m.Code)
			return s, append([]reactor.Effect{reactor.Close{Reason: string(m.Code)}}, effs...)
		}
		return closeAndRetry(s, env, m.RetryAfter)

	case msg.Error:
		switch msg.Classify(m.Code) {
		case msg.ClassHalt:
			s, effs := halt(s, m.Code)
			return s, append([]reactor.Effect{reactor.Close{Reason: string(m.Code)}}, effs...)
		case msg.ClassRetry:
			return closeAndRetry(s, env, m.RetryAfter)
		case msg.ClassAct:
			if m.Code == msg.ErrSessionSuperseded {
				s.Phase = PhaseClosed
				return s, []reactor.Effect{
					reactor.Close{Reason: string(m.Code)},
					reactor.ArmTimer{Kind: reactor.ReconnectDue, After: 0},
				}
			}
		}
		return s, nil // LEASE_RENEW_REQUIRED etc. belong to authority; per-command codes to commands

	case msg.Drain:
		s.Phase = PhaseDraining
		return s, []reactor.Effect{
			reactor.Close{Reason: "drain: " + m.Reason},
			reactor.ArmTimer{Kind: reactor.ReconnectDue, After: env.Jitter(m.ReconnectAfter)},
		}

	case msg.Teardown:
		return closeAndRetry(s, env, nil)

	case msg.Revoke:
		s, effs := halt(s, msg.ErrIdentityRevoked)
		return s, append([]reactor.Effect{reactor.Close{Reason: "revoked: " + m.Reason}}, effs...)

	case msg.HeartbeatAck:
		s.MissedHeartbeats = 0
		return s, nil
	}
	return s, nil
}

func halt(s SessionState, code msg.ErrorCode) (SessionState, []reactor.Effect) {
	s.Phase = PhaseHalted
	s.HaltCode = code
	return s, []reactor.Effect{reactor.Halt{Code: code}}
}

// retryLater keeps the phase (ENROLLING retries enroll, CLOSED retries dial).
func retryLater(s SessionState, env reactor.Env, retryAfter *time.Duration) (SessionState, []reactor.Effect) {
	after := env.Jitter(backoffFor(s.Attempt))
	if retryAfter != nil {
		after = *retryAfter
	}
	s.Attempt++
	return s, []reactor.Effect{reactor.ArmTimer{Kind: reactor.ReconnectDue, After: after}}
}

func closeAndRetry(s SessionState, env reactor.Env, retryAfter *time.Duration) (SessionState, []reactor.Effect) {
	s.Phase = PhaseClosed
	s.MissedHeartbeats = 0
	s, effs := retryLater(s, env, retryAfter)
	return s, append([]reactor.Effect{reactor.Close{Reason: "reconnect"}}, effs...)
}
