package commands

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// Marshal makes Record satisfy store.Record directly (it already has Rel,
// from journal.go) so the reducer below can persist a Record without the
// store.JSON{Value: ...} wrapper other reducers use.
func (r Record) Marshal() ([]byte, error) { return json.MarshalIndent(r, "", "  ") }

// Authority is the read-only slice of the lease reducer this one consults.
// authority.State satisfies it; this package does not import authority.
type Authority interface {
	RefusalDetail(op msg.Operation, bindingID string, dpNow time.Time) msg.DetailCode
	StepDeadline(op msg.Operation, start time.Time) time.Time
	Active() bool
}

type State struct {
	Journal  Journal
	InFlight string
	// Pulling correlates a CommandID to its (binding, generation) while a
	// CommandPull is outstanding and nothing has been journaled for it yet
	// (offer() writes the RECEIVED record only once the payload arrives).
	// A pull-stage error (SUPERSEDED, UNKNOWN_COMMAND, STALE_GENERATION)
	// otherwise has nowhere durable to look the command up: msg.Error
	// carries only a CorrelationID, not a binding/generation. Not persisted
	// — if the agent crashes before either an Offer or an Error resolves
	// the pull, the DP simply redelivers the GenerationAvailable notice.
	//
	// This crash case is the only one it covers, and deliberately so: a
	// SUPERSEDED pull answering with "the agent records that outcome
	// without acting" (gateway design.md §4.4 rule 7) and "a pull or ack
	// for a superseded command is answered SUPERSEDED and recorded as
	// such" (§4.7 rule 4) is exactly this path. What it does NOT cover is
	// the ack built from Pulling being lost in transit (as opposed to lost
	// to a crash): nothing resends it, because §4.7 rule 3's
	// re-notification-on-reclaim scope is QUEUED/OFFERED/DELIVERED only —
	// a command the DP has already marked SUPERSEDED and withdrawn is not
	// re-notified, so there is no second GenerationAvailable to re-trigger
	// this path. This mirrors refuse() below, which has the identical
	// property for every other pre-journal terminal ack in this file (a
	// bare Send, no Persist) — a pre-existing, accepted tradeoff in this
	// reducer, not new risk this addition introduces. Closing it for real
	// would mean durable pre-journal outcome tracking across every
	// refuse()/Pulling call site, a broader change than this one.
	Pulling map[string]pending
}

// pending is Pulling's value: just enough to ack a command that errors out
// before it is ever journaled.
type pending struct {
	BindingID  string
	Generation uint64
	Operation  msg.Operation
}

func Load(s *store.Store) (State, error) {
	j, err := LoadJournal(s)
	return State{Journal: j}, err
}

// PersistedGeneration is the highest generation durably written for a binding.
func (s State) PersistedGeneration(binding string) uint64 {
	var out uint64
	for _, r := range s.Journal.Records {
		if r.BindingID != binding {
			continue
		}
		if (r.Step == msg.StepPersisted || r.Step == msg.StepReported) && r.Outcome != msg.OutcomeRefused && r.RolloutGeneration > out {
			out = r.RolloutGeneration
		}
	}
	return out
}

func (s State) OpenCount() int { return s.Journal.OpenCount() }

func Reduce(s State, ev reactor.Event, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	switch e := ev.(type) {
	case reactor.Resumed:
		return s.resume(env)

	case reactor.TimerFired:
		if e.Kind != reactor.StepDeadline || s.InFlight == "" {
			return s, nil
		}
		r, ok := s.Journal.ByCommand(s.InFlight)
		if !ok {
			s.InFlight = ""
			return s, nil
		}
		// AG-D5: APPLY_INDETERMINATE only when a step was in flight and the
		// lease left ACTIVE; otherwise a blown deadline is an ordinary failure.
		outcome := msg.OutcomeFailedNoEffect
		detail := msg.AckDetail{Message: "step deadline exceeded"}
		if !auth.Active() {
			outcome = msg.OutcomeApplyIndeterminate
			detail = msg.AckDetail{Code: msg.DetailLeaseNotActive, Message: "lease left ACTIVE with a step in flight"}
		}
		return s.terminate(r, outcome, detail, "", env)

	case reactor.OpProgress:
		r, ok := s.Journal.ByCommand(e.CommandID)
		if !ok {
			return s, nil
		}
		r.Step = e.Step
		s.Journal.Put(r)
		return s, []reactor.Effect{
			reactor.Persist{Rec: r},
			reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: r.CommandID, Step: e.Step, ReplicaRef: r.ReplicaRef, At: env.Now}},
		}

	case reactor.OpFinished:
		r, ok := s.Journal.ByCommand(e.CommandID)
		if !ok {
			return s, nil
		}
		if e.Result.Facts != nil {
			r.ReplicaRef = e.Result.Facts.ReplicaRef
		}
		return s.terminate(r, e.Result.Outcome, e.Result.Detail, e.Result.EffectiveHash, env)

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.GenerationAvailable:
			return s.notice(m, env, auth)
		case msg.CommandOffer:
			return s.offer(m, env, auth)
		case msg.Error:
			return s.wireError(m, env)
		}
	}
	return s, nil
}

