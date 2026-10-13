package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.ConfigLadderView.Rung;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.StartTolerance;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.config.LadderEvidence.PlacementEvidence;
import com.solace.sm.dp.config.LadderEvidence.RequestEvidence;
import com.solace.sm.dp.config.LadderEvidence.StepEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules that turn the apply journal into the five rungs of OR-37 (plan 010 T6.5). Pure: no I/O, no clock, so
 * every rule is a unit test over {@link LadderEvidence}.
 *
 * <p><b>What each rung is, and what it is not.</b>
 * <ul>
 *   <li>{@code desired} and {@code rendered} name the instance's newest generation and carry its content digest
 *       (keyed, OR-32). The digest is of the canonical content the stage stored, not of the file bytes: those need the
 *       secrets resolved, and a read must not resolve a secret. So {@code rendered} stays what S5 documented.</li>
 *   <li>{@code persisted} is reached when, for the newest generation, <b>every</b> live placement's latest settled
 *       apply request ended {@code STAGED}, {@code APPLIED} or {@code INDETERMINATE_RESOLVED}-applied <b>and</b> each
 *       of its {@code MATERIALIZE} intents has a latest attempt that succeeded with a read-back hash equal to the hash
 *       the intent bound before it acted. An intent alone is never evidence. Its hash is the keyed hash of the file
 *       bytes the host holds, so this rung and the next carry a different digest from the first two.</li>
 *   <li>{@code restart-observed} is reached by one of three routes, each needing the generation to be {@code persisted}
 *       first (OR-112 (1)):
 *       <ol type="a">
 *         <li><b>gated, journal.</b> {@code APPLIED} under {@code rolling-health-gated} and, for each replica, a succeeded
 *             {@code LIFECYCLE} attempt (the gate passed on a moved baseline) and a succeeded {@code VERIFY} attempt whose
 *             post-restart read-back equals the bound hash (T6.9, OR-53).</li>
 *         <li><b>gated, confirmed by verify-then-retry.</b> {@code INDETERMINATE_RESOLVED} with outcome {@code applied}
 *             under {@code rolling-health-gated}, where for each replica the reconciler's own evidence says it was
 *             restarted onto the bound hash <i>and</i> the journal holds the succeeded {@code LIFECYCLE} attempt that
 *             {@code recordGateProof} writes only when the declared probe said {@code HEALTHY} in that observation. The
 *             evidence alone is not enough, nor is the gate proof alone. (This reads what the reconciler already
 *             records; it adds no journal row, so the write path is untouched.)</li>
 *         <li><b>ungated, a container start observed.</b> Every other strategy ({@code manual}, {@code rolling-no-validation},
 *             {@code restart-all}, {@code scheduled}) runs no health gate and reads nothing back after the restart, so the
 *             journal can never say it happened. The data plane's poller reads the container's start time
 *             ({@code docker inspect StartedAt}, plan 010 T6.9 M18: it moves on restart and on stop-then-start) and records
 *             it per placement; the rung is reached when that start is later than the start the host reported when the
 *             file was written, for every {@code MATERIALIZE} write of the request ({@link StepEvidence#writeBaseline}).
 *             The gated strategy never takes this route: its proof is the gate.</li>
 *       </ol>
 *       <b>One clock, stated.</b> Both values compared are the Docker host's own: the start the poller reads, and the start
 *       the host reported when the engine read the written file back, which the write's journal row keeps. No data plane
 *       timestamp is compared with a host one, so a host clock that runs ahead of or behind the data plane's by any amount
 *       cannot promote a generation nobody restarted, nor hide a restart that happened ("a start later than the apply's
 *       write", OR-112, decided on the host's own timeline). The two values must differ by more than
 *       {@link StartTolerance#VALUE}, which absorbs the rounding between the nanoseconds Docker reports and the microseconds
 *       the database stores; a restart never completes inside it. A write that left no baseline (a failed or cut-off
 *       attempt, a container that had never started, a request resolved after a time-out) claims nothing and is left at
 *       {@code persisted} on purpose; {@link #awaitsStart} stops asking about it, since no start could ever satisfy it.</li>
 *   <li>{@code effective-verified} is declared absent for every package: {@code SM-D22} makes it a per-package
 *       property and the package schema defines no verification-signal declaration, so no package can declare one.
 *       Never inferred, never branched on the solution.</li>
 * </ul>
 *
 * <p>Evidence about an <i>older</i> generation is not the newest generation's evidence: a later stage drops the ladder
 * back to {@code rendered} until that generation has been applied. The instance rung is the lowest across live
 * placements (OR-37); with no live placement there is nothing to persist to, so it stays {@code rendered}.
 */
public final class ConfigLadder {

    /** Why the top rung is absent. Fixed text: it reaches a response body. */
    public static final String EFFECTIVE_UNVERIFIED = "effective-unverified for this package";

    private ConfigLadder() {
    }

    /** The ladder as {@code getConfigLadder} reports it. */
    public static ConfigLadderView view(Optional<ConfigDraft> latest, LadderEvidence evidence) {
        List<Rung> rungs = new ArrayList<>();
        if (latest.isEmpty()) {
            for (LadderRung rung : LadderRung.values()) {
                rungs.add(new Rung(rung, false, null, null));
            }
            return new ConfigLadderView(rungs, LadderRung.DESIRED, EFFECTIVE_UNVERIFIED);
        }
        ConfigDraft draft = latest.get();
        long generation = draft.generation();
        Reach reach = reach(evidence, generation);
        for (LadderRung rung : LadderRung.values()) {
            switch (rung) {
                case DESIRED, RENDERED -> rungs.add(new Rung(rung, true, generation, draft.contentDigest()));
                case PERSISTED, RESTART_OBSERVED -> rungs.add(reach.rung().ordinal() >= rung.ordinal()
                        ? new Rung(rung, true, generation, reach.fileHash())
                        : new Rung(rung, false, null, null));
                default -> rungs.add(new Rung(rung, false, null, null));
            }
        }
        return new ConfigLadderView(rungs, reach.rung(), EFFECTIVE_UNVERIFIED);
    }

    /** The instance rung for its newest generation: what {@code instance_state.reached_rung} caches. */
    public static LadderRung instanceRung(LadderEvidence evidence) {
        if (evidence.lastGeneration() == 0) {
            return LadderRung.DESIRED;
        }
        return reach(evidence, evidence.lastGeneration()).rung();
    }

    /**
     * Whether the poller should still look for a container start on this placement: its latest request is of the
     * generation in question, that generation is {@code persisted} but not yet {@code restart-observed}, and the strategy
     * is one the start route serves (not the gated one, whose proof is its gate). Once a start is observed after the
     * write the answer is false, so a placement is inspected no longer than it needs to be.
     */
    public static boolean awaitsStart(PlacementEvidence placement, long generation) {
        RequestEvidence request = placement.latest();
        if (request == null || request.strategy() == Strategy.ROLLING_HEALTH_GATED) {
            return false;
        }
        if (request.steps().stream().anyMatch(s -> s.step() == Step.MATERIALIZE && s.writeBaseline() == null)) {
            return false;   // nothing a start could be compared with: no start can ever satisfy it, so do not keep asking
        }
        return placementReach(request, generation, placement.startedAt()).rung() == LadderRung.PERSISTED;
    }

    /** What a never-written {@code reached_rung} means: the stage's own rung, before any apply moved it. */
    public static LadderRung unappliedRung(LadderEvidence evidence) {
        return evidence.lastGeneration() == 0 ? LadderRung.DESIRED : LadderRung.RENDERED;
    }

    private record Reach(LadderRung rung, String fileHash) {
    }

    private static Reach reach(LadderEvidence evidence, long generation) {
        Reach rendered = new Reach(LadderRung.RENDERED, null);
        if (evidence.livePlacements().isEmpty()) {
            return rendered;
        }
        LadderRung lowest = LadderRung.RESTART_OBSERVED;
        Set<String> hashes = new LinkedHashSet<>();
        for (PlacementEvidence placement : evidence.livePlacements()) {
            Reach one = placementReach(placement.latest(), generation, placement.startedAt());
            if (one.rung().ordinal() < lowest.ordinal()) {
                lowest = one.rung();
            }
            if (one.fileHash() != null) {
                hashes.add(one.fileHash());
            }
        }
        // One rendered set per generation, so every placement holds the same bytes. Differing hashes would mean the
        // placements disagree about what is on disk: claim nothing past rendered rather than pick one.
        if (lowest.ordinal() < LadderRung.PERSISTED.ordinal() || hashes.size() != 1) {
            return rendered;
        }
        return new Reach(lowest, hashes.iterator().next());
    }

    private static Reach placementReach(RequestEvidence request, long generation, Instant startedAt) {
        Reach rendered = new Reach(LadderRung.RENDERED, null);
        if (request == null || request.generation() != generation) {
            return rendered;
        }
        List<StepEvidence> materializes = request.steps().stream().filter(s -> s.step() == Step.MATERIALIZE).toList();
        if (materializes.isEmpty()) {
            return rendered;
        }
        String hash = materializes.get(0).intendedHash();
        if (hash == null || materializes.stream().anyMatch(s -> !hash.equals(s.intendedHash()))) {
            return rendered;
        }
        if (!persisted(request, materializes, hash)) {
            return rendered;
        }
        if (restartObserved(request, materializes, hash, startedAt)) {
            return new Reach(LadderRung.RESTART_OBSERVED, hash);
        }
        return new Reach(LadderRung.PERSISTED, hash);
    }

    private static boolean persisted(RequestEvidence request, List<StepEvidence> materializes, String hash) {
        return switch (request.state()) {
            case STAGED, APPLIED -> materializes.stream().allMatch(s -> readBack(s, hash));
            case INDETERMINATE_RESOLVED -> request.resolution() == ResolutionOutcome.APPLIED
                    && materializes.stream().allMatch(s -> confirmed(request.resolvedReplicas(), s.replica(), hash));
            default -> false;
        };
    }

    private static boolean restartObserved(RequestEvidence request, List<StepEvidence> materializes, String hash,
            Instant startedAt) {
        if (request.strategy() != Strategy.ROLLING_HEALTH_GATED) {
            return startedAfterTheWrite(materializes, startedAt);
        }
        return switch (request.state()) {
            case APPLIED -> gatedAndVerified(request, materializes, hash);
            case INDETERMINATE_RESOLVED -> request.resolution() == ResolutionOutcome.APPLIED
                    && gateProvedAndConfirmed(request, materializes, hash);
            default -> false;
        };
    }

    /** Route (a): the gate passed and the post-restart read-back matched, per replica, in the journal. */
    private static boolean gatedAndVerified(RequestEvidence request, List<StepEvidence> materializes, String hash) {
        for (StepEvidence materialize : materializes) {
            boolean verified = request.steps().stream().anyMatch(s -> s.step() == Step.VERIFY
                    && s.replica().equals(materialize.replica()) && hash.equals(s.intendedHash())
                    && readBack(s, hash));
            if (!gatePassed(request, materialize.replica()) || !verified) {
                return false;
            }
        }
        return true;
    }

    /**
     * Route (b): verify-then-retry confirmed a gated apply the engine was cut off from. The reconciler's observation
     * (restarted by this request, the bound hash on disk) and the gate proof it journals must BOTH hold for every replica.
     */
    private static boolean gateProvedAndConfirmed(RequestEvidence request, List<StepEvidence> materializes, String hash) {
        for (StepEvidence materialize : materializes) {
            boolean restarted = request.resolvedReplicas().stream().anyMatch(o -> o.ref().equals(materialize.replica())
                    && o.restarted() && hash.equals(o.observedHash()));
            if (!gatePassed(request, materialize.replica()) || !restarted) {
                return false;
            }
        }
        return true;
    }

    private static boolean gatePassed(RequestEvidence request, ReplicaRef replica) {
        return request.steps().stream().anyMatch(s -> s.step() == Step.LIFECYCLE && s.replica().equals(replica)
                && s.latestOutcome() == AttemptOutcome.SUCCEEDED);
    }

    /**
     * Route (c): a container start later than the start the host reported at every write of the request, both on the
     * host's clock. A write with no baseline gives nothing to compare against, so it claims nothing.
     */
    private static boolean startedAfterTheWrite(List<StepEvidence> materializes, Instant startedAt) {
        if (startedAt == null) {
            return false;
        }
        for (StepEvidence materialize : materializes) {
            Instant baseline = materialize.writeBaseline();
            if (baseline == null || !startedAt.isAfter(baseline.plus(StartTolerance.VALUE))) {
                return false;
            }
        }
        return true;
    }

    private static boolean readBack(StepEvidence step, String hash) {
        return step.latestOutcome() == AttemptOutcome.SUCCEEDED && hash.equals(step.observedHash());
    }

    private static boolean confirmed(List<ReplicaObservation> observed, ReplicaRef replica, String hash) {
        return observed.stream().anyMatch(o -> o.ref().equals(replica) && hash.equals(o.observedHash()));
    }
}
