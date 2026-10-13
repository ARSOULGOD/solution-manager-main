package com.solace.sm.dp.apply.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.HostObservation;
import com.solace.sm.dp.apply.engine.HostObservation.Content;
import com.solace.sm.dp.apply.engine.HostObservation.ReplicaFacts;
import com.solace.sm.dp.apply.engine.RedrivePlan;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.reconcile.ReconcileDecision.Confirm;
import com.solace.sm.dp.apply.reconcile.ReconcileDecision.Context;
import com.solace.sm.dp.apply.reconcile.ReconcileDecision.GiveUp;
import com.solace.sm.dp.apply.reconcile.ReconcileDecision.Redrive;
import com.solace.sm.dp.apply.reconcile.ReconcileDecision.Retry;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The ruled decision table (OR-59 made exact by OR-63, Q-R1 to Q-R4), one test per row. Pure: the observation and the
 * request's counters go in, a decision comes out. No database, no host.
 */
class ReconcileDecisionTest {

    private static final String INTENDED = "hmacsha256v1:" + "a".repeat(64);
    private static final String PRIOR = "hmacsha256v1:" + "b".repeat(64);
    private static final String FOREIGN = "hmacsha256v1:" + "c".repeat(64);

    private static final PlacementRef PLACEMENT = new PlacementRef(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));

    /** No later request, young enough, no re-drive spent, one allowed: the situation in which everything is permitted. */
    private static final Context OPEN = new Context(false, false, 0, 1);

    private static TargetReplica replica(String ref) {
        return new TargetReplica(PLACEMENT, new ReplicaRef(ref));
    }

    private static ReplicaFacts facts(String ref, Content content, String current, boolean restarted,
            HealthSignal health) {
        return new ReplicaFacts(replica(ref), content, current, restarted, health, null);
    }

    private static ReplicaFacts intended(String ref, boolean restarted) {
        return facts(ref, Content.INTENDED, INTENDED, restarted, HealthSignal.HEALTHY);
    }

    private static HostObservation observed(Strategy strategy, ReplicaFacts... replicas) {
        return new HostObservation.Observed(7, strategy, INTENDED, List.of(replicas));
    }

    // ---- the confirm rows -----------------------------------------------------------------------

    /** Q-R4 (a): under manual the core never calls the lifecycle verb, so a matching file is the whole proof. */
    @Test
    void intendedFileUnderManualIsConfirmedWithoutAStartTimeCheck() {
        ReconcileDecision decision = ReconcileDecision.decide(
                observed(Strategy.MANUAL, intended("r1", false)), OPEN);

        Confirm confirm = assertInstanceOf(Confirm.class, decision);
        assertFalse(confirm.gateProved(), "manual has no gate");
    }

    @Test
    void intendedFileRestartedByThisRequestIsConfirmedWithNoHostWrite() {
        for (Strategy strategy : List.of(Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL)) {
            ReconcileDecision decision = ReconcileDecision.decide(
                    observed(strategy, intended("r1", true), intended("r2", true)), OPEN);

            assertFalse(assertInstanceOf(Confirm.class, decision, strategy.toString()).gateProved(),
                    "an ungated strategy proves no gate");
        }
    }

    /** Q-R2 (a): one read-only gate observation; HEALTHY confirms and records what it proved. */
    @Test
    void gatedConfirmTakesTheGateObservationAndRecordsGateProved() {
        ReconcileDecision decision = ReconcileDecision.decide(
                observed(Strategy.ROLLING_HEALTH_GATED, intended("r1", true)), OPEN);

        assertTrue(assertInstanceOf(Confirm.class, decision).gateProved());
    }

    @Test
    void gatedConfirmWithoutAHealthyObservationStaysIndeterminate() {
        for (HealthSignal signal : List.of(HealthSignal.STARTING, HealthSignal.UNHEALTHY, HealthSignal.NOT_DECLARED)) {
            ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.ROLLING_HEALTH_GATED,
                    facts("r1", Content.INTENDED, INTENDED, true, signal)), OPEN);

            assertEquals("reconcile.gate.unproven", assertInstanceOf(GiveUp.class, decision, signal.toString())
                    .why().token());
        }
    }

    /** A probe that could not be read says nothing about health: it is a failed read, retried, never a verdict. */
    @Test
    void anUnreadableProbeIsAFailedInspectionNotAVerdict() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.ROLLING_HEALTH_GATED,
                facts("r1", Content.INTENDED, INTENDED, true, HealthSignal.UNREADABLE)), OPEN);

        assertEquals("reconcile.gate.unreadable", assertInstanceOf(Retry.class, decision).why().token());
    }

    /** Every replica of a gated confirm must be healthy: one that is not stops the whole confirmation. */
    @Test
    void aGatedConfirmNeedsEveryReplicaHealthy() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.ROLLING_HEALTH_GATED,
                intended("r1", true), facts("r2", Content.INTENDED, INTENDED, true, HealthSignal.UNHEALTHY)), OPEN);

        assertInstanceOf(GiveUp.class, decision);
    }

    // ---- the re-drive rows ----------------------------------------------------------------------

    @Test
    void intendedFileNotRestartedReRunsOnlyTheRestartThenVerifies() {
        ReconcileDecision decision = ReconcileDecision.decide(
                observed(Strategy.ROLLING_NO_VALIDATION, intended("r1", false)), OPEN);

        RedrivePlan plan = assertInstanceOf(Redrive.class, decision).plan();
        assertEquals(1, plan.steps().size());
        RedrivePlan.Step step = plan.steps().get(0);
        assertFalse(step.rewrite(), "the file is already the intended one: nothing is written");
        assertTrue(step.restart());
        assertEquals(7, plan.generation());
        assertEquals(INTENDED, plan.intendedHash());
        assertEquals(Strategy.ROLLING_NO_VALIDATION, plan.strategy());
    }

    @Test
    void priorContentIsReAppliedThenRestartedAndVerified() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.RESTART_ALL,
                facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)), OPEN);

        RedrivePlan.Step step = assertInstanceOf(Redrive.class, decision).plan().steps().get(0);
        assertTrue(step.rewrite());
        assertEquals(PRIOR, step.expectedCurrentHash(), "the write is conditional on the file still being what was observed");
        assertTrue(step.restart());
    }

    /** Q-R1 (a): the prior state was no file and the adapter recorded the absence - that counts as prior. */
    @Test
    void anAbsentRecordedPriorIsReAppliedConditionallyOnStillBeingAbsent() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.RESTART_ALL,
                facts("r1", Content.PRIOR, null, false, HealthSignal.HEALTHY)), OPEN);

        RedrivePlan.Step step = assertInstanceOf(Redrive.class, decision).plan().steps().get(0);
        assertTrue(step.rewrite());
        assertNull(step.expectedCurrentHash(), "null means 'still absent'");
    }

    /** Under manual the core never restarts: a re-apply is a write and nothing more. */
    @Test
    void aManualReApplyWritesAndNeverRestarts() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.MANUAL,
                facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)), OPEN);

        RedrivePlan.Step step = assertInstanceOf(Redrive.class, decision).plan().steps().get(0);
        assertTrue(step.rewrite());
        assertFalse(step.restart(), "manual: the operator restarts, SM does not");
    }

    /** A replica that already holds the intended file and was restarted needs nothing; only its sibling is acted on. */
    @Test
    void aPartlyAppliedPlacementActsOnlyOnTheReplicasThatNeedIt() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.ROLLING_NO_VALIDATION,
                intended("r1", true), facts("r2", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)), OPEN);

        RedrivePlan plan = assertInstanceOf(Redrive.class, decision).plan();
        assertEquals(List.of("r2"), plan.steps().stream().map(s -> s.replica().ref().value()).toList());
    }

    // ---- the never-overwrite row ----------------------------------------------------------------

    @Test
    void foreignContentIsNeverOverwrittenAndGivesUpWithAnAlertClass() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.RESTART_ALL,
                facts("r1", Content.FOREIGN, FOREIGN, false, HealthSignal.HEALTHY)), OPEN);

        assertEquals("reconcile.foreign.content", assertInstanceOf(GiveUp.class, decision).why().token());
    }

    /** One replica's foreign file stops the whole request: a sibling that is prior is not re-applied around it. */
    @Test
    void oneForeignReplicaStopsEveryOtherReplicaFromBeingTouched() {
        ReconcileDecision decision = ReconcileDecision.decide(observed(Strategy.RESTART_ALL,
                facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY),
                facts("r2", Content.FOREIGN, FOREIGN, false, HealthSignal.HEALTHY)), OPEN);

        assertEquals("reconcile.foreign.content", assertInstanceOf(GiveUp.class, decision).why().token());
    }

    // ---- undecided and not reconcilable ---------------------------------------------------------

    @Test
    void anUndecidedObservationIsAFailedInspectionAndNeverARestart() {
        ReconcileDecision decision = ReconcileDecision.decide(
                new HostObservation.Undecided(new ErrorClass("ssh.connect.timeout")), OPEN);

        assertEquals("ssh.connect.timeout", assertInstanceOf(Retry.class, decision).why().token());
    }

    @Test
    void aRequestNoHostObservationCanSettleGivesUpWithItsOwnClass() {
        ReconcileDecision decision = ReconcileDecision.decide(
                new HostObservation.NotReconcilable(new ErrorClass("reconcile.no.intent")), OPEN);

        assertEquals("reconcile.no.intent", assertInstanceOf(GiveUp.class, decision).why().token());
    }

    // ---- the bounds on a re-drive (Q-R3) --------------------------------------------------------

    @Test
    void aLaterRequestForbidsAnyWriteButStillAllowsConfirm() {
        Context later = new Context(true, false, 0, 1);

        assertEquals("reconcile.superseded.by.later.request", assertInstanceOf(GiveUp.class,
                ReconcileDecision.decide(observed(Strategy.RESTART_ALL,
                        facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)), later)).why().token());
        assertEquals("reconcile.superseded.by.later.request", assertInstanceOf(GiveUp.class,
                ReconcileDecision.decide(observed(Strategy.ROLLING_NO_VALIDATION, intended("r1", false)), later))
                .why().token(), "a restart-only re-drive is a lifecycle action too");
        assertInstanceOf(Confirm.class, ReconcileDecision.decide(
                observed(Strategy.ROLLING_NO_VALIDATION, intended("r1", true)), later),
                "a confirmation is a read: a stop admitted since changes nothing about what the file holds");
    }

    @Test
    void noReDriveHappensPastTheMaxRedriveAgeButConfirmStillDoes() {
        Context old = new Context(false, true, 0, 1);

        assertEquals("reconcile.redrive.too.late", assertInstanceOf(GiveUp.class, ReconcileDecision.decide(
                observed(Strategy.RESTART_ALL, facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)), old))
                .why().token());
        assertInstanceOf(Confirm.class, ReconcileDecision.decide(
                observed(Strategy.MANUAL, intended("r1", false)), old));
    }

    @Test
    void reDrivesAreBoundedSeparatelyFromInspections() {
        Context spent = new Context(false, false, 1, 1);

        assertEquals("reconcile.redrive.exhausted", assertInstanceOf(GiveUp.class, ReconcileDecision.decide(
                observed(Strategy.RESTART_ALL, facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)), spent))
                .why().token());
        assertInstanceOf(Redrive.class, ReconcileDecision.decide(
                observed(Strategy.RESTART_ALL, facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)),
                new Context(false, false, 1, 2)), "a cap of two allows a second");
        assertInstanceOf(GiveUp.class, ReconcileDecision.decide(
                observed(Strategy.RESTART_ALL, facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY)),
                new Context(false, false, 0, 0)), "a cap of zero forbids every re-drive");
    }

    /** The reasons are ordered by how absolute they are: a later request outranks age, which outranks the count. */
    @Test
    void theGiveUpReasonsAreOrderedByHowAbsoluteTheyAre() {
        HostObservation obs = observed(Strategy.RESTART_ALL,
                facts("r1", Content.PRIOR, PRIOR, false, HealthSignal.HEALTHY));

        assertEquals("reconcile.superseded.by.later.request", assertInstanceOf(GiveUp.class,
                ReconcileDecision.decide(obs, new Context(true, true, 5, 1))).why().token());
        assertEquals("reconcile.redrive.too.late", assertInstanceOf(GiveUp.class,
                ReconcileDecision.decide(obs, new Context(false, true, 5, 1))).why().token());
    }
}
