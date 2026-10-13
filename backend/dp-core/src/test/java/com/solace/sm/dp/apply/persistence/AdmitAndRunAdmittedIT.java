package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 4 (engine half): {@code ApplyExecutor.admit}/{@code runAdmitted} as a genuine split of plan
 * 005's {@code execute()} — never a second engine, never a second {@code REQUESTED → RUNNING}
 * transition.
 *
 * <p>{@code execute()} itself is unchanged in every observable respect (it now composes
 * {@code admit} + {@code start} + {@code runAdmitted}), so the whole of the pre-existing suite —
 * {@code ManualApplyIT}, {@code RollingApplyIT}, {@code StopApplyIT}, {@code ResumeIT}, the Cache
 * walkthrough — is itself a regression proof for that composition and is deliberately left
 * untouched by this task.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdmitAndRunAdmittedIT {

    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;
    private InMemoryRuntimeAdapter adapter;
    private ApplyExecutor executor;

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        instance = new InstanceId(UUID.randomUUID());
        placementId = new PlacementId(UUID.randomUUID());
        scope = InventoryFixtures.registered(new ScopeKey(tenant, instance, placementId));
        placement = new PlacementRef(tenant, instance, placementId);
        adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        executor = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7);
    }

    private ApplyCommand manualApply(ScopeKey s, long generation) {
        return new ApplyCommand(s, Operation.APPLY, generation, Strategy.MANUAL, null, "operator-1");
    }

    // ---- admit(): the committed-but-unstarted half ----------------------------------------------

    @Test
    void admitPersistsARequestedRowAndNeverTouchesTheAdapter() {
        AdmissionResult result = executor.admit(manualApply(scope, 7));

        ApplyId id = assertInstanceOf(AdmissionResult.Admitted.class, result).id();
        assertEquals(RequestState.REQUESTED,
                ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state());
        assertEquals(0, adapter.prepareCalls(), "admit() must never call the adapter");
        assertEquals(0, adapter.materializeCalls());
    }

    @Test
    void admitRefusesARestartOperationAndPersistsNothing() {
        ApplyCommand restart = new ApplyCommand(scope, Operation.RESTART, null,
                Strategy.ROLLING_HEALTH_GATED, null, "operator-1");

        AdmissionResult.OperationUnsupported refused = assertInstanceOf(
                AdmissionResult.OperationUnsupported.class, executor.admit(restart));
        assertEquals(Operation.RESTART, refused.op());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty());
    }

    @Test
    void admitRefusesAnUnsupportedStrategyAndPersistsNothing() {
        ApplyCommand scheduled = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.SCHEDULED,
                null, "operator-1");

        AdmissionResult.StrategyUnsupported refused = assertInstanceOf(
                AdmissionResult.StrategyUnsupported.class, executor.admit(scheduled));
        assertEquals(Strategy.SCHEDULED, refused.s());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty());
    }

    @Test
    void admitRefusesAnInstanceWideApplyAndPersistsNothing() {
        ScopeKey instanceWide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);

        AdmissionResult.ScopeUnsupported refused = assertInstanceOf(
                AdmissionResult.ScopeUnsupported.class, executor.admit(manualApply(instanceWide, 7)));
        assertEquals(PlacementId.INSTANCE_WIDE, refused.scope().placement());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty());
    }

    @Test
    void admitReturnsNotAdmittedWhenTheScopeIsAlreadyBusy() {
        assertInstanceOf(AdmissionResult.Admitted.class, executor.admit(manualApply(scope, 7)));

        AdmissionResult.NotAdmitted refused = assertInstanceOf(AdmissionResult.NotAdmitted.class,
                executor.admit(manualApply(scope, 8)));
        assertEquals(com.solace.sm.dp.apply.engine.RefusalReason.SCOPE_BUSY, refused.reason());
    }

    // ---- runAdmitted(): entered on an ALREADY-RUNNING row, never calls start() -------------------

    @Test
    void runAdmittedRefusesARowThatIsStillRequested() {
        ApplyId id = ((AdmissionResult.Admitted) executor.admit(manualApply(scope, 7))).id();

        assertThrows(IllegalStateException.class, () -> executor.runAdmitted(tenant, id),
                "runAdmitted's one precondition is a row the claim already moved to RUNNING");
    }

    /**
     * The mutation this task exists to guard: if {@code runAdmitted} called {@code outcomes.start}
     * (as an earlier draft did), this test would fail, because {@code start()} refuses a row that is
     * not {@code REQUESTED} — and by the time {@code runAdmitted} is entered here, the row is already
     * {@code RUNNING} (this test performs the claim's transition directly, exactly as
     * {@code ApplyClaimRepository.claimNextAsStarted} does, without going through the engine).
     */
    @Test
    void runAdmittedNeverCallsStartBecauseTheClaimAlreadyDid() {
        ApplyId id = ((AdmissionResult.Admitted) executor.admit(manualApply(scope, 7))).id();

        int moved = ApplyEngineHarness.requests().transition(tenant, id, RequestState.REQUESTED,
                RequestState.RUNNING, null);
        assertEquals(1, moved, "the claim's own transition must have taken effect");

        ApplyOutcome outcome = executor.runAdmitted(tenant, id);

        assertEquals(RequestState.STAGED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state());
        assertNull(assertInstanceOf(ApplyOutcome.Landed.class, outcome).errorClass());
    }

    @Test
    void runAdmittedReconstructsTheCommandFromThePersistedRowAndDrivesTheSameStepLoop() {
        ApplyId id = ((AdmissionResult.Admitted) executor.admit(manualApply(scope, 7))).id();
        ApplyEngineHarness.requests().transition(tenant, id, RequestState.REQUESTED,
                RequestState.RUNNING, null);

        executor.runAdmitted(tenant, id);

        assertEquals(2, adapter.materializeCalls(), "both replicas of this placement were addressed");
        assertEquals(0, adapter.restarts(), "manual means the operator restarts, SM only observes");
    }

    @Test
    void executeComposesAdmitStartAndRunAdmittedForTheStopOperationToo() {
        executor.execute(manualApply(scope, 7));   // leaves a STAGED row -- irrelevant to the stop

        ApplyCommand stop = new ApplyCommand(scope, Operation.STOP, null, null, null, "operator-1");
        ApplyOutcome outcome = executor.execute(stop);

        assertEquals(RequestState.STOPPED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state());
    }
}
