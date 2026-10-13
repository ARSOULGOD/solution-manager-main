package com.solace.sm.dp.apply.reconcile;

import com.solace.sm.dp.apply.engine.HostObservation;
import com.solace.sm.dp.apply.engine.RedrivePlan;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the reconciler does with one fresh {@link HostObservation} (plan 010 T6.8, the ruled decision table). Pure: no I/O,
 * no clock, so every row of the table is a unit test.
 */
public sealed interface ReconcileDecision {

    /**
     * The host already holds what the request intended, and (where the strategy restarts) has been restarted by it. A read
     * proves it; nothing is written, nothing restarted. {@code gateProved}: a gated strategy whose declared probe said
     * {@code HEALTHY} in the same observation (Q-R2).
     */
    record Confirm(boolean gateProved) implements ReconcileDecision {
    }

    /** Re-apply and/or restart-only, per replica. Bounded by the caller's counters, already checked. */
    record Redrive(RedrivePlan plan) implements ReconcileDecision {

        public Redrive {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /** A failed or undecided inspection: retry with backoff (bounded by max-attempts). */
    record Retry(ErrorClass why) implements ReconcileDecision {

        public Retry {
            Objects.requireNonNull(why, "why");
        }
    }

    /** Stop for good: the request stays {@code APPLY_INDETERMINATE} carrying this classifier, and an alert is published. */
    record GiveUp(ErrorClass why) implements ReconcileDecision {

        public GiveUp {
            Objects.requireNonNull(why, "why");
        }
    }

    /**
     * @param laterRequestExists a newer request exists on the scope (a stop admitted while this was indeterminate)
     * @param redriveTooLate     the request's first sighting is older than {@code max-redrive-age}
     * @param redrivesDone       re-drives already spent
     * @param maxRedrives        the cap
     */
    record Context(boolean laterRequestExists, boolean redriveTooLate, int redrivesDone, int maxRedrives) {
    }

    /**
     * The decision table.
     *
     * <ol>
     *   <li>A request no observation can settle gives up with its own class; an undecided observation is a failed
     *       inspection (retry). Neither touches the host.</li>
     *   <li><b>Any replica holding content SM did not write gives up</b> ({@code reconcile.foreign.content}) - before any
     *       replica is acted on, so a prior sibling is not re-applied around a foreign one.</li>
     *   <li>Per replica: a {@code PRIOR} file is re-applied (and restarted unless the strategy is manual); an
     *       {@code INTENDED} file that this request has not restarted is restarted only. Nothing else is acted on.</li>
     *   <li>Nothing to act on is a confirmation - a read. Under {@code rolling-health-gated} it also needs every replica's
     *       declared probe {@code HEALTHY} in the same observation (Q-R2): a definitive "not healthy" gives up
     *       ({@code reconcile.gate.unproven}); an unreadable probe is a failed read and is retried.</li>
     *   <li>Something to act on is a re-drive, and only while it is still safe, in order of how absolute the reason is: a
     *       newer request on the scope, then the age bound, then the count (Q-R3).</li>
     * </ol>
     */
    static ReconcileDecision decide(HostObservation observation, Context context) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(context, "context");
        if (observation instanceof HostObservation.NotReconcilable n) {
            return new GiveUp(n.why());
        }
        if (observation instanceof HostObservation.Undecided u) {
            return new Retry(u.why());
        }
        HostObservation.Observed o = (HostObservation.Observed) observation;

        if (o.replicas().stream().anyMatch(r -> r.content() == HostObservation.Content.FOREIGN)) {
            return new GiveUp(new ErrorClass("reconcile.foreign.content"));
        }

        boolean restarts = o.strategy() != Strategy.MANUAL;
        List<RedrivePlan.Step> steps = new ArrayList<>();
        for (HostObservation.ReplicaFacts r : o.replicas()) {
            if (r.content() == HostObservation.Content.PRIOR) {
                steps.add(new RedrivePlan.Step(r.replica(), true, r.currentHash(), restarts));
            } else if (restarts && !r.restartedByThisRequest()) {
                steps.add(new RedrivePlan.Step(r.replica(), false, null, true));
            }
        }

        if (steps.isEmpty()) {
            if (o.strategy() != Strategy.ROLLING_HEALTH_GATED) {
                return new Confirm(false);
            }
            boolean unreadable = false;
            for (HostObservation.ReplicaFacts r : o.replicas()) {
                if (r.health() == HealthSignal.UNREADABLE) {
                    unreadable = true;
                } else if (r.health() != HealthSignal.HEALTHY) {
                    return new GiveUp(new ErrorClass("reconcile.gate.unproven"));
                }
            }
            return unreadable ? new Retry(new ErrorClass("reconcile.gate.unreadable")) : new Confirm(true);
        }

        if (context.laterRequestExists()) {
            return new GiveUp(new ErrorClass("reconcile.superseded.by.later.request"));
        }
        if (context.redriveTooLate()) {
            return new GiveUp(new ErrorClass("reconcile.redrive.too.late"));
        }
        if (context.redrivesDone() >= context.maxRedrives()) {
            return new GiveUp(new ErrorClass("reconcile.redrive.exhausted"));
        }
        return new Redrive(new RedrivePlan(o.generation(), o.strategy(), o.intendedHash(), steps));
    }
}
