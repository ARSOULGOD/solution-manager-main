package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the apply journal says about one instance, in the terms the ladder needs (plan 010 T6.5): no row, no
 * configuration value, only states, generation numbers and keyed hashes. Read by {@link LadderRows}; turned into rungs
 * by {@link ConfigLadder}.
 *
 * @param lastGeneration   {@code instance_state.last_generation}: the instance's newest generation, 0 before the first
 * @param storedRung       {@code instance_state.reached_rung}, or null if no landing has written it (a cache the
 *                         landing keeps for {@code instance.config-state}'s firing rule; the ladder itself is derived
 *                         from the journal and never reads it)
 * @param livePlacements   the instance's non-retired placements, each with the latest settled apply request that
 *                         touched it
 */
public record LadderEvidence(long lastGeneration, LadderRung storedRung, List<PlacementEvidence> livePlacements) {

    public LadderEvidence {
        livePlacements = List.copyOf(Objects.requireNonNull(livePlacements, "placements"));
    }

    /** An instance the data plane has not declared, or one with nothing recorded. */
    public static LadderEvidence none() {
        return new LadderEvidence(0, null, List.of());
    }

    /**
     * @param latest    the placement's latest SETTLED apply request (not {@code REQUESTED}, {@code RUNNING},
     *                  {@code FAILED_NO_EFFECT} or {@code SUPERSEDED}: none of those say what is on the host), or null
     * @param startedAt the start time of the placement's container as the data plane last OBSERVED it (OR-112 (1)):
     *                  the host's own clock, the latest observation and not a history, or null if none was observed.
     *                  Evidence for the ungated route to {@code restart-observed} only, compared with the write
     *                  baseline of the request's {@code MATERIALIZE} steps and with nothing on the data plane's clock
     */
    public record PlacementEvidence(UUID placementId, RequestEvidence latest, Instant startedAt) {
        public PlacementEvidence {
            Objects.requireNonNull(placementId, "placement");
        }

        /** A placement whose container start the data plane has not observed. */
        public PlacementEvidence(UUID placementId, RequestEvidence latest) {
            this(placementId, latest, null);
        }
    }

    /**
     * @param resolution         the operator's confirmation, for an {@code INDETERMINATE_RESOLVED} request, else null
     * @param resolvedReplicas   the replica observations that confirmation rested on, else empty
     * @param steps              every intent of the request with its latest attempt
     */
    public record RequestEvidence(long generation, RequestState state, Strategy strategy, ResolutionOutcome resolution,
            List<ReplicaObservation> resolvedReplicas, List<StepEvidence> steps) {
        public RequestEvidence {
            Objects.requireNonNull(state, "state");
            resolvedReplicas = List.copyOf(Objects.requireNonNull(resolvedReplicas, "resolved replicas"));
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        }
    }

    /**
     * One intent and the latest try of it.
     *
     * @param intendedHash   the hash the intent bound before acting (null on a hashless step)
     * @param latestOutcome  the latest attempt's outcome, null if no attempt was opened or it is still open
     * @param observedHash   that attempt's read-back hash: evidence, never intent
     * @param writeBaseline  for a {@code MATERIALIZE} step whose latest attempt SUCCEEDED: the container start the HOST
     *                       reported when the file was read back (OR-112 (1)), on the host's own clock; null for any other
     *                       step, and for a write that left no baseline (a failed or cut-off attempt, a container that had
     *                       never started). What a later observed start is compared with, so that no data plane timestamp
     *                       is ever compared with a host one
     */
    public record StepEvidence(Step step, ReplicaRef replica, String intendedHash, AttemptOutcome latestOutcome,
            String observedHash, Instant writeBaseline) {
        public StepEvidence {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(replica, "replica");
        }

        /** A step whose latest attempt left no write baseline (none opened, still open, or not a succeeded write). */
        public StepEvidence(Step step, ReplicaRef replica, String intendedHash, AttemptOutcome latestOutcome,
                String observedHash) {
            this(step, replica, intendedHash, latestOutcome, observedHash, null);
        }
    }
}
