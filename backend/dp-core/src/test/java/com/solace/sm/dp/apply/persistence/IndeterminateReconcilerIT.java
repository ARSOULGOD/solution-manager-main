package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler.Result;
import com.solace.sm.dp.apply.reconcile.ReconcileProperties;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter.Fault;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.8 - verify-then-retry, end to end over PostgreSQL and an in-memory host.
 *
 * <p>Every case builds the journal a real cut-off leaves, through the real engine, rather than seeding rows: a request
 * is driven until the host is cut off ({@link Cutting}), or the process "dies" (an adapter throws a plain
 * {@code RuntimeException}, as {@code ResumeIT} does) and the request is stranded {@code APPLY_INDETERMINATE} exactly as
 * {@code ApplyExecutor.strand} strands it. The reconciler is then pointed at the host and every claim is checked against
 * the host (what was written, what was restarted) and against the journal.
 */
@Testcontainers(disabledWithoutDocker = true)
class IndeterminateReconcilerIT {

    private static final RenderedConfig G7 = TestHashers.rendered("generation: 7\n");
    private static final RenderedConfig G8 = TestHashers.rendered("generation: 8\n");
    private static final String FOREIGN = TestHashers.SHA256.hash(TestHashers.TENANT,
            "somebody else wrote this\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static final ReconcileProperties PROPS = ReconcileProperties.defaults();
    private static final OutcomeService.RedriveBounds BOUNDS =
            new OutcomeService.RedriveBounds(PROPS.maxRedriveAge(), PROPS.maxRedrives());

    private ReconcilerFixture fx;
    private InMemoryRuntimeAdapter mem;
    private Cutting host;
    private TargetReplica r1;

    @BeforeEach
    void setUp() {
        fx = new ReconcilerFixture();
        mem = ReconcilerFixture.host(fx.placement, "replica-1");
        host = new Cutting(mem);
        r1 = mem.replicas().get(0);
    }

    /** The host as the reconciler finds it: the cut-off is over, the engine is rebuilt over the healthy host. */
    private IndeterminateReconciler reconcilerOver(RenderedConfig config) {
        fx.engine(host, (p, g) -> config);
        return fx.reconciler(PROPS);
    }

    // ---- building the journals a cut-off leaves ---------------------------------------------------

    /** A manual apply of {@code G7}: the file is G7 and the request is STAGED, so a later apply has a prior to leave behind. */
    private void stageGenerationSeven() {
        fx.engine(host, (p, g) -> G7);
        assertEquals(RequestState.STAGED, landed(fx.executor.execute(fx.apply(7, Strategy.MANUAL))));
    }

    private static RequestState landed(ApplyOutcome o) {
        return assertInstanceOf(ApplyOutcome.Landed.class, o).state();
    }

    /**
     * Ungated apply of {@code G8} cut off after the restart: the file is G8 and the replica was restarted by the request,
     * but the post-restart read-back could not be taken, so the request is APPLY_INDETERMINATE.
     */
    private ApplyId indeterminateAfterRestart(Strategy strategy) {
        host.cutStatusAfterRestart = true;
        fx.engine(host, (p, g) -> G8);
        ApplyOutcome o = fx.executor.execute(fx.apply(8, strategy));
        assertEquals(RequestState.APPLY_INDETERMINATE, landed(o));
        host.cutStatusAfterRestart = false;
        return fx.onlyOpenRequest();
    }

    /**
     * A process death inside the first MATERIALIZE, then the strand: the file is what {@code afterWrite} leaves,
     * the generation's pre-image is retained (the write captured it), the attempt is left open.
     */
    private ApplyId strandedAfterCrash(RenderedConfig config, long generation, Strategy strategy,
            Consumer<TargetReplica> afterWrite) {
        host.crashAfterMaterialize = afterWrite;
        fx.engine(host, (p, g) -> config);
        assertThrows(RuntimeException.class, () -> fx.executor.execute(fx.apply(generation, strategy)));
        host.crashAfterMaterialize = null;
        ApplyId id = fx.onlyOpenRequest();
        fx.outcomes.land(fx.tenant, id, RequestState.APPLY_INDETERMINATE);
        return id;
    }

    /** G7 on disk, then a cut-off apply of G8 that left the replica holding G7 again, pre-image retained. */
    private ApplyId priorContentAfterACrash(Strategy strategy) {
        stageGenerationSeven();
        return strandedAfterCrash(G8, 8, strategy, r -> mem.setFile(r, G7.hash()));
    }

    /** The very first apply: nothing was ever on disk, the absence is recorded, and the write was rewound. */
    private ApplyId absentPriorAfterACrash(Strategy strategy) {
        return strandedAfterCrash(G8, 8, strategy, r -> mem.setFile(r, null));
    }

    private static Resolution operatorResolution(String hash) {
        return new Resolution("operator-1", ResolutionOutcome.APPLIED, new ApplyResolutionEvidence(8,
                List.of(new ReplicaObservation(new com.solace.sm.dp.apply.model.ReplicaRef("replica-1"), true, hash))));
    }

    private JdbcReconciliationRepository.Row ledgerRow(ApplyId id) {
        return fx.ledger.find(fx.tenant, id).orElseThrow();
    }

    /** The newest attempt of the request's intent for this step (one replica in these fixtures). */
    private AttemptRow latestAttempt(ApplyId id, Step step) {
        for (StepIntentRow intent : ApplyEngineHarness.intents().forRequest(fx.tenant, id)) {
            if (intent.step() == step) {
                return ApplyEngineHarness.attempts().latest(fx.tenant, intent.intentId()).orElseThrow();
            }
        }
        throw new AssertionError("no " + step + " intent for " + id.value());
    }

    // ================================================================================================
    // The confirm rows
    // ================================================================================================

    @Test
    void intendedFileRestartedByThisRequestIsConfirmedWithNoHostWrite() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        int writes = mem.materializeCalls();
        int restarts = mem.restarts();
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.CONFIRMED, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(writes, mem.materializeCalls(), "a confirmation writes nothing");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(restarts, mem.restarts(), "and restarts nothing");
        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id));
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("t", fx.tenant.value()).addValue("a", id.value());
        assertEquals("sm-reconciler", ApplyEngineHarness.jdbc().queryForObject(
                "SELECT resolved_by FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a", key, String.class),
                "Step 0 (c): a fixed non-principal resolver token, named in the decision");
        assertEquals("applied", ApplyEngineHarness.jdbc().queryForObject(
                "SELECT resolution_outcome FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a", key, String.class));
        assertNotNull(ledgerRow(id).gaveUpAt(), "a finished reconciliation is closed and never polled again");
        assertEquals("reconcile.confirmed", ledgerRow(id).lastClass());
    }

    /** Q-R4 (a): under manual the core never restarts, so a matching file is the whole proof. */
    @Test
    void intendedFileUnderManualIsConfirmedWithoutAStartTimeCheck() {
        ApplyId id = strandedAfterCrash(G8, 8, Strategy.MANUAL, r -> { });
        assertEquals(G8.hash(), mem.hashOf(r1), "the write landed");
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.setRunning(r1, true);
        mem.fault(Fault.BASELINE_UNREADABLE);   // a start-time check would find nothing to compare, and must not be made

        assertEquals(Result.CONFIRMED, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(0, mem.restarts(), "manual: the operator restarts, SM never does");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id));
    }

    /** M-2: the verdict compares host-clock values only; a DP clock skewed an hour either way changes nothing. */
    @Test
    void restartedIsDecidedAgainstTheLifecyclePreBaselineOnHostClockOnly() {
        for (long skewMinutes : new long[] {-60, 60}) {
            setUp();
            ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
            fx.engine(host, (p, g) -> G8);
            ReconcilerFixture.TestClock skewed = new ReconcilerFixture.TestClock();
            skewed.advance(Duration.ofMinutes(skewMinutes));

            assertEquals(Result.CONFIRMED, fx.reconciler(PROPS, skewed).reconcileOne(fx.tenant, id),
                    "DP clock skewed " + skewMinutes + " min");
        }
    }

    /** M-2 / M18: a replica with no readable start baseline is undecided - retried, and never restarted. */
    @Test
    void aReplicaMidRestartIsUndecidedAndRetriedNeverRestarted() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        int restarts = mem.restarts();
        mem.baselineUnreadableOn("replica-1");

        assertEquals(Result.RETRY_SCHEDULED, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(restarts, mem.restarts(), "the reconciler never restarts a replica it could not read");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));
        assertEquals(1, ledgerRow(id).inspections(), "an undecided inspection is a failed one");
        assertNull(ledgerRow(id).gaveUpAt());

        mem.clearBaselineUnreadable();
        fx.clock.advance(Duration.ofMinutes(5));
        assertEquals(Result.CONFIRMED, reconciler.reconcileOne(fx.tenant, id), "and the next inspection decides");
    }

    // ---- the gate (Q-R2) --------------------------------------------------------------------------

    /** Gated apply whose gate saw no health signal: indeterminate with the replica restarted and the file written. */
    private ApplyId gatedIndeterminateAfterRestart() {
        host.healthAfterRestart = HealthSignal.NOT_DECLARED;
        fx.engine(host, (p, g) -> G8);
        assertEquals(RequestState.APPLY_INDETERMINATE, landed(fx.executor.execute(fx.apply(8, Strategy.ROLLING_HEALTH_GATED))));
        host.healthAfterRestart = null;
        mem.healthOverride(null);
        return fx.onlyOpenRequest();
    }

    @Test
    void gatedConfirmTakesOneGateObservationAndRecordsGateProved() {
        ApplyId id = gatedIndeterminateAfterRestart();
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        assertEquals(AttemptOutcome.FAILED, latestAttempt(id, Step.LIFECYCLE).outcome(),
                "the original gate never proved anything");
        int before = mem.statusCalls();

        assertEquals(Result.CONFIRMED, reconciler.reconcileOne(fx.tenant, id));

        assertTrue(mem.statusCalls() > before, "the gate observation is a read of the host, made now");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(AttemptOutcome.SUCCEEDED, latestAttempt(id, Step.LIFECYCLE).outcome(),
                "a succeeded LIFECYCLE step IS a passed gate (observeApply derives gateProved from it)");
        assertEquals(G8.hash(), latestAttempt(id, Step.LIFECYCLE).observedHash());
        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id));
    }

    @Test
    void gatedConfirmWithoutAHealthyObservationStaysIndeterminate() {
        ApplyId id = gatedIndeterminateAfterRestart();
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.healthOverride(HealthSignal.UNHEALTHY);

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));
        assertEquals("reconcile.gate.unproven", fx.reconcileClassOf(id));
        assertEquals(AttemptOutcome.FAILED, latestAttempt(id, Step.LIFECYCLE).outcome(), "no gate proof is recorded");
    }

    // ================================================================================================
    // The re-drive rows
    // ================================================================================================

    /** The Q-R3 default is one re-drive, so a restart-only re-drive uses it and lands the normal way. */
    @Test
    void intendedFileNotRestartedReRunsOnlyTheRestartThenVerifies() {
        mem.fault(Fault.BASELINE_UNREADABLE);
        fx.engine(host, (p, g) -> G8);
        assertEquals(RequestState.APPLY_INDETERMINATE, landed(fx.executor.execute(fx.apply(8, Strategy.ROLLING_NO_VALIDATION))));
        ApplyId id = fx.onlyOpenRequest();
        mem.fault(Fault.NONE);
        assertEquals(G8.hash(), mem.hashOf(r1), "the write landed");
        assertEquals(0, mem.restarts(), "and nothing restarted: that is the cut");
        int writes = mem.materializeCalls();
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(1, mem.restarts(), "exactly one lifecycle step");
        assertEquals(writes, mem.materializeCalls(), "and no write");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(RequestState.APPLIED, fx.stateOf(id), "the normal landing");
        assertEquals(AttemptOutcome.SUCCEEDED, latestAttempt(id, Step.VERIFY).outcome(), "then the post-restart verify");
        assertEquals(1, ledgerRow(id).redrives());
        assertNotNull(ledgerRow(id).gaveUpAt(), "the reconciliation is finished");
    }

    @Test
    void priorContentIsReAppliedThenVerified() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        assertEquals(G7.hash(), mem.hashOf(r1), "the cut left the prior file");
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        int writesBefore = mem.materializeCalls();

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(1, mem.rematerializeCalls(), "the write goes through the conditional operation");
        assertEquals(writesBefore, mem.materializeCalls(), "the unconditional write is never used by a re-drive");
        assertEquals(G8.hash(), mem.hashOf(r1));
        assertEquals(1, mem.restarts());
        assertEquals(RequestState.APPLIED, fx.stateOf(id));
        assertEquals(AttemptOutcome.SUCCEEDED, latestAttempt(id, Step.VERIFY).outcome());
    }

    /**
     * W4 note (d), a deliberate dp-remote behaviour change (BE-D34, clarified 2026-10-11): the re-drive's succeeded re-write records
     * the start baseline the read-back reported, as the forward write does, so the config ladder's start route has something to
     * compare a later container start with. Before, a re-drive closed its attempt with none and the ladder could never reach
     * restart-observed for a re-driven request (parity with the forward path, not a regression of a recorded one).
     */
    @Test
    void aReDrivesSucceededReWriteRecordsTheStartBaselineTheReadBackReported() {
        ApplyId id = priorContentAfterACrash(Strategy.MANUAL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));

        String baseline = ApplyEngineHarness.jdbc().queryForObject("SELECT a.write_baseline::text FROM sm_data.apply_attempt a"
                + " WHERE a.tenant_id = :t AND a.step = 'MATERIALIZE' AND a.outcome = 'SUCCEEDED' AND a.intent_id IN"
                + " (SELECT i.intent_id FROM sm_data.apply_step_intent i WHERE i.tenant_id = :t AND i.apply_id = :a"
                + " AND i.step = 'MATERIALIZE') ORDER BY a.started_at DESC LIMIT 1",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("t", fx.tenant.value())
                        .addValue("a", id.value()), String.class);
        assertNotNull(baseline, "the succeeded re-write keeps the status's start baseline");
        assertTrue(baseline.contains("startedAt"), baseline);
    }

    /** Q-R1 (a): no file and the absence marker present counts as prior; the re-apply is fully journalled. */
    @Test
    void anAbsentRecordedPriorIsReAppliedThenVerified() {
        ApplyId id = absentPriorAfterACrash(Strategy.RESTART_ALL);
        assertNull(mem.hashOf(r1), "the cut left no file");
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(1, mem.rematerializeCalls());
        assertEquals(G8.hash(), mem.hashOf(r1));
        assertEquals(RequestState.APPLIED, fx.stateOf(id));
    }

    /** Under manual a re-apply is a write and the normal landing is STAGED (BE-D16), never a restart. */
    @Test
    void aManualReApplyWritesAndLandsStagedWithoutRestarting() {
        ApplyId id = priorContentAfterACrash(Strategy.MANUAL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(1, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());
        assertEquals(RequestState.STAGED, fx.stateOf(id));
        assertEquals(G8.hash(), mem.hashOf(r1));
    }

    /** B-2: prior is the pre-image the adapter retained on the host, read now. Nothing else carries a prior hash. */
    @Test
    void priorIsTheRetainedPreImageReadFromTheHostNotThePreBaseline() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        // The journal holds no hash of the prior file anywhere: only the host's retained pre-image can say what it was.
        assertNull(latestAttempt(id, Step.MATERIALIZE).observedHash(), "the open attempt recorded nothing about the prior file");
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id),
                "recognising the file as prior needs the retained pre-image");
    }

    // ---- never overwrite content SM did not write -------------------------------------------------

    @Test
    void foreignContentIsNeverOverwrittenAndStaysIndeterminateWithAnAlert() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        mem.competingWrite(r1, FOREIGN);
        long stamp = fx.stampOf(id);
        List<String> noticesBefore = fx.outcomeNotices();
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(FOREIGN, mem.hashOf(r1), "a human's file is never reverted (B/D12)");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));
        assertEquals("reconcile.foreign.content", fx.reconcileClassOf(id));
        assertTrue(fx.stampOf(id) > stamp, "M-1: the give-up is an observable change");
        assertEquals(noticesBefore.size() + 1, fx.outcomeNotices().size(), "exactly one notice");
        assertEquals("APPLY_INDETERMINATE", fx.outcomeNotices().get(fx.outcomeNotices().size() - 1));
        assertEquals(fx.stampOf(id), fx.events.get(fx.events.size() - 1).stateVersion(),
                "the notice carries the stamp the database wrote, not one computed in Java");
        assertNotNull(ledgerRow(id).gaveUpAt());
    }

    /**
     * OR-102: the file holds what the retained copy recorded, but the copy itself changed since it was retained. It is no
     * longer a prior to recognise, so the file reads foreign and is never overwritten, and the change is reported by path.
     */
    @Test
    void aFileMatchingAChangedPreImageIsNeverReAppliedOverAndTheChangeIsReported() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        assertEquals(G7.hash(), mem.hashOf(r1), "the cut left the prior file, as in the re-applied case above");
        mem.preImageChangedSinceRetention(8, new com.solace.sm.dp.apply.runtime.PreImageChange(
                List.of("cache-engine.ttl"), false));
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        LogCapture.Captured<Result> run = LogCapture.during(() -> reconciler.reconcileOne(fx.tenant, id));

        assertEquals(Result.GAVE_UP, run.value());
        assertEquals(0, mem.rematerializeCalls(), "SM never overwrites content it cannot place");
        assertEquals(G7.hash(), mem.hashOf(r1));
        String line = run.firstContaining("retained pre-image changed");
        assertNotNull(line, run.text());
        assertTrue(line.contains("cache-engine.ttl"), line);
    }

    /** The cut came before the pre-image was retained, so "prior" is unknown and any other content is foreign. */
    @Test
    void anUnknownPriorWithForeignContentIsNeverOverwritten() {
        host.crashBeforeMaterialize = true;
        fx.engine(host, (p, g) -> G8);
        assertThrows(RuntimeException.class, () -> fx.executor.execute(fx.apply(8, Strategy.RESTART_ALL)));
        host.crashBeforeMaterialize = false;
        ApplyId id = fx.onlyOpenRequest();
        fx.outcomes.land(fx.tenant, id, RequestState.APPLY_INDETERMINATE);
        mem.competingWrite(r1, FOREIGN);
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(FOREIGN, mem.hashOf(r1));
        assertEquals(0, mem.rematerializeCalls());
        assertEquals("reconcile.foreign.content", fx.reconcileClassOf(id));
    }

    /** M-4: the conditional write re-reads the file; a writer that landed since the observation is not overwritten. */
    @Test
    void foreignContentWrittenBetweenInspectionAndWriteIsNotOverwritten() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.beforeRematerialize((m, r) -> m.competingWrite(r, FOREIGN));

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(FOREIGN, mem.hashOf(r1), "the conditional write refused");
        assertEquals(0, mem.restarts());
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id), "the re-drive was walked back");
        assertEquals("reconcile.foreign.content", fx.reconcileClassOf(id));
        assertEquals(1, ledgerRow(id).redrives(), "the re-drive was begun, and counted");
    }

    /** The fresh observation overrides a journal that says everything finished. */
    @Test
    void everyDecisionIsMadeFromAFreshHostObservationNeverFromTheJournalAlone() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        assertEquals(AttemptOutcome.SUCCEEDED, latestAttempt(id, Step.LIFECYCLE).outcome(),
                "the journal says the restart happened");
        assertEquals(AttemptOutcome.SUCCEEDED, latestAttempt(id, Step.MATERIALIZE).outcome(),
                "and the write was read back");
        mem.competingWrite(r1, FOREIGN);   // ... but the host has since changed
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals("reconcile.foreign.content", fx.reconcileClassOf(id));
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));
    }

    // ================================================================================================
    // Bounds
    // ================================================================================================

    @Test
    void anUnreachableHostIsRetriedWithBackoffUpToTheBoundThenStaysAndAlerts() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.failStatusWith("ssh.connect.timeout");
        long[] expectedSeconds = {30, 60, 120, 240};

        for (int i = 0; i < 4; i++) {
            Instant before = fx.clock.instant();
            assertEquals(Result.RETRY_SCHEDULED, reconciler.reconcileOne(fx.tenant, id), "failure " + (i + 1));
            assertEquals(i + 1, ledgerRow(id).inspections());
            assertEquals(before.plusSeconds(expectedSeconds[i]), ledgerRow(id).nextAttemptAt(),
                    "the spacing doubles: initial 30 s, then 60, 120, 240");
            fx.clock.advance(Duration.ofSeconds(expectedSeconds[i]));
        }
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id), "still not given up before the bound");

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id), "exactly max-attempts inspections");

        assertEquals(5, ledgerRow(id).inspections());
        assertEquals("reconcile.exhausted", fx.reconcileClassOf(id));
        assertNotNull(ledgerRow(id).gaveUpAt());
        int polls = mem.statusCalls();
        fx.clock.advance(Duration.ofHours(1));
        assertEquals(0, reconciler.runDue(fx.tenant), "a given-up request is never polled again");
        assertEquals(polls, mem.statusCalls(), "and no host is touched for it");
    }

    @Test
    void reDrivesAreBoundedSeparatelyAndAGiveUpIsPermanent() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.failRematerializeWith("ssh.write.failed");   // the host is cut off again after the re-check passes

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id), "cut off again: inspected again");
        assertEquals(1, ledgerRow(id).redrives());
        assertEquals(0, ledgerRow(id).inspections(), "a re-drive is not an inspection failure");
        assertNull(ledgerRow(id).gaveUpAt());

        fx.clock.advance(Duration.ofMinutes(10));
        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals("reconcile.redrive.exhausted", fx.reconcileClassOf(id));
        assertEquals(1, mem.rematerializeCalls(), "no second write");
        assertEquals(0, mem.restarts());
        int polls = mem.statusCalls();
        fx.clock.advance(Duration.ofHours(1));
        assertEquals(0, reconciler.runDue(fx.tenant));
        assertEquals(polls, mem.statusCalls(), "permanent");
    }

    @Test
    void aReDriveHittingThePerIntentCapGivesUp() {
        host.crashAfterMaterialize = r -> mem.setFile(r, null);
        fx.engine(host, (p, g) -> G8, 1);
        assertThrows(RuntimeException.class, () -> fx.executor.execute(fx.apply(8, Strategy.RESTART_ALL)));
        host.crashAfterMaterialize = null;
        ApplyId id = fx.onlyOpenRequest();
        fx.outcomes.land(fx.tenant, id, RequestState.APPLY_INDETERMINATE);
        fx.engine(host, (p, g) -> G8, 1);   // a cap of ONE attempt per intent: the original run already spent it
        IndeterminateReconciler reconciler = fx.reconciler(PROPS);
        List<String> noticesBefore = fx.outcomeNotices();

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals("reconcile.redrive.cap", fx.reconcileClassOf(id));
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id), "walked back from RUNNING");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(noticesBefore.size() + 1, fx.outcomeNotices().size(), "walk-back and class in one transaction: one notice");
    }

    @Test
    void noReDriveHappensPastTheMaxRedriveAgeButConfirmStillDoes() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        reconciler.enqueueIndeterminate(fx.tenant);   // first sighting
        fx.clock.advance(Duration.ofMinutes(61));

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals("reconcile.redrive.too.late", fx.reconcileClassOf(id));
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());
    }

    /** REVIEW R-A: the age bound runs from the APPLY_INDETERMINATE landing (Q-R3), not from the DP's first sighting. */
    @Test
    void reviewTheRedriveAgeRunsFromTheLandingNotFromTheFirstSighting() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);   // landed now, on the engine's (system) clock
        IndeterminateReconciler late = reconcilerOver(G8);
        // The data plane was down for two hours after the landing, so its first sighting is two hours late.
        fx.clock.advance(Duration.ofHours(2));
        late.enqueueIndeterminate(fx.tenant);

        assertEquals(Result.GAVE_UP, late.reconcileOne(fx.tenant, id));
        assertEquals("reconcile.redrive.too.late", fx.reconcileClassOf(id));
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());
    }

    /**
     * OR-105 (b): the age bound is decided from a clock read BEFORE the host is observed, and a slow observation can carry
     * the request past its window. The walk to RUNNING re-checks the bound under the scope lock, so nothing is restarted or
     * re-applied past the window.
     */
    @Test
    void aReDriveWhoseWindowClosesDuringASlowObservationGivesUpInsteadOfStarting() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        reconciler.enqueueIndeterminate(fx.tenant);
        host.onStatus = r -> {
            host.onStatus = null;
            fx.clock.advance(Duration.ofMinutes(61));   // the probe took longer than max-redrive-age
        };

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals("reconcile.redrive.too.late", fx.reconcileClassOf(id));
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id), "never walked to RUNNING");
        assertEquals(0, ledgerRow(id).redrives(), "nothing was spent");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());
    }

    /** OR-105 (b): the count bound too, re-read under the lock: a re-drive spent between the decision and the walk. */
    @Test
    void aReDriveSpentDuringASlowObservationLeavesNothingForTheStaleDecision() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        reconciler.enqueueIndeterminate(fx.tenant);
        host.onStatus = r -> {
            host.onStatus = null;
            fx.ledger.countRedrive(fx.tenant, id, fx.clock.instant());   // another tick spent the only re-drive
        };

        assertEquals(Result.GAVE_UP, reconciler.reconcileOne(fx.tenant, id));

        assertEquals("reconcile.redrive.exhausted", fx.reconcileClassOf(id));
        assertEquals(1, ledgerRow(id).redrives(), "the stale decision spent nothing more");
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());
    }

    /** OR-105 (b) at the seam: the walk itself refuses past either bound, whatever the caller decided. */
    @Test
    void beginRedriveRefusesPastTheBoundsUnderTheLock() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        reconcilerOver(G8);
        fx.ledger.ensureRow(fx.tenant, id, fx.clock.instant());
        fx.clock.advance(Duration.ofMinutes(61));
        assertEquals(OutcomeService.RedriveStart.TOO_LATE,
                fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS));
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));

        fx.clock.advance(Duration.ofMinutes(-61));
        fx.ledger.countRedrive(fx.tenant, id, fx.clock.instant());
        assertEquals(OutcomeService.RedriveStart.EXHAUSTED,
                fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS));
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));
    }

    /** REVIEW R-B: a re-drive that outlives the claim lease keeps its bookkeeping; a second tick must not close it. */
    @Test
    void reviewAReDriveInFlightPastTheClaimLeaseIsNotDroppedByASecondTick() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        reconciler.enqueueIndeterminate(fx.tenant);
        // One tick owns the request and walked it to RUNNING; a gated re-drive can outlive the 2-minute claim lease
        // (the gate deadline defaults to 3 minutes).
        assertEquals(OutcomeService.RedriveStart.STARTED, fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS));
        fx.clock.advance(Duration.ofMinutes(3));

        reconciler.runDue(fx.tenant);   // a second tick (dispatched because another request of the tenant is due)

        assertEquals(RequestState.RUNNING, fx.stateOf(id));
        assertNull(ledgerRow(id).gaveUpAt(), "the in-flight re-drive's bookkeeping must stay open");
        // The re-drive is then cut off again: it must be inspected again, never silently stranded.
        fx.outcomes.land(fx.tenant, id, RequestState.APPLY_INDETERMINATE);
        fx.clock.advance(Duration.ofMinutes(10));
        assertTrue(reconciler.hasDue(fx.tenant), "a re-drive cut off again is inspected again (and alerts on give-up)");
    }

    @Test
    void theRetryBoundSurvivesARestart() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        reconcilerOver(G8);
        mem.failStatusWith("ssh.connect.timeout");
        for (int i = 0; i < 3; i++) {
            // a "restarted process": a brand-new reconciler (and engine) every time; nothing is held in memory
            IndeterminateReconciler fresh = reconcilerOver(G8);
            assertEquals(Result.RETRY_SCHEDULED, fresh.reconcileOne(fx.tenant, id));
            fx.clock.advance(Duration.ofMinutes(10));
        }
        assertEquals(3, ledgerRow(id).inspections(), "the counters are persisted, not in memory");

        assertEquals(Result.RETRY_SCHEDULED, reconcilerOver(G8).reconcileOne(fx.tenant, id));
        fx.clock.advance(Duration.ofMinutes(10));
        assertEquals(Result.GAVE_UP, reconcilerOver(G8).reconcileOne(fx.tenant, id), "the fifth failure, across five processes");
    }

    // ================================================================================================
    // Locks, races, and who may move what
    // ================================================================================================

    /** M-4: no database lock is held while the host is observed; an emergency stop is admitted DURING a slow probe. */
    @Test
    void noLockIsHeldWhileTheHostIsObserved() throws Exception {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        boolean[] admitted = {false};
        host.onStatus = r -> {
            host.onStatus = null;
            CompletableFuture<Object> stop = CompletableFuture.supplyAsync(() -> fx.admission.admit(fx.stop(),
                    com.solace.sm.dp.apply.engine.GenerationBinding.UNCHECKED));
            try {
                stop.get(10, TimeUnit.SECONDS);
                admitted[0] = true;
            } catch (TimeoutException e) {
                // the scope row is locked across the host read: the stop is queued behind a probe
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        reconciler.reconcileOne(fx.tenant, id);

        assertTrue(admitted[0], "the operator's emergency stop must be admitted while the host is being observed");
    }

    @Test
    void aLaterRequestOnTheScopeForbidsAnyWriteButStillAllowsConfirm() {
        // (a) a re-drive is refused
        ApplyId prior = priorContentAfterACrash(Strategy.RESTART_ALL);
        fx.engine(host, (p, g) -> G8);
        assertInstanceOf(com.solace.sm.dp.apply.engine.AdmissionResult.Admitted.class, fx.executor.admit(fx.stop()),
                "a stop is admitted while an apply is indeterminate");
        assertEquals(Result.GAVE_UP, fx.reconciler(PROPS).reconcileOne(fx.tenant, prior));
        assertEquals("reconcile.superseded.by.later.request", fx.reconcileClassOf(prior));
        assertEquals(0, mem.rematerializeCalls());
        assertEquals(0, mem.restarts());

        // (b) a confirmation is a read, and is still allowed
        ReconcilerFixture other = new ReconcilerFixture();
        InMemoryRuntimeAdapter mem2 = ReconcilerFixture.host(other.placement, "replica-1");
        Cutting host2 = new Cutting(mem2);
        host2.cutStatusAfterRestart = true;
        other.engine(host2, (p, g) -> G8);
        other.executor.execute(other.apply(8, Strategy.ROLLING_NO_VALIDATION));
        host2.cutStatusAfterRestart = false;
        ApplyId confirmable = other.onlyOpenRequest();
        assertInstanceOf(com.solace.sm.dp.apply.engine.AdmissionResult.Admitted.class, other.executor.admit(other.stop()));
        assertEquals(Result.CONFIRMED, other.reconciler(PROPS).reconcileOne(other.tenant, confirmable));
    }

    /** The stop executed at the adapter after the decision: the re-drive meets the stopped replica by OBSERVATION. */
    @Test
    void aStopAdmittedDuringAReDriveEndsItByObservation() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.beforeRematerialize((m, r) -> m.setRunning(r, false));

        assertEquals(Result.REDRIVEN, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(RequestState.CONFIG_DIVERGED, fx.stateOf(id), "design 3.3: the apply is not cancelled in place");
        assertEquals(0, mem.restarts(), "nothing was started over a replica an operator stopped");
        AttemptRow lifecycle = latestAttempt(id, Step.LIFECYCLE);
        assertEquals("lifecycle.replica.not.running", lifecycle.errorClass().token());
    }

    @Test
    void operatorResolutionOfARunningReDriveIsRefused() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        reconcilerOver(G8);
        assertEquals(OutcomeService.RedriveStart.STARTED, fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> fx.outcomes.resolveIndeterminate(fx.tenant, id, operatorResolution(G8.hash())));

        assertTrue(e.getMessage().contains("RUNNING"), "refused cleanly, naming the state: " + e.getMessage());
        assertEquals(RequestState.RUNNING, fx.stateOf(id));
    }

    @Test
    void aReconcilerLosingTheRaceToAnOperatorResolutionDropsItsBookkeeping() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        int writes = mem.materializeCalls();
        host.onStatus = r -> {
            host.onStatus = null;
            fx.outcomes.resolveIndeterminate(fx.tenant, id, operatorResolution(G8.hash()));
        };

        assertEquals(Result.DROPPED, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id));
        assertEquals("operator-1", ApplyEngineHarness.jdbc().queryForObject(
                "SELECT resolved_by FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", fx.tenant.value()).addValue("a", id.value()), String.class),
                "the operator's resolution stands");
        assertNotNull(ledgerRow(id).gaveUpAt());
        assertEquals("resolved.by.operator", ledgerRow(id).lastClass());
        assertEquals(writes, mem.materializeCalls());
        assertEquals(0, mem.rematerializeCalls(), "no host call was made after the loss");
    }

    @Test
    void aReconcilerFindingTheRequestAlreadyResolvedMakesNoHostCallAtAll() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        fx.outcomes.resolveIndeterminate(fx.tenant, id, operatorResolution(G8.hash()));
        int polls = mem.statusCalls();

        assertEquals(Result.DROPPED, reconciler.reconcileOne(fx.tenant, id));

        assertEquals(polls, mem.statusCalls(), "MINOR 10: no host call");
        assertEquals("resolved.by.operator", ledgerRow(id).lastClass());
    }

    @Test
    void onlyTheReconcilerMovesIndeterminateToRunningAndOnlyFromIndeterminate() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        reconcilerOver(G8);

        assertThrows(IllegalStateException.class, () -> fx.outcomes.start(fx.tenant, id), "start() is REQUESTED -> RUNNING only");
        assertThrows(IllegalStateException.class, () -> fx.outcomes.land(fx.tenant, id, RequestState.RUNNING),
                "land() never walks to RUNNING");
        assertEquals(OutcomeService.RedriveStart.CHANGED, fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id) - 1, BOUNDS),
                "a request that is not the one observed is not re-opened");
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));

        assertEquals(OutcomeService.RedriveStart.STARTED, fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS));
        assertEquals(OutcomeService.RedriveStart.NOT_INDETERMINATE, fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS),
                "and only from APPLY_INDETERMINATE: a RUNNING request is not re-opened again");
        fx.outcomes.land(fx.tenant, id, RequestState.APPLIED);
        assertEquals(OutcomeService.RedriveStart.NOT_INDETERMINATE, fx.outcomes.beginRedrive(fx.tenant, id, fx.stampOf(id), BOUNDS),
                "nor a terminal one");
    }

    // ================================================================================================
    // The alert, and what it may carry
    // ================================================================================================

    @Test
    void aGiveUpIsObservableThroughTheJournalRead() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        mem.competingWrite(r1, FOREIGN);
        reconcilerOver(G8).reconcileOne(fx.tenant, id);

        var observed = new JdbcApplyObservationRepository(ApplyEngineHarness.jdbc())
                .observe(fx.tenant, fx.instance, id, null, 10);

        assertEquals("reconcile.foreign.content", observed.get(0).reconcileClass());
    }

    @Test
    void noLogLineClassifierOrEventCarriesFileContentOrAnUnkeyedHash() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        mem.competingWrite(r1, FOREIGN);
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        LogCapture.Captured<Result> run = LogCapture.during(() -> reconciler.reconcileOne(fx.tenant, id));

        assertEquals(Result.GAVE_UP, run.value());
        String everything = run.text() + fx.events + fx.reconcileClassOf(id) + ledgerRow(id).lastClass();
        assertFalse(everything.contains("sha256:"), "no hash, keyed or not, in a log line, event or classifier");
        assertFalse(everything.contains("generation: 8"), "and no file content");
        assertTrue(run.text().contains(id.value().toString()) || run.lines().isEmpty() || run.text().contains("reconcile."),
                "ids and the classifier are what the line carries");
    }

    @Test
    void anOperatorResolutionStillWorksAfterTheReconcilerGaveUp() {
        ApplyId id = priorContentAfterACrash(Strategy.RESTART_ALL);
        mem.competingWrite(r1, FOREIGN);
        reconcilerOver(G8).reconcileOne(fx.tenant, id);
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));

        fx.outcomes.resolveIndeterminate(fx.tenant, id, operatorResolution(FOREIGN));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id), "the human exit is unchanged");
    }

    // ================================================================================================
    // Startup, enumeration, tenancy
    // ================================================================================================

    @Test
    void startupRecoveryEnqueuesIndeterminateRowsAndDoesNotTouchAHost() {
        fx.ledgerWitnessesLandings = false;   // the landing now writes the row; startup covers one it did not witness
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        mem.failStatusWith("ssh.connect.timeout");   // the host is unreachable at boot
        int polls = mem.statusCalls();

        assertEquals(1, reconciler.enqueueIndeterminate(fx.tenant));

        assertEquals(polls, mem.statusCalls(), "boot never waits on a host");
        assertEquals(fx.clock.instant(), ledgerRow(id).nextAttemptAt(), "due immediately");
        assertEquals(0, ledgerRow(id).inspections());
        assertEquals(0, reconciler.enqueueIndeterminate(fx.tenant), "idempotent: nothing new to enqueue");
    }

    /** Plan 007 Task 4b's never-written case, with the new expectation. */
    @Test
    void aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminateIsEnumeratedAndThenReconciled() {
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        TenantEnumerationRepository tenants = new TenantEnumerationRepository(ApplyEngineHarness.jdbc());
        assertTrue(tenants.tenantsWithNonTerminalWork().contains(fx.tenant), "OR-44's list yields the tenant");

        assertEquals(1, reconciler.runDue(fx.tenant), "the poll handles the due reconciliation");

        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id));
        assertFalse(tenants.tenantsWithNonTerminalWork().contains(fx.tenant), "and then it has no open work");
    }

    @Test
    void thePollReconcilesOnlyTheTenantItWasGiven() {
        ApplyId mine = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);

        ReconcilerFixture other = new ReconcilerFixture();
        InMemoryRuntimeAdapter mem2 = ReconcilerFixture.host(other.placement, "replica-1");
        Cutting host2 = new Cutting(mem2);
        host2.cutStatusAfterRestart = true;
        other.engine(host2, (p, g) -> G8);
        other.executor.execute(other.apply(8, Strategy.ROLLING_NO_VALIDATION));
        ApplyId theirs = other.onlyOpenRequest();

        assertEquals(1, reconciler.runDue(fx.tenant));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(mine));
        assertEquals(RequestState.APPLY_INDETERMINATE,
                ApplyEngineHarness.requests().find(other.tenant, theirs).orElseThrow().state(),
                "I5: another tenant's request is not this tenant's work");
    }

    @Test
    void aRunDueTickAlsoPicksUpAnIndeterminateRequestNobodyEnqueued() {
        fx.ledgerWitnessesLandings = false;   // a landing the ledger did not witness (an older release, a lost row)
        ApplyId id = indeterminateAfterRestart(Strategy.ROLLING_NO_VALIDATION);
        IndeterminateReconciler reconciler = reconcilerOver(G8);
        assertTrue(fx.ledger.find(fx.tenant, id).isEmpty(), "no landing row, so the tick has to find it");

        assertEquals(1, reconciler.runDue(fx.tenant));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, fx.stateOf(id));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /**
     * An adapter that behaves as the in-memory host and can be cut: a process death inside or just before
     * {@code materializeConfig}, status that goes unreachable once the restart verb has run, a health signal that
     * vanishes after it, and a hook on every status read for races.
     */
    private static final class Cutting implements RuntimeAdapter {

        final InMemoryRuntimeAdapter mem;
        volatile boolean crashBeforeMaterialize;
        volatile Consumer<TargetReplica> crashAfterMaterialize;
        volatile boolean cutStatusAfterRestart;
        volatile HealthSignal healthAfterRestart;
        volatile Consumer<TargetReplica> onStatus;
        private volatile boolean restarted;

        Cutting(InMemoryRuntimeAdapter mem) {
            this.mem = mem;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            return mem.replicas(p);
        }

        @Override
        public PreflightResult prepare(PlacementRef p, long generation) {
            return mem.prepare(p, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica r, long generation, RenderedConfig c) {
            if (crashBeforeMaterialize) {
                throw new RuntimeException("simulated process death before the bytes landed");
            }
            MaterializeResult result = mem.materializeConfig(r, generation, c);
            Consumer<TargetReplica> after = crashAfterMaterialize;
            if (after != null) {
                after.accept(r);
                throw new RuntimeException("simulated process death after the write");
            }
            return result;
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica r, long generation, RenderedConfig c,
                String expectedCurrentHash) {
            return mem.rematerializeIfCurrent(r, generation, c, expectedCurrentHash);
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            LifecycleResult result = mem.startOrUpdate(r, generation);
            restarted = true;
            if (healthAfterRestart != null) {
                mem.healthOverride(healthAfterRestart);
            }
            return result;
        }

        @Override
        public LifecycleResult stop(TargetReplica r) {
            return mem.stop(r);
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            Consumer<TargetReplica> hook = onStatus;
            if (hook != null) {
                hook.accept(r);
            }
            if (cutStatusAfterRestart && restarted) {
                throw new RuntimeAdapterException(new ErrorClass("ssh.connect.timeout"));
            }
            return mem.status(r);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef p, long generation, String intendedHash) {
            return mem.assessRollback(p, generation, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef p, long generation, String targetHash) {
            return mem.restore(p, generation, targetHash);
        }
    }
}
