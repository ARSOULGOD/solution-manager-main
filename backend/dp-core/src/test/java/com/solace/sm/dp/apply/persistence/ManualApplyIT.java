package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The complete single-placement {@code manual} slice, over the real {@code sm_data} journal.
 *
 * <p>Drives {@link InMemoryRuntimeAdapter} rather than the Docker one: the failure states this class
 * needs — a partial materialize, a competing write, a pre-image that moves mid-flight — are not
 * things a real daemon reproduces on demand, and the Docker adapter has its own ITs.
 */
@Testcontainers(disabledWithoutDocker = true)
class ManualApplyIT {

    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;
    private InMemoryRuntimeAdapter adapter;
    private ApplyExecutor executor;

    private static final RenderedConfig GENERATION_7 =
            RenderedConfig.of("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        instance = new InstanceId(UUID.randomUUID());
        placementId = new PlacementId(UUID.randomUUID());
        scope = new ScopeKey(tenant, instance, placementId);
        placement = new PlacementRef(tenant, instance, placementId);
        adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        executor = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7);
    }

    private ApplyCommand manualApply(ScopeKey s, long generation) {
        return new ApplyCommand(s, Operation.APPLY, generation, Strategy.MANUAL, null, "operator-1");
    }

    private List<StepIntentRow> intentsFor(ApplyId id, Step step) {
        return ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                .filter(i -> i.step() == step).toList();
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    // ---- the happy path ------------------------------------------------------------------------

    @Test
    void aManualApplyStagesEveryReplicaAndTerminatesInStaged() {
        ApplyOutcome outcome = executor.execute(manualApply(scope, 7));

        ApplyId id = assertInstanceOf(ApplyOutcome.Landed.class, outcome).applyId();
        assertEquals(RequestState.STAGED, stateOf(id));
        assertNull(((ApplyOutcome.Landed) outcome).errorClass());
        // sorted() on an enum orders by ORDINAL, and PREFLIGHT precedes MATERIALIZE in Step.
        assertEquals(List.of(Step.PREFLIGHT, Step.MATERIALIZE, Step.MATERIALIZE),
                ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                        .map(StepIntentRow::step).sorted().toList());
        assertEquals(0, adapter.restarts(), "manual means the operator restarts, and SM observes");
    }

    @Test
    void theIntentBindsTheHashSoTheJournalNamesWhatWasIntended() {
        ApplyId id = ((ApplyOutcome.Landed) executor.execute(manualApply(scope, 7))).applyId();
        for (StepIntentRow intent : intentsFor(id, Step.MATERIALIZE)) {
            assertEquals(GENERATION_7.hash(), intent.configHash());
        }
        assertNull(intentsFor(id, Step.PREFLIGHT).get(0).configHash(),
                "hash_on_config_steps: PREFLIGHT is hashless");
    }

    @Test
    void admissionRefusalIsReportedRatherThanThrown() {
        executor.execute(manualApply(scope, 7));                  // leaves a STAGED row
        InMemoryRuntimeAdapter second = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        ApplyOutcome outcome = ApplyEngineHarness.executor(second, (p, g) -> GENERATION_7)
                .execute(manualApply(scope, 7));                  // same generation, fenced
        assertInstanceOf(ApplyOutcome.NotAdmitted.class, outcome);
    }

    // ---- the two pre-admission refusals ---------------------------------------------------------

    /**
     * {@code SCHEDULED} is the strategy plan 005 never opens: it needs a scheduler, and there is no
     * application class to host one. Task 9 widened the supported set to the three restart
     * strategies, so this is the only member left to assert the refusal with — which is the point.
     * A refusal test aimed at a strategy that later ships passes for the wrong reason.
     */
    @Test
    void anUnsupportedStrategyIsRefusedBeforeAdmissionAndPersistsNothing() {
        ApplyCommand scheduled = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.SCHEDULED,
                null, "operator-1");

        ApplyOutcome.StrategyUnsupported refused = assertInstanceOf(
                ApplyOutcome.StrategyUnsupported.class, executor.execute(scheduled));
        assertEquals(Strategy.SCHEDULED, refused.strategy());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty(),
                "nothing may be persisted for a request this build was never going to run");
        assertInstanceOf(ApplyOutcome.Landed.class, executor.execute(manualApply(scope, 7)),
                "…and the scope is therefore still admissible");
    }

    /**
     * A restart REQUEST is a different slice — no generation, so no {@code MATERIALIZE} step — and
     * plan 005 does not open it. Refused on the operation, not on the strategy: the strategy here
     * is one the executor fully supports, and a refusal naming the wrong reason would send an
     * operator to change the wrong thing.
     */
    @Test
    void aRestartRequestIsRefusedOnItsOperationAndPersistsNothing() {
        ApplyCommand restart = new ApplyCommand(scope, Operation.RESTART, null,
                Strategy.ROLLING_HEALTH_GATED, null, "operator-1");

        ApplyOutcome.OperationUnsupported refused = assertInstanceOf(
                ApplyOutcome.OperationUnsupported.class, executor.execute(restart));
        assertEquals(Operation.RESTART, refused.operation());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty(), "no apply row");
    }

    /** BE-D23: the journal cannot hold two placements' placement-scoped steps under one apply_id. */
    @Test
    void anInstanceWideRequestIsRefusedBeforeAdmissionAndPersistsNothing() {
        ScopeKey instanceWide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);

        ApplyOutcome.ScopeUnsupported refused = assertInstanceOf(ApplyOutcome.ScopeUnsupported.class,
                executor.execute(manualApply(instanceWide, 7)));
        assertEquals(PlacementId.INSTANCE_WIDE, refused.scope().placement());

        // No apply row, no intent, no attempt, no adapter call -- all four, because "refused" must
        // mean the engine did not start rather than that it stopped early.
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty(), "no apply row");
        assertEquals(0, ApplyEngineHarness.jdbc().queryForObject(
                "SELECT count(*) FROM sm_data.apply_step_intent WHERE tenant_id = :t",
                java.util.Map.of("t", tenant.value()), Integer.class), "no intent");
        assertEquals(0, ApplyEngineHarness.jdbc().queryForObject(
                "SELECT count(*) FROM sm_data.apply_attempt WHERE tenant_id = :t",
                java.util.Map.of("t", tenant.value()), Integer.class), "no attempt");
        assertEquals(0, adapter.prepareCalls() + adapter.materializeCalls() + adapter.restarts(),
                "no adapter call");

        assertInstanceOf(ApplyOutcome.Landed.class, executor.execute(manualApply(scope, 7)),
                "…and the scope is untouched");
    }

    // ---- pre-flight and render failures ---------------------------------------------------------

    @Test
    void aRefusedPreflightLandsFailedNoEffectAndWritesNoMaterializeIntent() {
        adapter.fault(InMemoryRuntimeAdapter.Fault.PREFLIGHT_REFUSED);
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_NO_EFFECT, stateOf(landed.applyId()));
        assertEquals(List.of(Step.PREFLIGHT),
                ApplyEngineHarness.intents().forRequest(tenant, landed.applyId()).stream()
                        .map(StepIntentRow::step).toList());
        assertEquals("fake.preflight.refused", landed.errorClass().token());
    }

    /**
     * A renderer that throws would leave the request RUNNING and scope-blocking unless something
     * routes it. Rendering runs inside PREFLIGHT and before the adapter call, so FAILED_NO_EFFECT is
     * provable here: no MATERIALIZE intent exists and the runtime was asked nothing.
     */
    @Test
    void aFailedRenderLandsFailedNoEffectWithNoMaterializeIntentAndNoAdapterCall() {
        RenderedConfigSource exploding = (p, g) -> {
            throw new IllegalStateException("cannot read /etc/sm/render/SENTINELRENDER.yml");
        };
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(adapter, exploding).execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_NO_EFFECT, stateOf(landed.applyId()));
        assertEquals(List.of(Step.PREFLIGHT),
                ApplyEngineHarness.intents().forRequest(tenant, landed.applyId()).stream()
                        .map(StepIntentRow::step).toList());
        assertEquals(0, adapter.prepareCalls(),
                "rendering precedes the adapter call, so a render failure asks the runtime nothing");
        assertEquals(0, adapter.materializeCalls());
        assertEquals("render.failed", landed.errorClass().token());

        // The classifier is PERSISTED, not merely returned.
        StepIntentRow preflight = intentsFor(landed.applyId(), Step.PREFLIGHT).get(0);
        AttemptRow attempt = ApplyEngineHarness.attempts()
                .latest(tenant, preflight.intentId()).orElseThrow();
        assertEquals(AttemptOutcome.FAILED, attempt.outcome());
        assertEquals("render.failed", attempt.errorClass().token());
        assertNull(attempt.preBaseline(), "baseline_only_for_lifecycle: PREFLIGHT carries none");
    }

    @Test
    void aFailedRenderLeavesTheScopeAdmissibleForTheNextApply() {
        ApplyEngineHarness.executor(adapter, (p, g) -> {
            throw new IllegalStateException("x");
        }).execute(manualApply(scope, 7));

        ApplyOutcome retry = executor.execute(manualApply(scope, 7));
        assertEquals(RequestState.STAGED,
                stateOf(assertInstanceOf(ApplyOutcome.Landed.class, retry).applyId()),
                "FAILED_NO_EFFECT unblocks the scope AND raises no watermark, so the SAME "
                        + "generation is admissible again");
    }

    /**
     * {@code I6} over all three surfaces at once: persistence, the returned outcome, and the ROOT
     * LOGGER — the last captured at TRACE and including throwable chains, because a caught
     * exception logged as {@code log.warn(msg, e)} lives in the throwable and not in the message
     * (plan 004 measured exactly that leak one layer in).
     *
     * <p>Production stays message-free by construction: {@link ApplyExecutor} never reads the
     * renderer's exception and never logs it. The mutation that proves this test fires is to log it.
     */
    @Test
    void aFailedRenderPutsNoRendererTextIntoPersistenceTheOutcomeOrTheLog() {
        RenderedConfigSource exploding = (p, g) -> {
            throw new IllegalStateException("cannot read /etc/sm/render/SENTINELRENDER.yml");
        };

        LogCapture.Captured<ApplyOutcome> captured = LogCapture.during(
                () -> ApplyEngineHarness.executor(adapter, exploding).execute(manualApply(scope, 7)));

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) captured.value();
        assertEquals(RequestState.FAILED_NO_EFFECT, stateOf(landed.applyId()));

        StepIntentRow preflight = intentsFor(landed.applyId(), Step.PREFLIGHT).get(0);
        AttemptRow attempt = ApplyEngineHarness.attempts()
                .latest(tenant, preflight.intentId()).orElseThrow();

        List<String> surfaces = List.of(
                "attempt.error_class=" + attempt.errorClass().token(),
                "attempt.observed_hash=" + attempt.observedHash(),
                "attempt.runtime_ref=" + attempt.runtimeRef(),
                "outcome.errorClass=" + landed.errorClass().token(),
                "outcome=" + landed,
                "rootLog=" + captured.text());

        for (String surface : surfaces) {
            assertFalse(surface.contains("SENTINELRENDER"), "renderer text leaked in " + surface);
            assertFalse(surface.contains("/etc/sm/render"), "renderer path leaked in " + surface);
            assertFalse(surface.contains("IllegalStateException"),
                    "the renderer's exception TYPE leaked in " + surface);
        }
    }

    // ---- BE-D22's exhaustion journal, and corrections A and B ------------------------------------

    /**
     * Correction A's headline case: replica A holds the new generation, replica B still holds the
     * old. They differ, and that mixture is precisely what a rollback is FOR — so it is Restorable,
     * and the journal shows assess → hash-bearing ROLLBACK → per-replica VERIFY.
     */
    @Test
    void oneReplicaAtTheMaterializeHashAndOneAtTheTargetRestoresToFailedRolledBack() {
        seedPriorGeneration();
        adapter.failMaterializeOn("replica-2");

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_ROLLED_BACK, stateOf(landed.applyId()),
                "classifying the ordinary partial write CONFIG_DIVERGED would refuse to roll back "
                        + "the very case rollback exists for");
        assertEquals(1, intentsFor(landed.applyId(), Step.ROLLBACK_ASSESS).size());
        assertNull(intentsFor(landed.applyId(), Step.ROLLBACK_ASSESS).get(0).configHash(),
                "hash_on_config_steps: the assessment restored nothing and carries no hash");
        assertEquals(1, intentsFor(landed.applyId(), Step.ROLLBACK).size());
        assertEquals(priorHash(), intentsFor(landed.applyId(), Step.ROLLBACK).get(0).configHash(),
                "the restore target is DURABLE before the destructive call -- this is what makes "
                        + "resume decidable instead of a guess");
        assertEquals(2, intentsFor(landed.applyId(), Step.VERIFY).size(), "one VERIFY per replica");
    }

    /** The durable per-replica record, which no return value may stand in for. */
    @Test
    void everyReplicasRestoredHashIsReadableFromItsOwnVerifyAttempt() {
        seedPriorGeneration();
        adapter.failMaterializeOn("replica-2");
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        for (StepIntentRow verify : intentsFor(landed.applyId(), Step.VERIFY)) {
            AttemptRow a = ApplyEngineHarness.attempts().latest(tenant, verify.intentId())
                    .orElseThrow();
            assertEquals(priorHash(), a.observedHash(),
                    "observed_hash is read-back EVIDENCE, and it is the ONLY per-replica record of "
                            + "the restore that survives the process");
        }
    }

    /** Correction A: a third, unknown hash prevents the restore and preserves the file. */
    @Test
    void anUnknownThirdHashPreventsTheRestoreAndPreservesTheValue() {
        seedPriorGeneration();
        adapter.failMaterializeOn("replica-2");
        RenderedConfig competing =
                RenderedConfig.of("hand: edited\n".getBytes(StandardCharsets.UTF_8));
        adapter.competingWrite(adapter.replicas().get(1), competing.hash());

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(landed.applyId()));
        assertEquals("rollback.state.unknown", landed.errorClass().token());
        assertTrue(intentsFor(landed.applyId(), Step.ROLLBACK).isEmpty(),
                "no ROLLBACK intent: the engine never even proposed to write");
        assertEquals(competing.hash(),
                adapter.status(adapter.replicas().get(1)).observedHash(),
                "B/D12: never silently auto-revert a human");
    }

    /** Correction B: the pre-image moves between assess and restore; restore writes nothing. */
    @Test
    void aPreImageAlteredBetweenAssessAndRestoreIsRefusedAndNothingIsWritten() {
        seedPriorGeneration();
        adapter.failMaterializeOn("replica-2");
        adapter.alterPreImageAfterAssess(7,
                RenderedConfig.of("tampered\n".getBytes(StandardCharsets.UTF_8)).hash());

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(landed.applyId()));
        assertEquals("rollback.preimage.altered", landed.errorClass().token());
        // The ROLLBACK intent DOES exist -- the engine proposed the write before learning the target
        // had moved -- and its attempt is FAILED. That pair is the honest record of "we intended to
        // restore to X, and X was gone by the time we looked".
        StepIntentRow rollback = intentsFor(landed.applyId(), Step.ROLLBACK).get(0);
        assertEquals(AttemptOutcome.FAILED,
                ApplyEngineHarness.attempts().latest(tenant, rollback.intentId()).orElseThrow()
                        .outcome());
        assertEquals(GENERATION_7.hash(),
                adapter.status(adapter.replicas().get(0)).observedHash(),
                "replica 1 still holds what materialize wrote: restore wrote nothing at all");
    }

    @Test
    void aFirstApplyThatFailsPartwayIsIndeterminateBecauseAbsenceHasNoHash() {
        adapter.failMaterializeOn("replica-2");                   // no prior generation at all

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(landed.applyId()));
        assertEquals("rollback.target.absent", landed.errorClass().token());
        assertTrue(intentsFor(landed.applyId(), Step.ROLLBACK).isEmpty());
    }

    /**
     * §2.2 rule 2's binding, enforced on the RETURN and not only on the request: the intent bound a
     * hash before the adapter was invoked, so an adapter that reports writing something else has
     * not materialized this generation — whatever it returned.
     *
     * <p>The replica is then holding a value this apply never intended, which is neither the
     * rollback target nor the intended hash, so {@code BE-D22} correction A refuses to overwrite it
     * and the request reaches {@code APPLY_INDETERMINATE}. What must never happen is {@code STAGED}.
     */
    @Test
    void aMaterializeReportingADifferentHashThanTheIntentBoundIsNeverASuccess() {
        seedPriorGeneration();
        RenderedConfig corrupt =
                RenderedConfig.of("corrupted: true\n".getBytes(StandardCharsets.UTF_8));
        adapter.reportWrongHashOn("replica-1", corrupt.hash());

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertNotEquals(RequestState.STAGED, stateOf(landed.applyId()),
                "a materialization whose written hash is not the one the intent bound may never "
                        + "let the request reach STAGED");
        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(landed.applyId()));

        StepIntentRow materialize = intentsFor(landed.applyId(), Step.MATERIALIZE).get(0);
        AttemptRow attempt = ApplyEngineHarness.attempts()
                .latest(tenant, materialize.intentId()).orElseThrow();
        assertEquals(AttemptOutcome.FAILED, attempt.outcome());
        assertEquals("materialize.hash.mismatch", attempt.errorClass().token());
        assertEquals(corrupt.hash(), attempt.observedHash(),
                "closed with ACTUAL read-back evidence, not with the hash the adapter claimed");

        assertEquals(ApplyEngineHarness.maxAttempts(),
                ApplyEngineHarness.attempts().count(tenant, materialize.intentId()),
                "routed through the existing bounded-retry path, not short-circuited");
    }

    /** A returned hash is a claim; the runtime read-back is the evidence that permits STAGED. */
    @Test
    void aMaterializeThatLiesAboutTheWrittenHashIsNeverASuccess() {
        seedPriorGeneration();
        RenderedConfig corrupt =
                RenderedConfig.of("corrupted-but-reported-correctly: true\n".getBytes(StandardCharsets.UTF_8));
        adapter.writeWrongButReportExpectedOn("replica-1", corrupt.hash());

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(landed.applyId()));
        StepIntentRow materialize = intentsFor(landed.applyId(), Step.MATERIALIZE).get(0);
        AttemptRow attempt = ApplyEngineHarness.attempts()
                .latest(tenant, materialize.intentId()).orElseThrow();
        assertEquals(AttemptOutcome.FAILED, attempt.outcome());
        assertEquals("materialize.hash.mismatch", attempt.errorClass().token());
        assertEquals(corrupt.hash(), attempt.observedHash(),
                "the engine must persist the runtime's read-back, not the adapter's claim");
        assertEquals(ApplyEngineHarness.maxAttempts(),
                ApplyEngineHarness.attempts().count(tenant, materialize.intentId()));
    }

    /** §2.2 rule 4: the cap is spent BEFORE the exhaustion path, and the DATABASE counts it. */
    @Test
    void everyAllowedAttemptIsSpentBeforeTheExhaustionPathIsTaken() {
        seedPriorGeneration();
        adapter.failMaterializeAlways();

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        StepIntentRow materialize = intentsFor(landed.applyId(), Step.MATERIALIZE).get(0);
        assertEquals(ApplyEngineHarness.maxAttempts(),
                ApplyEngineHarness.attempts().count(tenant, materialize.intentId()),
                "the bound is the database's count of attempts on this intent, not a field the "
                        + "engine keeps -- which is what makes it survive a crash");
        assertEquals(RequestState.FAILED_ROLLED_BACK, stateOf(landed.applyId()));
    }

    /** A prior generation, so generation 7 has something to roll back TO. */
    private void seedPriorGeneration() {
        for (TargetReplica r : adapter.replicas()) {
            adapter.materializeConfig(r, 6, priorConfig());
        }
    }

    private static RenderedConfig priorConfig() {
        return RenderedConfig.of("generation: 6\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String priorHash() {
        return priorConfig().hash();
    }
}
