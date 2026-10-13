package commands

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"slices"
	"sort"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
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
	Journal Journal
	// Spool is the set of (binding, generation) whose payload spool file is on
	// disk with the bytes the journal record's hash names. Load computes it after
	// the start-up sweep; resume() runs a RECEIVED record only when its payload
	// is here, so a restart resumes from the spool without a CommandPull
	// (OR-129, AG-D29). The reducer only ever deletes a key (terminate), never adds one.
	Spool map[string]bool
	// InFlight is the command whose op the worker is running right now: set
	// when a RunOp is dispatched, cleared only by that command's OpFinished.
	// Closing its record (a deadline, a SUPERSEDED) does not clear it — the
	// worker is still busy until it reports back. The single StepDeadline
	// timer belongs to this command and no other.
	InFlight string
	// Queue holds offered commands awaiting the worker, oldest first. The
	// worker runs one op at a time and §4.4 lets a stop interleave with an
	// outstanding apply, so a second command waits here instead of taking
	// over InFlight and the timer. In memory only: a queued command's record
	// is RECEIVED, which resume discards for the DP to redeliver.
	Queue []string
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
	if err != nil {
		return State{Journal: j}, err
	}
	spool, err := SweepSpool(s, j)
	return State{Journal: j, Spool: spool}, err
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

// AppliedGeneration is the highest generation of a binding whose config is on the host: PERSISTED, or REPORTED
// as APPLIED. A lower generation is superseded.
func (s State) AppliedGeneration(binding string) uint64 { return s.Journal.AppliedGeneration(binding) }

func (s State) OpenCount() int { return s.Journal.OpenCount() }

// Idle is true when no command is running or waiting for the worker. A planned
// drain (AGW-D18) completes only when the agent is idle, or its deadline hits.
func (s State) Idle() bool { return s.InFlight == "" && len(s.Queue) == 0 }

// Unfinished counts the commands a forced drain abandons: the running one plus
// the queue. The DP re-delivers them on the next session.
func (s State) Unfinished() int {
	n := len(s.Queue)
	if s.InFlight != "" {
		n++
	}
	return n
}

func Reduce(s State, ev reactor.Event, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	s, effs := s.reduce(ev, env, auth)
	// The one dispatch point. Checked after every event because any of them
	// can free the worker (OpFinished) or restore admission for a held step
	// (a lease grant, the clock returning to OK).
	if s.InFlight == "" && len(s.Queue) > 0 {
		var more []reactor.Effect
		s, more = s.startNext(env, auth)
		effs = append(effs, more...)
	}
	return s, effs
}

func (s State) reduce(ev reactor.Event, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	switch e := ev.(type) {
	case reactor.Resumed:
		return s.resume(env)

	case reactor.TimerFired:
		if e.Kind != reactor.StepDeadline || s.InFlight == "" {
			return s, nil
		}
		r, ok := s.Journal.ByCommand(s.InFlight)
		if !ok {
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
		return s.terminate(r, outcome, detail, "", nil, env)

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
		var effs []reactor.Effect
		if r, ok := s.Journal.ByCommand(e.CommandID); ok {
			if e.Result.Facts != nil {
				r.ReplicaRef = e.Result.Facts.ReplicaRef
			}
			s, effs = s.terminate(r, e.Result.Outcome, e.Result.Detail, e.Result.EffectiveHash, startedAtOf(r.Operation, e.Result), env)
		}
		if s.InFlight == e.CommandID {
			s.InFlight = ""
		}
		return s, effs

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
			Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now, Operation: r.Operation, ContainerStartedAt: r.ContainerStartedAt}}}
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
	if m.Operation == msg.OpRematerialize && m.ExpectedCurrentSHA256 == nil {
		// AG-D27 item 4: a REMATERIALIZE without its precondition is a protocol
		// error, never an unconditional write.
		return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
	}
	if prev, ok := s.Journal.Get(m.BindingID, m.Generation); ok {
		if prev.IdempotencyKey == m.IdempotencyKey && prev.ConfigHash != m.ConfigSHA256 {
			// same key, different bytes: a DP bug, never resolved by retry
			return s.refuse(m.CommandID, m.BindingID, m.Generation, m.Operation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
		}
		if prev.Terminal() {
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration,
				Outcome: prev.Outcome, Detail: prev.Detail, AgentClock: env.Now, Operation: prev.Operation, ContainerStartedAt: prev.ContainerStartedAt}}}
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
		BindingID: m.BindingID, Operation: m.Operation, ConfigHash: m.ConfigSHA256, Correlation: m.Correlation, Step: msg.StepReceived,
		StepDeadline: auth.StepDeadline(m.Operation, env.DPNow()), ReceivedAck: true,
		ExpectedCurrentSHA256: m.ExpectedCurrentSHA256,
	}
	s.Journal.Put(r)
	// Queued, not run: Reduce's dispatch point starts it at once if the worker
	// is free, and otherwise when the running op reports back.
	s.Queue = append(slices.Clip(s.Queue), m.CommandID)
	return s, []reactor.Effect{
		reactor.Persist{Rec: store.Blob{RelPath: PayloadRel(m.BindingID, m.Generation), Data: m.Payload}},
		reactor.Persist{Rec: r},
		reactor.Send{Msg: msg.CommandReceived{Envelope: env.Env(), CommandID: m.CommandID, Generation: m.Generation}},
		reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: m.CommandID, Step: msg.StepReceived, At: env.Now}},
	}
}

