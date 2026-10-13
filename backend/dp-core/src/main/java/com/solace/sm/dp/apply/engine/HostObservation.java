package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.util.List;
import java.util.Objects;

/**
 * One FRESH observation of the host for an {@code APPLY_INDETERMINATE} request (plan 010 T6.8): taken from the host now,
 * outside any database lock, and never from the journal alone. It carries facts, not a verdict - the verdict is
 * {@code ReconcileDecision}'s, a pure function of this and the request's own bookkeeping.
 *
 * <p><b>What it holds, per replica.</b> The replica's current file (as a keyed hash, or absent), what that file is relative
 * to the request ({@link Content}), and - only where it matters - whether the replica was restarted by <i>this</i> request,
 * decided exactly as {@link HealthGate} decides it: the replica's current start baseline is present and differs from the
 * {@code pre_baseline} of this request's latest {@code LIFECYCLE} attempt, host-clock values only.
 *
 * <p><b>No file content, no unkeyed hash</b> (I6, OR-32): hashes are the adapter's keyed hashes, and nothing here reaches a
 * log line or an event.
 */
public sealed interface HostObservation {

    /**
     * The host could not be read, or the answer cannot be decided now: unreachable, a read failed, or a replica that would
     * have to be judged "restarted by this request" has no readable start baseline (not running, or mid-restart - M18). A
     * failed inspection: it is retried with backoff, and the reconciler never restarts a replica it could not read.
     */
    record Undecided(ErrorClass why) implements HostObservation {

        public Undecided {
            Objects.requireNonNull(why, "why");
        }
    }

    /**
     * The request cannot be reconciled from a host observation at all (no {@code MATERIALIZE} intent was ever journaled, or
     * it is not a single-placement apply). Not retried - nothing a re-inspection could learn - and not a re-drive: the
     * operator's {@code resolveIndeterminate} is the exit.
     */
    record NotReconcilable(ErrorClass why) implements HostObservation {

        public NotReconcilable {
            Objects.requireNonNull(why, "why");
        }
    }

    /**
     * A decided observation.
     *
     * @param intendedHash the keyed hash the {@code MATERIALIZE} intents bound before acting
     */
    record Observed(long generation, Strategy strategy, String intendedHash, List<ReplicaFacts> replicas)
            implements HostObservation {

        public Observed {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(intendedHash, "intended hash");
            if (replicas == null || replicas.isEmpty()) {
                throw new IllegalArgumentException("an observation names at least one replica");
            }
            replicas = List.copyOf(replicas);
        }
    }

    /** What a replica's current file is, relative to the request. */
    enum Content {
        /** The file is the one the request bound. */
        INTENDED,
        /** The file is what the adapter retained before the write (a hash), or was absent and the absence was recorded (Q-R1). */
        PRIOR,
        /** Anything else: content SM did not write is NEVER overwritten. */
        FOREIGN
    }

    /**
     * @param currentHash the keyed hash of the file now, or {@code null} when there is none
     * @param restartedByThisRequest meaningful only for an {@link Content#INTENDED} replica under a restarting strategy;
     *        {@code false} elsewhere
     * @param health the declared-probe signal read in this same observation (Q-R2's one read-only gate observation)
     */
    record ReplicaFacts(TargetReplica replica, Content content, String currentHash, boolean restartedByThisRequest,
            HealthSignal health, RuntimeRef runtimeRef) {

        public ReplicaFacts {
            Objects.requireNonNull(replica, "replica");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(health, "health");
        }
    }
}
