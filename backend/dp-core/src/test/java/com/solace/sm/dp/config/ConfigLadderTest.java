package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The ladder's rules over journal evidence (plan 010 T6.5; OR-37), with no database: every rule is a statement about
 * which facts make a rung present. The same rules run end to end against the real journal in {@code ConfigLadderIT}.
 */
class ConfigLadderTest {

    private static final String DIGEST = "hmacsha256v1:" + "d".repeat(64);
    private static final String FILE = "hmacsha256v1:" + "f".repeat(64);
    private static final String OTHER = "hmacsha256v1:" + "0".repeat(64);
    private static final long G = 3;
    private static final ReplicaRef R1 = new ReplicaRef("replica-1");
    private static final ReplicaRef R2 = new ReplicaRef("replica-2");

    private static Optional<ConfigDraft> draft(long generation) {
        return Optional.of(new ConfigDraft(generation, "cache", "2.4", "{\"instances\":[]}", "{}", DIGEST));
    }

    private static StepEvidence step(Step step, ReplicaRef replica, String intended, AttemptOutcome outcome,
            String observed) {
        return new StepEvidence(step, replica, intended, outcome, observed);
    }

    private static StepEvidence materialized(ReplicaRef replica) {
        return step(Step.MATERIALIZE, replica, FILE, AttemptOutcome.SUCCEEDED, FILE);
    }

    private static List<StepEvidence> restarted(ReplicaRef replica) {
        return List.of(step(Step.LIFECYCLE, replica, null, AttemptOutcome.SUCCEEDED, FILE),
                step(Step.VERIFY, replica, FILE, AttemptOutcome.SUCCEEDED, FILE));
    }

    private static RequestEvidence request(long generation, RequestState state, Strategy strategy,
            StepEvidence... steps) {
        return new RequestEvidence(generation, state, strategy, null, List.of(), List.of(steps));
    }

    private static RequestEvidence request(long generation, RequestState state, Strategy strategy,
            List<StepEvidence> steps) {
        return new RequestEvidence(generation, state, strategy, null, List.of(), steps);
    }

    private static LadderEvidence evidence(RequestEvidence... perPlacement) {
        List<PlacementEvidence> placements = new ArrayList<>();
        for (RequestEvidence r : perPlacement) {
            placements.add(new PlacementEvidence(UUID.randomUUID(), r));
        }
        return new LadderEvidence(G, null, placements);
    }

    private static RequestEvidence gatedApplied() {
        List<StepEvidence> steps = new ArrayList<>(List.of(materialized(R1), materialized(R2)));
        steps.addAll(restarted(R1));
        steps.addAll(restarted(R2));
        return request(G, RequestState.APPLIED, Strategy.ROLLING_HEALTH_GATED, steps);
    }

    private static RequestEvidence manualStaged() {
        return request(G, RequestState.STAGED, Strategy.MANUAL, materialized(R1), materialized(R2));
    }

    private static Rung rung(ConfigLadderView view, LadderRung rung) {
        return view.rungs().get(rung.ordinal());
    }

    // ---- the plan's five -----------------------------------------------------------------------------------------

    @Test
    void aManualApplyReachesPersistedAndStopsThere() {
        ConfigLadderView view = ConfigLadder.view(draft(G), evidence(manualStaged()));

        assertEquals(LadderRung.PERSISTED, view.reached());
        assertTrue(rung(view, LadderRung.PERSISTED).present());
        assertEquals(G, rung(view, LadderRung.PERSISTED).generation());
        assertEquals(FILE, rung(view, LadderRung.PERSISTED).configHash(),
                "persisted carries the keyed hash of the file bytes the read-back saw");
        assertFalse(rung(view, LadderRung.RESTART_OBSERVED).present(), "the operator restarts; SM has not observed it");
        assertFalse(rung(view, LadderRung.EFFECTIVE_VERIFIED).present());
    }