func (s State) refuse(commandID, bindingID string, gen uint64, op msg.Operation, code msg.DetailCode, env reactor.Env) (State, []reactor.Effect) {
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: commandID, Generation: gen,
		Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: code}, AgentClock: env.Now, Operation: op}}}
}

func (s State) notice(m msg.GenerationAvailable, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	if d := auth.RefusalDetail(m.Operation, m.BindingID, env.DPNow()); d != msg.DetailNone {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, d, env)
	}
	if r, ok := s.Journal.Get(m.BindingID, m.Generation); ok && r.Terminal() {
		// redelivery of a finished generation: replay the stored outcome
		return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID, Generation: r.RolloutGeneration,
			Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now, Operation: r.Operation}}}
	}
	if m.Generation < s.Journal.Watermark[m.BindingID] {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrStaleGeneration), env)
	}
	if s.Pulling == nil {
		s.Pulling = map[string]pending{}
	}
	s.Pulling[m.CommandID] = pending{BindingID: m.BindingID, Generation: m.Generation, Operation: m.Operation}
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandPull{Envelope: env.Env(), CommandID: m.CommandID}}}
}

func (s State) offer(m msg.CommandOffer, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	// The pull cycle for this CommandID is over the moment a CommandOffer
	// arrives, whatever offer() decides to do with it below.
	delete(s.Pulling, m.CommandID)
	if d := auth.RefusalDetail(m.Operation, m.BindingID, env.DPNow()); d != msg.DetailNone {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, d, env)
	}
	if env.Limits.MaxMessageBytes > 0 && uint64(len(m.Payload)) > env.Limits.MaxMessageBytes {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrPayloadTooLarge), env)
	}
	if sha256Hex(m.Payload) != m.ConfigSHA256 {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
	}
	if prev, ok := s.Journal.Get(m.BindingID, m.Generation); ok {
		if prev.IdempotencyKey == m.IdempotencyKey && prev.ConfigHash != m.ConfigSHA256 {
			// same key, different bytes: a DP bug, never resolved by retry
			return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
		}
		if prev.Terminal() {
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration,
				Outcome: prev.Outcome, Detail: prev.Detail, AgentClock: env.Now, Operation: prev.Operation}}}
		}
		if prev.ReceivedAck {
			// in flight and already acknowledged: re-announce, do not re-run
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandReceived{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration}}}
		}
	}
	if m.Generation < s.Journal.Watermark[m.BindingID] {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrStaleGeneration), env)
	}

	r := Record{
		SchemaVersion: schemaVersion, RolloutGeneration: m.Generation, IdempotencyKey: m.IdempotencyKey, CommandID: m.CommandID,
		BindingID: m.BindingID, Operation: m.Operation, ConfigHash: m.ConfigSHA256, Step: msg.StepReceived,
		StepDeadline: auth.StepDeadline(m.Operation, env.DPNow()), ReceivedAck: true,
	}
	s.Journal.Put(r)
	s.InFlight = m.CommandID
	effs := []reactor.Effect{
		reactor.Persist{Rec: store.Blob{RelPath: PayloadRel(m.BindingID, m.Generation), Data: m.Payload}},
		reactor.Persist{Rec: r},
		reactor.Send{Msg: msg.CommandReceived{Envelope: env.Env(), CommandID: m.CommandID, Generation: m.Generation}},
		reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: m.CommandID, Step: msg.StepReceived, At: env.Now}},
		reactor.RunOp{CommandID: m.CommandID, BindingID: m.BindingID, Op: m.Operation, PayloadRel: PayloadRel(m.BindingID, m.Generation),
			SHA256: m.ConfigSHA256, Deadline: r.StepDeadline},
		reactor.ArmTimer{Kind: reactor.StepDeadline, After: r.StepDeadline.Sub(env.DPNow())},
	}
	return s, effs
}

