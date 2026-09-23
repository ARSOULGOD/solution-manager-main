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

func (s State) refuse(commandID, bindingID string, gen uint64, code msg.DetailCode, env reactor.Env) (State, []reactor.Effect) {
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: commandID, Generation: gen,
		Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: code}, AgentClock: env.Now}}}
}

func (s State) notice(m msg.GenerationAvailable, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	if d := auth.RefusalDetail(m.Operation, m.BindingID, env.DPNow()); d != msg.DetailNone {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, d, env)
	}
	if r, ok := s.Journal.Get(m.BindingID, m.Generation); ok && r.Terminal() {
		// redelivery of a finished generation: replay the stored outcome
		return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID, Generation: r.RolloutGeneration,
			Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now}}}
	}
	if m.Generation < s.Journal.Watermark[m.BindingID] {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrStaleGeneration), env)
	}
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandPull{Envelope: env.Env(), CommandID: m.CommandID}}}
}

func (s State) offer(m msg.CommandOffer, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	if d := auth.RefusalDetail(m.Operation, m.BindingID, env.DPNow()); d != msg.DetailNone {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, d, env)
	}
	if env.Limits.MaxMessageBytes > 0 && uint64(len(m.Payload)) > env.Limits.MaxMessageBytes {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrPayloadTooLarge), env)
	}
	if sha256Hex(m.Payload) != m.ConfigSHA256 {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
	}
	if prev, ok := s.Journal.Get(m.BindingID, m.Generation); ok {
		if prev.IdempotencyKey == m.IdempotencyKey && prev.ConfigHash != m.ConfigSHA256 {
			// same key, different bytes: a DP bug, never resolved by retry
			return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
		}
		if prev.Terminal() {
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration,
				Outcome: prev.Outcome, Detail: prev.Detail, AgentClock: env.Now}}}
		}
		if prev.ReceivedAck {
			// in flight and already acknowledged: re-announce, do not re-run
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandReceived{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration}}}
		}
	}
	if m.Generation < s.Journal.Watermark[m.BindingID] {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrStaleGeneration), env)
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
	r, ok := s.Journal.ByCommand(m.CorrelationID)
	if !ok {
		return s, nil
	}
	switch m.Code {
	case msg.ErrSuperseded:
		return s.terminate(r, msg.OutcomeSuperseded, msg.AckDetail{Code: msg.DetailOf(msg.ErrSuperseded)}, "", env)
	case msg.ErrUnknownCommand, msg.ErrStaleGeneration:
		return s.terminate(r, msg.OutcomeRefused, msg.AckDetail{Code: msg.DetailOf(m.Code)}, "", env)
	}
	return s, nil
}

// terminate closes a record, acks it, and sweeps superseded partials.
func (s State) terminate(r Record, outcome msg.Outcome, detail msg.AckDetail, effectiveHash string, env reactor.Env) (State, []reactor.Effect) {
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
			Outcome: outcome, Detail: detail, AgentClock: env.Now}},
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
			// nothing was written; discard and let the DP redeliver
			effs = append(effs, reactor.Delete{Rel: DirRel(r.BindingID, r.RolloutGeneration)})
		case msg.StepStaged, msg.StepPersisted:
			// re-enter the adapter: it verifies the temp / reads back the file
			// and never rewrites a PERSISTED target
			if s.InFlight == "" {
				s.InFlight = r.CommandID
			}
			effs = append(effs,
				reactor.RunOp{CommandID: r.CommandID, BindingID: r.BindingID, Op: r.Operation,
					PayloadRel: PayloadRel(r.BindingID, r.RolloutGeneration), SHA256: r.ConfigHash, Deadline: r.StepDeadline},
				reactor.ArmTimer{Kind: reactor.StepDeadline, After: time.Until(r.StepDeadline)},
			)
		case msg.StepReported:
			if !r.AckSent {
				effs = append(effs, reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID,
					Generation: r.RolloutGeneration, Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now}})
			}
		}
	}
	return s, effs
}
