package agent

import (
	"context"
	"errors"
	"os"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// opRunner executes one runtime operation and maps it to an outcome. Journal
// steps reached mid-write are pushed back through the loop as OpProgress, so
// the record is on disk before the writer proceeds (write-before-act).
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
			return runtime.OpResult{Outcome: msg.OutcomePreflightRefused, Detail: msg.AckDetail{Message: err.Error()}, Placement: &p}
		}
		if !pr.Writable {
			return runtime.OpResult{Outcome: msg.OutcomePreflightRefused, Detail: msg.AckDetail{Message: "config directory is not writable", PreflightResult: &pr}, Placement: &p}
		}
		facts, _ := r.adapter.Observe(ctx, p)
		return runtime.OpResult{Outcome: msg.OutcomePreflightOK, Detail: msg.AckDetail{PreflightResult: &pr}, Facts: &facts, Placement: &p}

	case msg.OpApply:
		payload, err := os.ReadFile(r.store.Path(op.PayloadRel))
		if err != nil {
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "payload unreadable"}, Placement: &p}
		}
		prev := r.store.Path(commands.PreviousRel(op.BindingID, generationOf(op)))
		res, err := r.adapter.WriteConfig(ctx, p, payload, op.SHA256, prev, func(step msg.ProgressStep) error {
			r.push(reactor.OpProgress{At: r.now(), CommandID: op.CommandID, Step: step})
			return nil
		})
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
			return runtime.OpResult{Outcome: outcome, Detail: msg.AckDetail{Message: err.Error()}, Facts: &facts, Placement: &p}
		}
		return runtime.OpResult{Outcome: msg.OutcomeApplied, EffectiveHash: res.Hash, Facts: &facts, Placement: &p}

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
			return runtime.OpResult{Outcome: msg.OutcomeApplied, Facts: &after, Placement: &p}
		}
		if opErr != nil {
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: opErr.Error()}, Facts: &after, Placement: &p}
		}
		// AG-D19: the verb reported success but the post-state is not the one
		// asked for. AG-D5 has no outcome for "changed and still failed", so
		// the honest answer is a failure plus the observed facts in the report.
		return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "post-state not reached"}, Facts: &after, Placement: &p}
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
	return runtime.OpResult{Outcome: outcome, Detail: msg.AckDetail{Message: err.Error()}}
}

// generationOf recovers the generation from the payload path the reducer set.
func generationOf(op reactor.RunOp) uint64 { return commands.GenerationFromRel(op.PayloadRel) }
