package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.util.List;
import java.util.Objects;

/**
 * What a re-drive does, decided from an observation: per replica, whether to re-write the file (conditionally, only if it
 * is still what was observed) and whether to restart it. Replicas that need nothing are absent.
 *
 * @param generation   the request's own generation
 * @param strategy     the request's own strategy (a re-drive never changes it)
 * @param intendedHash the keyed hash the {@code MATERIALIZE} intents bound; a re-apply refuses to write anything else
 */
public record RedrivePlan(long generation, Strategy strategy, String intendedHash, List<Step> steps) {

    public RedrivePlan {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(intendedHash, "intended hash");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("a re-drive with nothing to do is a confirmation, not a re-drive");
        }
        steps = List.copyOf(steps);
    }

    /**
     * @param rewrite             re-run {@code MATERIALIZE} through the conditional write
     * @param expectedCurrentHash what the file must still be for the write to proceed, {@code null} = still absent;
     *                            meaningful only when {@code rewrite}
     * @param restart             run the {@code LIFECYCLE} step (gated as the strategy requires) and the post-restart verify
     */
    public record Step(TargetReplica replica, boolean rewrite, String expectedCurrentHash, boolean restart) {

        public Step {
            Objects.requireNonNull(replica, "replica");
            if (!rewrite && !restart) {
                throw new IllegalArgumentException("a step that neither writes nor restarts is not a step");
            }
        }
    }

    boolean anyRewrite() {
        return steps.stream().anyMatch(Step::rewrite);
    }
}
