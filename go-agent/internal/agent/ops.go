package agent

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// opRunner executes one runtime operation and maps it to an outcome. Journal
// steps reached mid-write are pushed back through the loop as OpProgress and
// journaled when the loop reduces them — after the writer has moved on, not
// before; runtime.Progress states why that ordering is safe.
type opRunner struct {
	adapter runtime.Adapter
	store   *store.Store
	push    func(reactor.Event)
	now     func() time.Time
	// offset is the current DP clock offset (DPNow = wall now + offset), the
	// same source the loop feeds into reactor.Env for the reducers. op.Deadline
	// arrives in DP time (it was stamped via auth.StepDeadline(op, env.DPNow())
	// in the commands reducer); context.WithDeadline compares against the wall
	// clock, so it must be converted back before use — the same class of bug
	// already fixed once at Task 18 in commands.resume() for ArmTimer.
	offset func() time.Duration
	// probe returns the DP-declared health probe for a binding, or nil when the
	// package declares none (then nothing is probed, OR-101).
	probe func(binding string) *msg.HealthProbe
}

// MaxDetailMessage bounds the ack detail text.
const MaxDetailMessage = 160

// detailMessage turns a runtime error into the ack detail text WITHOUT ever
// copying the error's own text (plan 015 S4, Global constraint 9). A config
// writer or Docker error can quote the rendered file (a YAML parse error names
// the offending line, a hash mismatch names the digests); the payload can hold
// resolved secrets, and the digest of it lets a log reader test guessed ones. So
// the text is a code-keyed template chosen from the error's class, bounded and
// printable, and the error itself stays inside the agent.
func detailMessage(err error) string {
	var we *configwriter.WriteError
	switch {
	case err == nil:
		return ""
	case errors.Is(err, runtime.ErrContainerNotFound):
		return "container not found"
	case errors.Is(err, runtime.ErrContainerAmbiguous):
		return "more than one container matches"
	case errors.Is(err, runtime.ErrNoDeclaredPath):
		return "container declares no external config location"
	case errors.Is(err, runtime.ErrNoBindMount):
		return "config directory is not a bind mount"
	case errors.Is(err, runtime.ErrPortUnreachable):
		return "port is not reachable"
	case errors.As(err, &we):
		return boundDetail("config write failed: " + we.Phase)
	case errors.Is(err, context.DeadlineExceeded):
		return "step deadline exceeded"
	case errors.Is(err, context.Canceled):
		return "step cancelled"
	case errors.Is(err, configwriter.ErrSymlinkEscape):
		return "config target escapes its declared directory"
	}
	var pe *fs.PathError
	if errors.As(err, &pe) {
		return boundDetail("file operation failed: " + pe.Op)
	}
	return boundDetail(fmt.Sprintf("runtime operation failed (%T)", err))
}

func boundDetail(s string) string {
	b := make([]rune, 0, len(s))
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			r = ' '
		}
		b = append(b, r)
	}
	if len(b) > MaxDetailMessage {
		b = b[:MaxDetailMessage]
	}
	return string(b)
}

// health observes the declared probe's facts after an operation; nil when the
// adapter cannot.
func (r *opRunner) health(ctx context.Context, p runtime.Placement, binding string) *msg.HealthObservation {
	ho, ok := r.adapter.(runtime.HealthObserver)
	if !ok {
		return nil
	}
	var probe *msg.HealthProbe
	if r.probe != nil {
		probe = r.probe(binding)
	}
	h := ho.ObserveHealth(ctx, p, probe)
	if h.State == msg.HealthUnspecified {
		return nil // the adapter observed nothing
	}
	return &h
}