func (s State) wireError(m msg.Error, env reactor.Env) (State, []reactor.Effect) {
	if m.CorrelationID == "" {
		return s, nil
	}
	var outcome msg.Outcome
	switch m.Code {
	case msg.ErrSuperseded:
		outcome = msg.OutcomeSuperseded
	case msg.ErrUnknownCommand, msg.ErrStaleGeneration, msg.ErrRenderChanged:
		// ErrRenderChanged: the gateway's re-render after an SM restart no longer
		// matches what it recorded; a terminal refusal of this command (AG-D29 item 5).
		outcome = msg.OutcomeRefused
	default:
		return s, nil
	}
	detail := msg.AckDetail{Code: msg.DetailOf(m.Code)}
	if r, ok := s.Journal.ByCommand(m.CorrelationID); ok {
		return s.terminate(r, outcome, detail, "", nil, env)
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
func (s State) terminate(r Record, outcome msg.Outcome, detail msg.AckDetail, effectiveHash string, startedAt *time.Time, env reactor.Env) (State, []reactor.Effect) {
	if r.Terminal() {
		return s, nil
	}
	delete(s.Spool, key(r.BindingID, r.RolloutGeneration))
	r.Step, r.Outcome, r.Detail, r.AckSent = msg.StepReported, outcome, detail, true
	if effectiveHash != "" {
		r.EffectiveHash = effectiveHash
	}
	if startedAt != nil {
		r.ContainerStartedAt = startedAt
	}
	s.Journal.Put(r)
	effs := []reactor.Effect{reactor.Persist{Rec: r}}
	// The timer belongs to the running command. Closing a queued one — say,
	// SUPERSEDED while it waited — must leave it alone.
	if s.InFlight == r.CommandID {
		effs = append(effs, reactor.CancelTimer{Kind: reactor.StepDeadline})
	}
	effs = append(effs,
		reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: r.CommandID, Step: msg.StepReported, ReplicaRef: r.ReplicaRef, At: env.Now}},
		reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID, Generation: r.RolloutGeneration,
			Outcome: outcome, Detail: detail, AgentClock: env.Now, Operation: r.Operation, ContainerStartedAt: r.ContainerStartedAt}},
		// The spool purge (AG-D29 item 2): in the batch that makes the record
		// terminal, on EVERY terminal path (APPLIED, REFUSED, RENDER_CHANGED,
		// TARGET_MOVED, superseded, deadline). The effect order puts it before the
		// outcome leaves the process.
		reactor.Delete{Rel: DirRel(r.BindingID, r.RolloutGeneration)},
	)
	if outcome == msg.OutcomeApplied {
		for _, dir := range s.Journal.SupersededPartials(r.BindingID, r.RolloutGeneration) {
			effs = append(effs, reactor.Delete{Rel: dir})
		}
	}
	return s, effs
}