    @Test
    void theInstanceRungIsTheLowestAcrossLivePlacements() {
        assertEquals(LadderRung.PERSISTED,
                ConfigLadder.view(draft(G), evidence(gatedApplied(), manualStaged())).reached(),
                "one placement observed its restart, the other only persisted: the lowest is reported");
        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(gatedApplied(), null)).reached(),
                "a live placement nothing has been applied to holds the instance at rendered");
        assertEquals(LadderRung.RESTART_OBSERVED,
                ConfigLadder.view(draft(G), evidence(gatedApplied(), gatedApplied())).reached());
        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence()).reached(),
                "no live placement: there is nothing to have persisted to");
    }

    @Test
    void effectiveVerifiedIsDeclaredAbsentWithItsReason() {
        ConfigLadderView view = ConfigLadder.view(draft(G), evidence(gatedApplied()));

        Rung top = rung(view, LadderRung.EFFECTIVE_VERIFIED);
        assertFalse(top.present());
        assertNull(top.generation());
        assertNull(top.configHash());
        assertEquals("effective-unverified for this package", view.degradedReason());
        assertEquals(LadderRung.RESTART_OBSERVED, view.reached(), "never inferred past what a probe proved");
    }

    @Test
    void everyRungCarriesAKeyedHash() {
        Pattern keyed = Pattern.compile("^hmacsha256v[0-9]+:[0-9a-f]{64}$");
        ConfigLadderView view = ConfigLadder.view(draft(G), evidence(gatedApplied()));

        long present = view.rungs().stream().filter(Rung::present).count();
        assertEquals(4, present);
        for (Rung r : view.rungs()) {
            if (r.present()) {
                assertTrue(keyed.matcher(r.configHash()).matches(), r.rung().wire() + " -> " + r.configHash());
            } else {
                assertNull(r.configHash(), r.rung().wire());
            }
        }
        assertEquals(DIGEST, rung(view, LadderRung.RENDERED).configHash(),
                "rendered is the stored content's digest (S5, documented); the file-bytes hash starts at persisted");
        assertNotEquals(rung(view, LadderRung.RENDERED).configHash(), rung(view, LadderRung.PERSISTED).configHash());
    }

    @Test
    void theLadderNoLongerReportsTheS5DegradedNotice() {
        for (Optional<ConfigDraft> latest : List.of(Optional.<ConfigDraft>empty(), draft(G))) {
            String reason = ConfigLadder.view(latest, evidence(manualStaged())).degradedReason();
            assertEquals(ConfigLadder.EFFECTIVE_UNVERIFIED, reason);
            assertFalse(reason.contains("apply path is wired"), reason);
        }
    }

    // ---- persisted is the read-back, never the intent ------------------------------------------------------------

    @Test
    void persistedNeedsTheReadBackToMatchTheBoundHash() {
        // an intent with no attempt at all: the engine bound a hash and never acted
        RequestEvidence intentOnly = request(G, RequestState.STAGED, Strategy.MANUAL,
                step(Step.MATERIALIZE, R1, FILE, null, null));
        // a try that failed, even though it left the right bytes behind
        RequestEvidence failedTry = request(G, RequestState.STAGED, Strategy.MANUAL,
                step(Step.MATERIALIZE, R1, FILE, AttemptOutcome.FAILED, FILE));
        // a try that succeeded but read back something else
        RequestEvidence wrongBytes = request(G, RequestState.STAGED, Strategy.MANUAL,
                step(Step.MATERIALIZE, R1, FILE, AttemptOutcome.SUCCEEDED, OTHER));
        // a try that read nothing back
        RequestEvidence unread = request(G, RequestState.STAGED, Strategy.MANUAL,
                step(Step.MATERIALIZE, R1, FILE, AttemptOutcome.SUCCEEDED, null));
        // one replica is persisted, the other is not
        RequestEvidence oneOfTwo = request(G, RequestState.STAGED, Strategy.MANUAL, materialized(R1),
                step(Step.MATERIALIZE, R2, FILE, AttemptOutcome.SUCCEEDED, OTHER));
        // no intent at all
        RequestEvidence noIntent = request(G, RequestState.STAGED, Strategy.MANUAL);

        for (RequestEvidence r : List.of(intentOnly, failedTry, wrongBytes, unread, oneOfTwo, noIntent)) {
            assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(r)).reached(), r.toString());
        }
    }

    @Test
    void onlyTheStatesThatLeaveTheNewGenerationOnTheHostCountAsPersisted() {
        for (RequestState state : List.of(RequestState.FAILED_ROLLED_BACK, RequestState.CONFIG_DIVERGED,
                RequestState.APPLY_INDETERMINATE, RequestState.FAILED_NO_EFFECT, RequestState.RUNNING)) {
            RequestEvidence r = request(G, state, Strategy.ROLLING_HEALTH_GATED, materialized(R1));
            assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(r)).reached(), state.name());
        }
    }

    @Test
    void anOlderGenerationIsNotTheNewestGenerationsEvidence() {
        ConfigLadderView view = ConfigLadder.view(draft(G + 1), evidence(gatedApplied()));

        assertEquals(LadderRung.RENDERED, view.reached(), "a later stage drops the ladder back until it is applied");
        assertFalse(rung(view, LadderRung.PERSISTED).present());
        assertEquals(G + 1, rung(view, LadderRung.DESIRED).generation());
    }

    @Test
    void placementsThatHoldDifferentBytesClaimNothingPastRendered() {
        RequestEvidence other = request(G, RequestState.STAGED, Strategy.MANUAL,
                step(Step.MATERIALIZE, R1, OTHER, AttemptOutcome.SUCCEEDED, OTHER));

        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(manualStaged(), other)).reached());
    }

    @Test
    void aResolvedApplyCountsOnlyWhenTheOperatorsOwnObservationMatches() {
        List<StepEvidence> mats = List.of(step(Step.MATERIALIZE, R1, FILE, AttemptOutcome.FAILED, null));
        ReplicaObservation good = new ReplicaObservation(R1, true, FILE);
        ReplicaObservation bad = new ReplicaObservation(R1, true, OTHER);

        RequestEvidence applied = new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED, Strategy.ROLLING_HEALTH_GATED,
                ResolutionOutcome.APPLIED, List.of(good), mats);
        RequestEvidence wrongHash = new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED,
                Strategy.ROLLING_HEALTH_GATED, ResolutionOutcome.APPLIED, List.of(bad), mats);
        RequestEvidence notApplied = new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED,
                Strategy.ROLLING_HEALTH_GATED, ResolutionOutcome.NOT_APPLIED, List.of(good), mats);

        assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidence(applied)).reached());
        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(wrongHash)).reached());
        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(notApplied)).reached());
    }

    // ---- restart-observed ----------------------------------------------------------------------------------------

    @Test
    void restartObservedNeedsAGatedApplyWhoseGateAndPostRestartReadBackBothPassedOnEveryReplica() {
        assertEquals(LadderRung.RESTART_OBSERVED, ConfigLadder.view(draft(G), evidence(gatedApplied())).reached());

        // the same journal under an ungated strategy: the verb was issued, nothing observed the restart
        for (Strategy ungated : List.of(Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL)) {
            List<StepEvidence> steps = new ArrayList<>(List.of(materialized(R1)));
            steps.addAll(restarted(R1));
            assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G),
                    evidence(request(G, RequestState.APPLIED, ungated, steps))).reached(), ungated.sql());
        }

        // a gated apply with no VERIFY, or a VERIFY that read back other bytes, or a replica never restarted
        List<StepEvidence> noVerify = List.of(materialized(R1),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE));
        List<StepEvidence> wrongVerify = List.of(materialized(R1),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE),
                step(Step.VERIFY, R1, FILE, AttemptOutcome.SUCCEEDED, OTHER));
        List<StepEvidence> failedVerify = List.of(materialized(R1),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE),
                step(Step.VERIFY, R1, FILE, AttemptOutcome.FAILED, FILE));
        List<StepEvidence> secondNeverRestarted = new ArrayList<>(List.of(materialized(R1), materialized(R2)));
        secondNeverRestarted.addAll(restarted(R1));
        for (List<StepEvidence> steps : List.of(noVerify, wrongVerify, failedVerify, secondNeverRestarted)) {
            assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G),
                    evidence(request(G, RequestState.APPLIED, Strategy.ROLLING_HEALTH_GATED, steps))).reached(),
                    steps.toString());
        }
    }

    @Test
    void aGatedApplyThatDivergedIsNotRestartObservedEvenThoughItsGateRan() {
        List<StepEvidence> steps = new ArrayList<>(List.of(materialized(R1)));
        steps.addAll(restarted(R1));
        RequestEvidence diverged = request(G, RequestState.CONFIG_DIVERGED, Strategy.ROLLING_HEALTH_GATED, steps);

        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(diverged)).reached());
    }

    // ---- restart-observed by a start the data plane observed (OR-112 (1)(a), plan 010 T6.5's second route) -----------

    /** The start the HOST reported when the file was written (its own clock); any later start is a restart. */
    private static final Instant WRITE = Instant.parse("2026-10-07T10:00:00Z");

    private static StepEvidence writtenAt(ReplicaRef replica, Instant writeBaseline) {
        return new StepEvidence(Step.MATERIALIZE, replica, FILE, AttemptOutcome.SUCCEEDED, FILE, writeBaseline);
    }

    private static LadderEvidence evidenceWithStart(Instant startedAt, RequestEvidence... perPlacement) {
        List<PlacementEvidence> placements = new ArrayList<>();
        for (RequestEvidence r : perPlacement) {
            placements.add(new PlacementEvidence(UUID.randomUUID(), r, startedAt));
        }
        return new LadderEvidence(G, null, placements);
    }

    private static RequestEvidence ungated(Strategy strategy, RequestState state, StepEvidence... steps) {
        return request(G, state, strategy, steps);
    }

    @Test
    void anUngatedApplyReachesRestartObservedWhenAStartIsObservedAfterItsWrite() {
        for (Strategy strategy : List.of(Strategy.MANUAL, Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL,
                Strategy.SCHEDULED)) {
            RequestState state = strategy == Strategy.MANUAL ? RequestState.STAGED : RequestState.APPLIED;
            RequestEvidence request = ungated(strategy, state, writtenAt(R1, WRITE), writtenAt(R2, WRITE));

            ConfigLadderView view = ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(1), request));

            assertEquals(LadderRung.RESTART_OBSERVED, view.reached(), strategy.sql());
            assertTrue(rung(view, LadderRung.RESTART_OBSERVED).present());
            assertEquals(FILE, rung(view, LadderRung.RESTART_OBSERVED).configHash(), "the file hash the read-back saw");
        }
    }

    @Test
    void aStartObservedBeforeOrAtTheWriteDoesNotCount() {
        RequestEvidence request = ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, WRITE));

        for (Instant start : List.of(WRITE.minusSeconds(1), WRITE, WRITE.minusSeconds(3600), Instant.parse("0001-01-01T00:00:00Z"))) {
            assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidenceWithStart(start, request)).reached(),
                    "a start at " + start + " is the file's PREDECESSOR running, not the new file loaded");
        }
        assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidenceWithStart(null, request)).reached(),
                "no start observed at all");
    }

    @Test
    void theStartMustFollowEveryWriteOfTheRequestAndAWriteWithNoBaselineIsNeverFollowed() {
        RequestEvidence slowSecond = ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, WRITE),
                writtenAt(R2, WRITE.plusSeconds(10)));
        RequestEvidence noEnd = ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, null));

        assertEquals(LadderRung.PERSISTED,
                ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(5), slowSecond)).reached());
        assertEquals(LadderRung.RESTART_OBSERVED,
                ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(11), slowSecond)).reached());
        assertEquals(LadderRung.PERSISTED,
                ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(11), noEnd)).reached(),
                "a write that left no baseline gives no start to compare against: nothing is claimed");
    }

    @Test
    void aStartNeverRescuesARequestThatIsNotPersisted() {
        RequestEvidence wrongBytes = ungated(Strategy.MANUAL, RequestState.STAGED,
                new StepEvidence(Step.MATERIALIZE, R1, FILE, AttemptOutcome.SUCCEEDED, OTHER, WRITE));
        RequestEvidence failed = ungated(Strategy.ROLLING_NO_VALIDATION, RequestState.FAILED_ROLLED_BACK, writtenAt(R1, WRITE));

        for (RequestEvidence r : List.of(wrongBytes, failed)) {
            assertEquals(LadderRung.RENDERED,
                    ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(60), r)).reached(), r.toString());
        }
    }

    @Test
    void anUngatedResolvedApplyCountsWhenItsWriteLeftABaselineAndAStartFollowsIt() {
        List<StepEvidence> mats = List.of(writtenAt(R1, WRITE));
        RequestEvidence resolved = new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED, Strategy.MANUAL,
                ResolutionOutcome.APPLIED, List.of(new ReplicaObservation(R1, false, FILE)), mats);

        assertEquals(LadderRung.RESTART_OBSERVED,
                ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(1), resolved)).reached());
        assertEquals(LadderRung.PERSISTED,
                ConfigLadder.view(draft(G), evidenceWithStart(WRITE.minusSeconds(1), resolved)).reached());
    }

    @Test
    void aResolvedApplyWhoseWriteTimedOutLeftNoBaselineIsLeftAtPersistedOnPurpose() {
        List<StepEvidence> mats = List.of(new StepEvidence(Step.MATERIALIZE, R1, FILE, AttemptOutcome.TIMED_OUT, null, null));
        RequestEvidence resolved = new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED, Strategy.MANUAL,
                ResolutionOutcome.APPLIED, List.of(new ReplicaObservation(R1, false, FILE)), mats);

        assertEquals(LadderRung.PERSISTED,
                ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(3600), resolved)).reached(),
                "no host-clock start to compare with: a later start cannot be told from the predecessor's");
    }

    @Test
    void aStartMustDifferFromTheBaselineByMoreThanTheStoredPrecision() {
        RequestEvidence request = ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, WRITE));

        for (Instant same : List.of(WRITE, WRITE.plusNanos(1), WRITE.plusNanos(900_000), WRITE.plus(StartTolerance.VALUE))) {
            assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidenceWithStart(same, request)).reached(),
                    same + " is the baseline's own start, as stored");
        }
        assertEquals(LadderRung.RESTART_OBSERVED, ConfigLadder.view(draft(G),
                evidenceWithStart(WRITE.plus(StartTolerance.VALUE).plusNanos(1), request)).reached());
    }

    @Test
    void theRungDoesNotDependOnTheOffsetOfTheHostClockFromAnythingButItsOwnBaseline() {
        for (long offset : new long[] {-3600, -60, -5, 0, 5, 60, 3600}) {
            Instant baseline = WRITE.plusSeconds(offset);
            RequestEvidence request = ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, baseline));

            assertEquals(LadderRung.PERSISTED,
                    ConfigLadder.view(draft(G), evidenceWithStart(baseline, request)).reached(), "host " + offset + "s: no restart");
            assertEquals(LadderRung.RESTART_OBSERVED,
                    ConfigLadder.view(draft(G), evidenceWithStart(baseline.plusSeconds(3), request)).reached(),
                    "host " + offset + "s: a restart three seconds later on the host's own clock");
        }
    }

    @Test
    void aStartObservationNeverRescuesAGatedApplyWhoseGateOrVerifyDidNotPass() {
        List<StepEvidence> noVerify = List.of(writtenAt(R1, WRITE),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE));

        assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidenceWithStart(WRITE.plusSeconds(60),
                request(G, RequestState.APPLIED, Strategy.ROLLING_HEALTH_GATED, noVerify))).reached(),
                "the gated strategy's own proof is the gate and the verify; a start is the UNGATED route (OR-112)");
    }

    @Test
    void theInstanceRungIsTheLowestEvenWhenOnlyOnePlacementHasSeenItsStart() {
        RequestEvidence request = ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, WRITE));
        LadderEvidence mixed = new LadderEvidence(G, null, List.of(
                new PlacementEvidence(UUID.randomUUID(), request, WRITE.plusSeconds(1)),
                new PlacementEvidence(UUID.randomUUID(), request, null)));

        assertEquals(LadderRung.PERSISTED, ConfigLadder.instanceRung(mixed));
    }

    @Test
    void aPlacementAwaitsAStartOnlyWhilePersistedUnderAStrategyThatCanBeObservedThisWay() {
        PlacementEvidence manual = new PlacementEvidence(UUID.randomUUID(),
                ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, WRITE)), null);
        PlacementEvidence observed = new PlacementEvidence(UUID.randomUUID(),
                ungated(Strategy.MANUAL, RequestState.STAGED, writtenAt(R1, WRITE)), WRITE.plusSeconds(1));
        PlacementEvidence gated = new PlacementEvidence(UUID.randomUUID(), request(G, RequestState.APPLIED,
                Strategy.ROLLING_HEALTH_GATED, List.of(writtenAt(R1, WRITE))), null);
        PlacementEvidence nothingApplied = new PlacementEvidence(UUID.randomUUID(), null, null);
        PlacementEvidence olderGeneration = new PlacementEvidence(UUID.randomUUID(),
                request(G - 1, RequestState.STAGED, Strategy.MANUAL, writtenAt(R1, WRITE)), null);

        assertTrue(ConfigLadder.awaitsStart(manual, G));
        assertFalse(ConfigLadder.awaitsStart(observed, G), "already observed: nothing left to wait for");
        assertFalse(ConfigLadder.awaitsStart(gated, G), "the gated strategy proves its restart itself");
        assertFalse(ConfigLadder.awaitsStart(nothingApplied, G));
        assertFalse(ConfigLadder.awaitsStart(olderGeneration, G), "an older generation's file is not the newest's");
    }

    @Test
    void aPlacementWhoseWriteCanNeverBeFollowedIsNotAwaited() {
        // persisted by the reconciler's observation although its MATERIALIZE attempt never produced the write evidence
        RequestEvidence resolved = new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED, Strategy.MANUAL,
                ResolutionOutcome.APPLIED, List.of(new ReplicaObservation(R1, false, FILE)),
                List.of(new StepEvidence(Step.MATERIALIZE, R1, FILE, AttemptOutcome.TIMED_OUT, null, null)));
        PlacementEvidence placement = new PlacementEvidence(UUID.randomUUID(), resolved, null);

        assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), new LadderEvidence(G, null, List.of(placement))).reached());
        assertFalse(ConfigLadder.awaitsStart(placement, G),
                "nothing a start could be compared with: inspecting this placement every round would never end");
    }

    // ---- a gated apply the reconciler confirmed (OR-112 (1)(b)) -----------------------------------------------------

    private static RequestEvidence gatedConfirmed(List<ReplicaObservation> observed, List<StepEvidence> steps) {
        return new RequestEvidence(G, RequestState.INDETERMINATE_RESOLVED, Strategy.ROLLING_HEALTH_GATED,
                ResolutionOutcome.APPLIED, observed, steps);
    }

    @Test
    void aGatedApplyTheReconcilerConfirmedWithTheGateProvedReachesRestartObserved() {
        List<StepEvidence> steps = List.of(writtenAt(R1, WRITE),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE));

        ConfigLadderView view = ConfigLadder.view(draft(G), evidence(
                gatedConfirmed(List.of(new ReplicaObservation(R1, true, FILE)), steps)));

        assertEquals(LadderRung.RESTART_OBSERVED, view.reached());
        assertEquals(FILE, rung(view, LadderRung.RESTART_OBSERVED).configHash());
    }

    @Test
    void aConfirmedGatedApplyNeedsEveryReplicaRestartedOntoTheBoundHashAndItsGateProof() {
        List<StepEvidence> both = List.of(writtenAt(R1, WRITE), writtenAt(R2, WRITE),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE),
                step(Step.LIFECYCLE, R2, null, AttemptOutcome.SUCCEEDED, FILE));
        List<StepEvidence> noGateProof = List.of(writtenAt(R1, WRITE));
        List<StepEvidence> failedGate = List.of(writtenAt(R1, WRITE),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.FAILED, FILE));
        List<StepEvidence> oneGate = List.of(writtenAt(R1, WRITE), writtenAt(R2, WRITE),
                step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE));
        ReplicaObservation r1 = new ReplicaObservation(R1, true, FILE);
        ReplicaObservation r2 = new ReplicaObservation(R2, true, FILE);

        assertEquals(LadderRung.RESTART_OBSERVED,
                ConfigLadder.view(draft(G), evidence(gatedConfirmed(List.of(r1, r2), both))).reached());
        // not restarted by this request, or one replica missing from the evidence: persisted, no further
        for (List<ReplicaObservation> observed : List.of(
                List.of(new ReplicaObservation(R1, false, FILE), r2),
                List.of(r1, new ReplicaObservation(R2, false, FILE)))) {
            assertEquals(LadderRung.PERSISTED,
                    ConfigLadder.view(draft(G), evidence(gatedConfirmed(observed, both))).reached(), observed.toString());
        }
        // a replica the confirmation does not name, or names with other bytes, is not even persisted (the existing rule)
        for (List<ReplicaObservation> observed : List.of(List.of(r1),
                List.of(new ReplicaObservation(R1, true, OTHER), r2))) {
            assertEquals(LadderRung.RENDERED,
                    ConfigLadder.view(draft(G), evidence(gatedConfirmed(observed, both))).reached(), observed.toString());
        }
        // the evidence is complete but the journal holds no gate proof for a replica
        for (List<StepEvidence> steps : List.of(noGateProof, failedGate)) {
            assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidence(
                    gatedConfirmed(List.of(r1), steps))).reached(), steps.toString());
        }
        assertEquals(LadderRung.PERSISTED, ConfigLadder.view(draft(G), evidence(
                gatedConfirmed(List.of(r1, r2), oneGate))).reached(), "R2 has no gate proof");
        // not_applied is not even persisted
        assertEquals(LadderRung.RENDERED, ConfigLadder.view(draft(G), evidence(new RequestEvidence(G,
                RequestState.INDETERMINATE_RESOLVED, Strategy.ROLLING_HEALTH_GATED, ResolutionOutcome.NOT_APPLIED,
                List.of(r1), List.of(writtenAt(R1, WRITE), step(Step.LIFECYCLE, R1, null, AttemptOutcome.SUCCEEDED, FILE))))).reached());
    }

    // ---- the instance rung the landing caches --------------------------------------------------------------------

    @Test
    void theInstanceRungIsDerivedForTheNewestGenerationAndAnUnwrittenColumnMeansThePreApplyRung() {
        assertEquals(LadderRung.DESIRED, ConfigLadder.instanceRung(LadderEvidence.none()));
        assertEquals(LadderRung.DESIRED, ConfigLadder.unappliedRung(LadderEvidence.none()));
        assertEquals(LadderRung.RENDERED, ConfigLadder.unappliedRung(evidence()));
        assertEquals(LadderRung.PERSISTED, ConfigLadder.instanceRung(evidence(manualStaged())));
        assertEquals(LadderRung.RESTART_OBSERVED, ConfigLadder.instanceRung(evidence(gatedApplied())));
    }

    @Test
    void withNoGenerationNothingIsPresent() {
        ConfigLadderView view = ConfigLadder.view(Optional.empty(), LadderEvidence.none());

        assertEquals(LadderRung.DESIRED, view.reached());
        assertTrue(view.rungs().stream().noneMatch(Rung::present));
    }
}