func (r *opRunner) Run(ctx context.Context, op reactor.RunOp) runtime.OpResult {
	p, err := r.adapter.Resolve(ctx, op.BindingID)
	if err != nil {
		return refusedResult(op, err)
	}
	wallDeadline := op.Deadline
	if r.offset != nil {
		wallDeadline = op.Deadline.Add(-r.offset())
	}
	ctx, cancel := context.WithDeadline(ctx, wallDeadline)
	defer cancel()

	switch op.Op {
	case msg.OpPreflight:
		pr, err := r.adapter.Preflight(ctx, p)
		if err != nil {
			return runtime.OpResult{Outcome: msg.OutcomePreflightRefused, Detail: msg.AckDetail{Message: detailMessage(err)}, Placement: &p}
		}
		if !pr.Writable {
			return runtime.OpResult{Outcome: msg.OutcomePreflightRefused, Detail: msg.AckDetail{Message: "config directory is not writable", PreflightResult: &pr}, Placement: &p}
		}
		facts, _ := r.adapter.Observe(ctx, p)
		return runtime.OpResult{Outcome: msg.OutcomePreflightOK, Detail: msg.AckDetail{PreflightResult: &pr}, Facts: &facts, Placement: &p}

	case msg.OpApply, msg.OpRematerialize:
		payload, err := os.ReadFile(r.store.Path(op.PayloadRel))
		if err != nil {
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "payload unreadable"}, Placement: &p}
		}
		if op.Op == msg.OpRematerialize {
			// AG-D27 item 3: hash the file as it stands BEFORE writing; any other
			// content (or presence) than the one the DP rendered against means the
			// target moved and nothing is written.
			if res, refused := r.precondition(op, p); refused {
				return res
			}
		}
		if ctx.Err() != nil {
			// the step's budget is already spent: never start a write the deadline has timed out
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "step deadline exceeded"}, Placement: &p}
		}
		progress := func(step msg.ProgressStep) error {
			r.push(reactor.OpProgress{At: r.now(), CommandID: op.CommandID, Step: step})
			return nil
		}
		var res runtime.WriteResult
		conditional := false
		if cw, ok := r.adapter.(runtime.ConditionalWriter); ok && op.Op == msg.OpRematerialize {
			// the precondition is evaluated on the writer's own read (AG-D27 item 3)
			res, err = cw.WriteConfigIf(ctx, p, payload, op.SHA256, op.ExpectedCurrentSHA256, progress)
			conditional = !errors.Is(err, runtime.ErrNoConditionalWrite)
		}
		if !conditional {
			res, err = r.adapter.WriteConfig(ctx, p, payload, op.SHA256, progress)
		}
		if errors.Is(err, configwriter.ErrTargetMoved) {
			return runtime.OpResult{Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrTargetMoved)}, Placement: &p}
		}
		facts, _ := r.adapter.Observe(ctx, p)
		if err != nil {
			var we *configwriter.WriteError
			outcome := msg.OutcomeFailedNoEffect
			if errors.As(err, &we) {
				switch we.Phase {
				case configwriter.PhaseAfterRenameRolledBack:
					outcome = msg.OutcomeFailedRolledBack
				case configwriter.PhaseAfterRenameUnknown:
					outcome = msg.OutcomeApplyIndeterminate
				}
			}
			return runtime.OpResult{Outcome: outcome, Detail: msg.AckDetail{Message: detailMessage(err)}, Facts: &facts, Placement: &p, Health: r.health(ctx, p, op.BindingID)}
		}
		return runtime.OpResult{Outcome: msg.OutcomeApplied, EffectiveHash: res.Hash, Facts: &facts, Placement: &p, Health: r.health(ctx, p, op.BindingID)}

	case msg.OpRestart, msg.OpStop:
		before, _ := r.adapter.Observe(ctx, p)
		var opErr error
		if op.Op == msg.OpRestart {
			opErr = r.adapter.Restart(ctx, p)
		} else {
			opErr = r.adapter.Stop(ctx, p)
		}
		after, obsErr := r.adapter.Observe(ctx, p)
		if obsErr != nil {
			return runtime.OpResult{Outcome: msg.OutcomeApplyIndeterminate, Detail: msg.AckDetail{Message: "post-state unobservable"}, Placement: &p}
		}
		if reached(op.Op, before, after) {
			return runtime.OpResult{Outcome: msg.OutcomeApplied, Facts: &after, Placement: &p, Health: r.health(ctx, p, op.BindingID)}
		}
		if opErr != nil {
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: detailMessage(opErr)}, Facts: &after, Placement: &p, Health: r.health(ctx, p, op.BindingID)}
		}
		// AG-D19: the verb reported success but the post-state is not the one
		// asked for. AG-D5 has no outcome for "changed and still failed", so
		// the honest answer is a failure plus the observed facts in the report.
		return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "post-state not reached"}, Facts: &after, Placement: &p, Health: r.health(ctx, p, op.BindingID)}
	}
	return runtime.OpResult{Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Message: "unknown operation"}}
}

func reached(op msg.Operation, before, after runtime.ReplicaFacts) bool {
	if op == msg.OpStop {
		return !after.Running
	}
	return after.Running && after.StartedAt.After(before.StartedAt)
}

func refusedResult(op reactor.RunOp, err error) runtime.OpResult {
	outcome := msg.OutcomeFailedNoEffect
	if op.Op == msg.OpPreflight {
		outcome = msg.OutcomePreflightRefused
	}
	return runtime.OpResult{Outcome: outcome, Detail: msg.AckDetail{Message: detailMessage(err)}}
}

// precondition evaluates OPERATION_REMATERIALIZE's expected_current_sha256 against
// the host file. Equal digest (or an absent file for an empty expectation) passes.
// A file that already holds the offer's own payload also passes: that is a replay
// after a crash between the rename and the journal (the writer is idempotent and
// reports PERSISTED without rewriting), and it is the only way a crashed
// REMATERIALIZE can finish.
func (r *opRunner) precondition(op reactor.RunOp, p runtime.Placement) (runtime.OpResult, bool) {
	if op.ExpectedCurrentSHA256 == nil {
		return runtime.OpResult{Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrKeyHashMismatch)}, Placement: &p}, true
	}
	cur, exists, err := configwriter.Current(p.HostPath)
	if err != nil {
		return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: detailMessage(err)}, Placement: &p}, true
	}
	want := *op.ExpectedCurrentSHA256
	switch {
	case want == "" && !exists:
		return runtime.OpResult{}, false
	case exists && cur == want:
		return runtime.OpResult{}, false
	case exists && cur == op.SHA256:
		return runtime.OpResult{}, false
	}
	return runtime.OpResult{Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrTargetMoved)}, Placement: &p}, true
}