// startNext hands the worker the head of the queue. Reduce calls it only when
// the worker is free. The step deadline and admission are decided here, at
// dispatch, not at offer: spec §5.2 counts the deadline from step start and
// re-checks admission before each step, and a command may have waited behind
// another for longer than its whole ceiling.
func (s State) startNext(env reactor.Env, auth Authority) (State, []reactor.Effect) {
	var effs []reactor.Effect
	for s.InFlight == "" && len(s.Queue) > 0 {
		r, ok := s.Journal.ByCommand(s.Queue[0])
		if !ok || r.Terminal() {
			s.Queue = s.Queue[1:] // closed while it waited, e.g. SUPERSEDED
			continue
		}
		dpNow := env.DPNow()
		// A generation below one already applied is superseded (a crash between the newer record and the
		// older one's partial-delete leaves it queued after a restart); it must not write stale config over
		// the newer one.
		if r.RolloutGeneration < s.AppliedGeneration(r.BindingID) {
			s.Queue = s.Queue[1:]
			var more []reactor.Effect
			s, more = s.terminate(r, msg.OutcomeRefused, msg.AckDetail{Code: msg.DetailOf(msg.ErrStaleGeneration)}, "", nil, env)
			effs = append(effs, more...)
			continue
		}
		if d := auth.RefusalDetail(r.Operation, r.BindingID, dpNow); d != msg.DetailNone {
			// A RECEIVED record resumed from the spool (OR-129) is held the same way: the
			// DP acknowledged it as received and awaits its outcome, so refusing it for a
			// lease that has not come back yet would turn a restart into a REFUSED.
			if (r.Step != msg.StepReceived || s.Spool[key(r.BindingID, r.RolloutGeneration)]) && (d == msg.DetailLeaseNotActive || d == msg.DetailAdmissionBlocked) {
				// A resumed STAGED/PERSISTED step may already have changed the
				// target, so refusing it would report no effect for an effect
				// that may exist. Hold it — and everything behind it, keeping a
				// binding's generations in order — until the lease admits it
				// (AG-D5: resolved on the next ACTIVE session by replaying).
				return s, effs
			}
			s.Queue = s.Queue[1:]
			var more []reactor.Effect
			s, more = s.terminate(r, msg.OutcomeRefused, msg.AckDetail{Code: d}, "", nil, env)
			effs = append(effs, more...)
			continue
		}
		s.Queue = s.Queue[1:]
		// Dispatched in the same event as its offer, the deadline is unchanged
		// and the record needs no second write.
		if deadline := auth.StepDeadline(r.Operation, dpNow); !deadline.Equal(r.StepDeadline) {
			r.StepDeadline = deadline
			s.Journal.Put(r)
			effs = append(effs, reactor.Persist{Rec: r})
		}
		s.InFlight = r.CommandID
		effs = append(effs,
			reactor.RunOp{CommandID: r.CommandID, BindingID: r.BindingID, Op: r.Operation,
				PayloadRel: PayloadRel(r.BindingID, r.RolloutGeneration), SHA256: r.ConfigHash, Deadline: r.StepDeadline,
				ExpectedCurrentSHA256: r.ExpectedCurrentSHA256},
			reactor.ArmTimer{Kind: reactor.StepDeadline, After: r.StepDeadline.Sub(dpNow)},
		)
	}
	return s, effs
}

// resume replays the journal per the AG-D4 table, in (binding, generation)
// order so the outcome never depends on map iteration. STAGED and PERSISTED
// steps are queued, not run: the dispatch point starts them one at a time with
// a fresh deadline — the one written before the crash is long past after any
// real outage, and would fail a step AG-D5 says must be replayed — and holds
// them while the lease does not admit a config write.
func (s State) resume(env reactor.Env) (State, []reactor.Effect) {
	recs := make([]Record, 0, len(s.Journal.Records))
	for _, r := range s.Journal.Records {
		recs = append(recs, r)
	}
	sort.Slice(recs, func(i, j int) bool {
		if recs[i].BindingID != recs[j].BindingID {
			return recs[i].BindingID < recs[j].BindingID
		}
		return recs[i].RolloutGeneration < recs[j].RolloutGeneration
	})
	var effs []reactor.Effect
	for _, r := range recs {
		switch r.Step {
		case msg.StepReceived:
			if s.Spool[key(r.BindingID, r.RolloutGeneration)] {
				// OR-129: the payload is on the host's spool with the bytes the record's
				// hash names, so the command resumes from it without a CommandPull. The
				// dispatch point re-checks admission and gives it a fresh deadline.
				s.Queue = append(slices.Clip(s.Queue), r.CommandID)
				continue
			}
			// nothing was written and no valid spool; discard and let the DP redeliver. Delete
			// both the payload/temp directory AND the sibling
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
			// the adapter verifies the temp / reads back the file and never
			// rewrites a PERSISTED target, so replaying is safe
			s.Queue = append(slices.Clip(s.Queue), r.CommandID)
		case msg.StepReported:
			if !r.AckSent {
				effs = append(effs, reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID,
					Generation: r.RolloutGeneration, Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now, Operation: r.Operation, ContainerStartedAt: r.ContainerStartedAt}})
			}
		}
	}
	return s, effs
}

// startedAtOf is the host-clock StartedAt the terminal ack carries (AG-D27 item
// 2, 5): APPLY and REMATERIALIZE report the value read back when the write was
// verified, RESTART the value after the restart. PREFLIGHT and STOP carry none,
// and a never-started container (zero time) is absent, not 0001-01-01.
func startedAtOf(op msg.Operation, res runtime.OpResult) *time.Time {
	if res.Outcome != msg.OutcomeApplied {
		return nil // a failed attempt establishes no OR-112 baseline
	}
	switch op {
	case msg.OpApply, msg.OpRematerialize, msg.OpRestart:
	default:
		return nil
	}
	if res.Facts == nil || res.Facts.StartedAt.IsZero() {
		return nil
	}
	t := res.Facts.StartedAt
	return &t
}
