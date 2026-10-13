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
	// supersedeWindow is how close together two SESSION_SUPERSEDED must be to count as one run. A session stable for
	// longer than this was a real failover, not a duplicate identity.
	supersedeWindow = 2 * time.Minute
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

	// Planned drain (AGW-D18). Quiescing is true from the Drain until the agent
	// answers with DrainComplete and closes: the connection stays open so that
	// in-flight acknowledgements still reach the gateway.
	Quiescing           bool
	DrainMessageID      string
	DrainStartedAt      time.Time
	DrainReconnectAfter time.Duration

	// Supersession rate limit. SESSION_SUPERSEDED means "a newer session owns you; reconnect", and the first is
	// answered at once (a failover). Two live agents presenting one identity supersede each other forever, so a run
	// of them inside supersedeWindow backs off exponentially instead of reconnecting at the speed of the network.
	LastSuperseded time.Time
	SupersededRun  int
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
			if s.Phase != PhaseActive && !s.Quiescing {
				return s, nil
			}
			if s.MissedHeartbeats >= maxMissedHeartbeats {
				return closeAndRetry(s, env, nil)
			}
			s.MissedHeartbeats++
			return s, []reactor.Effect{
				reactor.Send{Msg: msg.Heartbeat{
					Envelope:             env.Env(),
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
			Envelope:              env.Env(),
			ProtocolVersion:       protocolVersion,
			AgentVersion:          cfg.AgentVersion,
			CapabilitiesSupported: cfg.Capabilities,
			Resume: msg.Resume{LastAckedGenerationByBinding: in.Resume.LastAckedGenerationByBinding,
				LastTelemetrySeqByStream: e.TelemetryResume},
			AgentClock: env.Now,
		}}}

	case reactor.Disconnected:
		switch s.Phase {
		case PhaseDraining:
			s.Phase = PhaseClosed
			if s.Quiescing {
				// The connection died before the drain completed. The reconnect
				// timer is only armed on completion, so arm it here or the agent
				// would sit Closed forever.
				s.Quiescing = false
				return s, []reactor.Effect{
					reactor.CancelTimer{Kind: reactor.DrainDeadline},
					reactor.ArmTimer{Kind: reactor.ReconnectDue, After: s.drainRemaining(env)},
				}
			}
			return s, nil
		case PhaseClosed, PhaseIdle, PhaseEnrolling:
			return s, nil
		default:
			return closeAndRetry(s, env, nil)
		}

	case reactor.Shutdown:
		var effs []reactor.Effect
		if s.Phase == PhaseActive || s.Phase == PhaseOpening {
			effs = append(effs, reactor.Send{Msg: msg.Teardown{Envelope: env.Env(), Reason: "shutdown"}})
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
				s.Quiescing = false
				if !s.LastSuperseded.IsZero() && env.Now.Sub(s.LastSuperseded) < supersedeWindow {
					s.SupersededRun++
				} else {
					s.SupersededRun = 0
				}
				s.LastSuperseded = env.Now
				var after time.Duration
				if s.SupersededRun > 0 {
					after = env.Jitter(backoffFor(s.SupersededRun))
				}
				return s, []reactor.Effect{
					reactor.Close{Reason: string(m.Code)},
					reactor.ArmTimer{Kind: reactor.ReconnectDue, After: after},
				}
			}
		}
		return s, nil // LEASE_RENEW_REQUIRED etc. belong to authority; per-command codes to commands

	case msg.Drain:
		if m.Deadline > 0 && s.Phase == PhaseActive {
			// Planned drain with a deadline: keep the connection open and let the
			// agent finish what is in flight (the composite reducer completes it).
			// The agent forces completion at 80% of the deadline so its
			// DrainComplete lands before the gateway closes the stream.
			s.Phase = PhaseDraining
			s.Quiescing = true
			s.DrainMessageID = m.MessageID
			s.DrainStartedAt = env.Now
			s.DrainReconnectAfter = m.ReconnectAfter
			return s, []reactor.Effect{reactor.ArmTimer{Kind: reactor.DrainDeadline, After: m.Deadline - m.Deadline/5}}
		}
		s.Phase = PhaseDraining
		// AGW design.md §5.6: the gateway assigns staggered reconnect_after
		// values across the drain window (§8 row 15: reconnects spread over
		// ≥80% of it). That stagger is server-assigned — jittering it further
		// here (fullJitter is uniform over [0, d]) can only pull an agent's
		// reconnect earlier than instructed and collapse the spread, so this
		// value is honoured verbatim, exactly like retryLater's explicit
		// server override below.
		return s, []reactor.Effect{
			reactor.Close{Reason: "drain: " + m.Reason},
			reactor.ArmTimer{Kind: reactor.ReconnectDue, After: m.ReconnectAfter},
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
	s.Quiescing = false
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
	s.Quiescing = false
	s.Phase = PhaseClosed
	s.MissedHeartbeats = 0
	s, effs := retryLater(s, env, retryAfter)
	return s, append([]reactor.Effect{reactor.Close{Reason: "reconnect"}}, effs...)
}

// drainRemaining is the part of the gateway's staggered reconnect_after that is
// still ahead: time spent finishing in-flight work counts toward it, so the
// stagger the gateway assigned is honoured rather than stretched.
func (s SessionState) drainRemaining(env reactor.Env) time.Duration {
	remaining := s.DrainReconnectAfter - env.Now.Sub(s.DrainStartedAt)
	if remaining < 0 {
		return 0
	}
	return remaining
}

// completeDrain answers a Drain: DrainComplete, close, reconnect on schedule.
func completeDrain(s SessionState, env reactor.Env, abandoned int) (SessionState, []reactor.Effect) {
	s.Quiescing = false
	// Closed, not Draining: the reconnect timer must work whether or not the loop delivers the close echo. When the
	// time spent finishing in-flight work already exceeds the assigned stagger the timer is armed at zero, the loop
	// then suppresses the echo (it assumes an immediate redial), and a session left in Draining would never dial.
	s.Phase = PhaseClosed
	return s, []reactor.Effect{
		reactor.Send{Msg: msg.DrainComplete{Envelope: env.Env(), InReplyTo: s.DrainMessageID, AbandonedCommands: uint32(abandoned)}},
		reactor.CancelTimer{Kind: reactor.DrainDeadline},
		reactor.ArmTimer{Kind: reactor.ReconnectDue, After: s.drainRemaining(env)},
		reactor.Close{Reason: "drain complete"},
	}
}
