package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Design §2.2 rule 3, as a <b>pure function</b>.
 *
 * <p>A decision table that can only be exercised through a database and a daemon is one nobody will
 * exercise exhaustively. {@link #decide} takes rows and observations and returns a verdict;
 * {@code ResumePlannerTest} walks every cell, and {@code ResumeIT} then proves the wiring on the
 * handful that cannot be faked.
 *
 * <p><b>The table is total.</b> Every {@code (Step × attempt-presence × observation-class)} the
 * enums admit returns a decision. There is no permissive {@code default}: a {@code switch} with one
 * is not a decision table, it is a decision table plus a guess.
 */
public final class ResumePlanner {

    /**
     * @param intent       the durable step. For {@code ROLLBACK} its {@code config_hash} is the
     *                     restore target, written <b>before</b> the destructive call — which is the
     *                     single fact that makes the three {@code ROLLBACK} rows decidable rather
     *                     than a guess
     * @param open         the attempt that was still open when the DP died, if there was one. Its
     *                     {@code pre_baseline} is what a {@code LIFECYCLE} restart is observed
     *                     against
     * @param observed     the replicas this step addresses: one entry for a replica-scoped step,
     *                     the whole placement for {@code ROLLBACK}. <b>A list rather than the single
     *                     {@code RuntimeStatus} the plan sketched</b> — {@code ROLLBACK} is
     *                     placement-scoped and its rows speak of "every replica", which one status
     *                     cannot express. Empty is a legal input and means "nothing could be
     *                     observed": for the three read-only steps it changes nothing, and for the
     *                     rest it is {@link StepDecision#INDETERMINATE}
     * @param intendedHash the {@code config_hash} the {@code MATERIALIZE} intents bound. The second
     *                     of the two journal-known values a {@code ROLLBACK} may see, and a
     *                     parameter because it is a row of this apply, not an inference
     */
    public StepDecision decide(StepIntentRow intent, Optional<AttemptRow> open,
            List<RuntimeStatus> observed, String intendedHash) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(observed, "observed");

        if (open.isEmpty()) {
            // §2.2 rule 3: "an intent with no attempt row provably issued nothing: re-run
            // deterministically -- never indeterminate." Rule 0 is what makes this provable.
            return StepDecision.RERUN;
        }
        switch (intent.step()) {
            case PREFLIGHT:
                // prepare() never mutates, so re-running it is free and always safe -- and the
                // answer does not depend on an observation, which matters: a crash inside the very
                // first step leaves no MATERIALIZE intent to name a replica with, so there is
                // nothing to observe yet. An empty-observation guard placed ahead of this branch
                // would turn the cheapest cell in the table into APPLY_INDETERMINATE.
                return StepDecision.RERUN;
            case ROLLBACK_ASSESS:
                // BE-D22 made the assessment read-only, which is the whole reason it may be
                // journalled hashless and re-run after a crash -- the same reason as PREFLIGHT.
                return StepDecision.RERUN;
            case VERIFY:
                return StepDecision.RERUN;
            case MATERIALIZE:
                return observed.isEmpty()
                        ? StepDecision.INDETERMINATE : materialize(intent, observed.get(0));
            case LIFECYCLE:
                return observed.isEmpty()
                        ? StepDecision.INDETERMINATE : lifecycle(open.get(), observed.get(0));
            case STOP:
                return observed.isEmpty()
                        ? StepDecision.INDETERMINATE : stop(observed.get(0));
            case ROLLBACK:
                return decideRollback(intent, observed, intendedHash);
            default:
                throw new IllegalStateException("unreachable: every Step is named above, and this "
                        + "default exists only because Java 17's statement switch has no "
                        + "exhaustiveness check over enums. A new Step must be added above, not "
                        + "here: " + intent.step());
        }
    }

    private StepDecision materialize(StepIntentRow intent, RuntimeStatus observed) {
        String seen = observed.observedHash();
        if (seen == null) {
            return StepDecision.RERUN;                       // nothing at the path
        }
        if (intent.configHash() == null) {
            // hash_on_config_steps makes this unreachable from the database. Answered anyway,
            // because a decision table with a hole is not total.
            return StepDecision.INDETERMINATE;
        }
        return seen.equals(intent.configHash())
                ? StepDecision.CONTINUE
                // "bytes present with an unexpected hash -- a competing writer or partial write --
                // never silently overwritten" (B/D12).
                : StepDecision.INDETERMINATE;
    }

    private StepDecision lifecycle(AttemptRow open, RuntimeStatus observed) {
        Optional<PreBaseline> now = observed.startBaseline();
        if (now.isEmpty() || open.preBaseline() == null) {
            // "baseline missing or unreadable -> APPLY_INDETERMINATE, because re-issuing a restart
            // the operator may not have authorized twice is exactly the guess this state exists to
            // refuse."
            //
            // The missing-pre_baseline half IS reachable, and the executor has two ways to get
            // there: it records obligation (r)'s halt as a baseline-less LIFECYCLE attempt, and it
            // opens one with a null baseline for a replica that was already down when the apply
            // began. A crash inside either window leaves this cell. INDETERMINATE is still right:
            // resume cannot tell a halt it had already decided from a restart that never reported,
            // and the difference is exactly the guess rule 3 refuses.
            return StepDecision.INDETERMINATE;
        }
        return now.get().startedAt().equals(open.preBaseline().startedAt())
                ? StepDecision.REISSUE                        // baseline unchanged: idempotent
                : StepDecision.CONTINUE;                      // restart observed
    }

    private StepDecision stop(RuntimeStatus observed) {
        // ABSENT is the third class the enum admits and the table did not name. It is CONTINUE: a
        // stop's desired state is "not running", and a replica that is gone is not running.
        // Re-issuing a stop against something that no longer exists would fail for a reason that is
        // not the operator's.
        return observed.liveness() == Liveness.RUNNING ? StepDecision.REISSUE : StepDecision.CONTINUE;
    }

    /**
     * The {@code ROLLBACK} rows, on their own, <b>without</b> the attempt-presence short-circuit.
     *
     * <p>Public because resume calls it directly for the whole of {@code BE-D22}'s tail, and must:
     * the short-circuit's rule — "an intent with no attempt row provably issued nothing, so re-run"
     * — answers a question the tail has already moved past. By the time a {@code ROLLBACK} intent
     * exists, its {@code config_hash} names the target and the bytes on the replicas say whether
     * the restore happened; attempt presence adds nothing and, routed through {@link #decide},
     * would return {@code RERUN} for a crash between the intent and its attempt — a step the tail
     * has no "from the top" to return to. The answers agree anyway: nothing issued means the
     * replicas still hold the pre-restore mixture, which is {@link StepDecision#REISSUE}, and
     * restoring identical bytes twice is the same write (§2.2 rule 2).
     */
    public StepDecision decideRollback(StepIntentRow intent, List<RuntimeStatus> observed,
            String intendedHash) {
        if (observed.isEmpty()) {
            return StepDecision.INDETERMINATE;
        }
        String target = intent.configHash();
        if (target == null) {
            return StepDecision.INDETERMINATE;               // unreachable: hash_on_config_steps
        }
        boolean allAtTarget = true;
        for (RuntimeStatus status : observed) {
            String seen = status.observedHash();
            if (seen == null || !(seen.equals(target) || seen.equals(intendedHash))) {
                // A THIRD value, or unreadable: somebody else wrote while the DP was down. B/D12 --
                // never silently auto-revert a human. Report and stop; do not overwrite.
                return StepDecision.INDETERMINATE;
            }
            allAtTarget &= seen.equals(target);
        }
        // Both remaining values are ones THIS apply wrote, so re-issuing is safe: restoring
        // identical bytes twice is the same write (§2.2 rule 2).
        return allAtTarget ? StepDecision.CONTINUE : StepDecision.REISSUE;
    }
}