func (s State) wireError(m msg.Error, env reactor.Env) (State, []reactor.Effect) {
	if m.CorrelationID == "" {
		return s, nil
	}
	var outcome msg.Outcome
	switch m.Code {
	case msg.ErrSuperseded:
		outcome = msg.OutcomeSuperseded
	case msg.ErrUnknownCommand, msg.ErrStaleGeneration:
		outcome = msg.OutcomeRefused
	default:
		return s, nil
	}
	detail := msg.AckDetail{Code: msg.DetailOf(m.Code)}
	if r, ok := s.Journal.ByCommand(m.CorrelationID); ok {
		return s.terminate(r, outcome, detail, "", env)
	}
	// No journal record: the error arrived while a CommandPull was still
	// outstanding, before offer() ever journaled a RECEIVED record for it.
	// Ack from the pending-pull correlation instead, so the DP still gets
	// closure on a command it will never redeliver under this id — there is
	// nothing to terminate in the journal because nothing was ever written
	// to it.
	p, ok := s.Pulling[m.CorrelationID]
	if !ok {
		return s, nil
	}
	delete(s.Pulling, m.CorrelationID)
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: m.CorrelationID, Generation: p.Generation,
		Outcome: outcome, Detail: detail, AgentClock: env.Now, Operation: p.Operation}}}
}

// terminate closes a record, acks it, and sweeps superseded partials. A
// record that is already terminal is a no-op: a step-deadline timer can fire
// APPLY_INDETERMINATE and then the operation it was timing out on finishes
// anyway (OpFinished delivered after the timer, both racing the same
// in-flight command) — without this guard the second terminate() would
// overwrite the record and send a second, contradictory ack for a command
// already closed.
func (s State) terminate(r Record, outcome msg.Outcome, detail msg.AckDetail, effectiveHash string, env reactor.Env) (State, []reactor.Effect) {
	if r.Terminal() {
		return s, nil
	}
	r.Step, r.Outcome, r.Detail, r.AckSent = msg.StepReported, outcome, detail, true
	if effectiveHash != "" {
		r.EffectiveHash = effectiveHash
	}
	s.Journal.Put(r)
	if s.InFlight == r.CommandID {
		s.InFlight = ""
	}
	effs := []reactor.Effect{
		reactor.Persist{Rec: r},
		reactor.CancelTimer{Kind: reactor.StepDeadline},
		reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: r.CommandID, Step: msg.StepReported, ReplicaRef: r.ReplicaRef, At: env.Now}},
		reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID, Generation: r.RolloutGeneration,
			Outcome: outcome, Detail: detail, AgentClock: env.Now, Operation: r.Operation}},
		reactor.Delete{Rel: PayloadRel(r.BindingID, r.RolloutGeneration)},
	}
	if outcome == msg.OutcomeApplied {
		for _, dir := range s.Journal.SupersededPartials(r.BindingID, r.RolloutGeneration) {
			effs = append(effs, reactor.Delete{Rel: dir})
		}
	}
	return s, effs
}

// resume replays the journal per the AG-D4 table.
func (s State) resume(env reactor.Env) (State, []reactor.Effect) {
	var effs []reactor.Effect
	for _, r := range s.Journal.Records {
		switch r.Step {
		case msg.StepReceived:
			// nothing was written; discard and let the DP redeliver. Delete
			// both the payload/temp/previous directory AND the sibling
			// record file (r.Rel()) — leaving the record behind orphans it
			// with ReceivedAck=true and no payload, which makes offer()'s
			// "in flight and already acknowledged" branch re-announce a
			// command that will never run again. Drop it from the
			// in-memory journal too, so OpenCount() is correct immediately
			// rather than only after the next restart reloads the (now
			// absent) file from disk.
			effs = append(effs, reactor.Delete{Rel: DirRel(r.BindingID, r.RolloutGeneration)}, reactor.Delete{Rel: r.Rel()})
			s.Journal.Delete(r.BindingID, r.RolloutGeneration)
		case msg.StepStaged, msg.StepPersisted:
			// re-enter the adapter: it verifies the temp / reads back the file
			// and never rewrites a PERSISTED target
			if s.InFlight == "" {
				s.InFlight = r.CommandID
			}
			effs = append(effs,
				reactor.RunOp{CommandID: r.CommandID, BindingID: r.BindingID, Op: r.Operation,
					PayloadRel: PayloadRel(r.BindingID, r.RolloutGeneration), SHA256: r.ConfigHash, Deadline: r.StepDeadline},
				reactor.ArmTimer{Kind: reactor.StepDeadline, After: r.StepDeadline.Sub(env.DPNow())},
			)
		case msg.StepReported:
			if !r.AckSent {
				effs = append(effs, reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID,
					Generation: r.RolloutGeneration, Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now, Operation: r.Operation}})
			}
		}
	}
	return s, effs
}
