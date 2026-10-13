package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
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
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 4b's startup sweep, at the engine level: {@code ApplyExecutor.resumeRunning} — {@code RUNNING}
 * rows only, never a call to {@code start()}, no second state machine.
 *
 * <p>Crashes are simulated exactly as {@code ResumeIT} does: an adapter throws a plain
 * {@link RuntimeException} (not a {@link com.solace.sm.dp.apply.runtime.RuntimeAdapterException}),
 * which the step loop does not catch, leaving the intent committed, the attempt OPEN and the request
 * {@code RUNNING} — the state a real crash leaves, not one stubbed to suit the test.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResumeRunningIT {

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    private TenantId tenant;

    private ScopeKey freshScope() {
        return InventoryFixtures.registered(
                new ScopeKey(tenant, new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID())));
    }

    private void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    @Test
    void resumeRunningRecoversARunningRowLeftByACrashToATerminalState() {
        setUp();
        ScopeKey scope = freshScope();
        PlacementRef placement = new PlacementRef(scope.tenant(), scope.instance(), scope.placement());
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        CrashOnceAdapter crashing = new CrashOnceAdapter(adapter);
        ApplyCommand manualApply = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");

        assertThrows(RuntimeException.class,
                () -> ApplyEngineHarness.executor(crashing, (p, g) -> GENERATION_7).execute(manualApply),
                "the simulated crash must escape the step loop");
        // execute() already inserted+started one row before crashing; capture its id from the
        // journal, which is the only place a crash leaves anything to read.
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        assertEquals(RequestState.RUNNING, stateOf(id), "a crash mid-materialize leaves the row RUNNING");

        crashing.stopCrashing();
        List<ApplyOutcome> outcomes =
                ApplyEngineHarness.executor(crashing, (p, g) -> GENERATION_7).resumeRunning(tenant);

        assertEquals(1, outcomes.size());
        assertTrue(stateOf(id).isTerminal(), "the sweep must land the row on a terminal state");
    }

    @Test
    void resumeRunningLeavesARequestedRowForTheNextPollRatherThanTouchingIt() {
        setUp();
        ScopeKey scope = freshScope();
        ApplyCommand manualApply = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        AdmissionOutcome admitted = ApplyEngineHarness.admission().admit(manualApply);
        ApplyId id = ((AdmissionOutcome.Admitted) admitted).applyId();
        assertEquals(RequestState.REQUESTED, stateOf(id));

        PlacementRef placement = new PlacementRef(scope.tenant(), scope.instance(), scope.placement());
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        List<ApplyOutcome> outcomes =
                ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7).resumeRunning(tenant);

        assertTrue(outcomes.isEmpty(), "a REQUESTED row is the next poll's job, not the sweep's");
        assertEquals(RequestState.REQUESTED, stateOf(id));
    }

    /**
     * The mutation this task exists to guard, at the sweep's own entry point: if
     * {@code resumeRunning} called {@code outcomes.start} before handing the row to
     * {@code resumeOne} (as an earlier draft might), that call would throw — the row is already
     * {@code RUNNING}, not {@code REQUESTED} — and the row would be stranded to
     * {@code APPLY_INDETERMINATE} instead of being cleanly recovered. This test recovers a
     * non-crashing scenario (no open attempt at all) and asserts the CLEAN outcome, which a
     * spurious {@code start()} call would prevent.
     */
    @Test
    void resumeRunningNeverCallsStartOnARowWithNoOpenAttemptEither() {
        setUp();
        ScopeKey scope = freshScope();
        PlacementRef placement = new PlacementRef(scope.tenant(), scope.instance(), scope.placement());
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        ApplyCommand manualApply = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        AdmissionOutcome admitted = ApplyEngineHarness.admission().admit(manualApply);
        ApplyId id = ((AdmissionOutcome.Admitted) admitted).applyId();
        // Simulate the claim directly -- the row is RUNNING with NO intents at all, exactly the
        // state a worker that claimed the row and crashed before its first step would leave.
        ApplyEngineHarness.requests().transition(tenant, id, RequestState.REQUESTED,
                RequestState.RUNNING, null);

        List<ApplyOutcome> outcomes =
                ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7).resumeRunning(tenant);

        assertEquals(1, outcomes.size());
        RequestState landed =
                assertInstanceOf(ApplyOutcome.Landed.class, outcomes.get(0)).state();
        assertEquals(RequestState.STAGED, landed,
                "a clean recovery must reach STAGED; a spurious start() call would have thrown and "
                        + "stranded this on APPLY_INDETERMINATE instead");
    }

    @Test
    void resumeRunningIsIdempotentAcrossTwoConsecutiveCalls() {
        setUp();
        ScopeKey scope = freshScope();
        PlacementRef placement = new PlacementRef(scope.tenant(), scope.instance(), scope.placement());
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        ApplyCommand manualApply = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        ApplyId id = ((AdmissionOutcome.Admitted) ApplyEngineHarness.admission().admit(manualApply)).applyId();
        ApplyEngineHarness.requests().transition(tenant, id, RequestState.REQUESTED,
                RequestState.RUNNING, null);
        ApplyExecutor executor = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7);

        List<ApplyOutcome> first = executor.resumeRunning(tenant);
        assertEquals(1, first.size());
        assertTrue(stateOf(id).isTerminal());

        List<ApplyOutcome> second = executor.resumeRunning(tenant);
        assertTrue(second.isEmpty(), "nothing RUNNING remains -- a second sweep must be a no-op");
    }

    /** Isolation: one stranded row in a tenant must not stop the sweep from recovering another. */
    @Test
    void oneUnrecoverableRowDoesNotStopTheSweepFromRecoveringAnother() {
        setUp();
        ScopeKey recoverableScope = freshScope();
        ScopeKey poisonedScope = freshScope();
        PlacementRef recoverablePlacement = new PlacementRef(recoverableScope.tenant(),
                recoverableScope.instance(), recoverableScope.placement());
        PlacementRef poisonedPlacement = new PlacementRef(poisonedScope.tenant(),
                poisonedScope.instance(), poisonedScope.placement());

        InMemoryRuntimeAdapter recoverableAdapter =
                new InMemoryRuntimeAdapter(recoverablePlacement, true, "replica-1");
        AlwaysThrowingAdapter poisonedAdapter = new AlwaysThrowingAdapter(poisonedPlacement);

        ApplyId recoverableId = claimAsRunningWithNoIntents(recoverableScope);
        ApplyId poisonedId = claimAsRunningWithNoIntents(poisonedScope);

        RuntimeAdapter routing = new RoutingAdapter(
                Map.of(recoverablePlacement, recoverableAdapter, poisonedPlacement, poisonedAdapter));

        List<ApplyOutcome> outcomes =
                ApplyEngineHarness.executor(routing, (p, g) -> GENERATION_7).resumeRunning(tenant);

        assertEquals(2, outcomes.size());
        assertTrue(stateOf(recoverableId).isTerminal() && stateOf(recoverableId) == RequestState.STAGED,
                "the recoverable row must reach its ordinary landing despite the other's poison");
        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(poisonedId),
                "the unrecoverable row is stranded, not left to corrupt the sweep");
    }

    private ApplyId claimAsRunningWithNoIntents(ScopeKey scope) {
        ApplyCommand manualApply = new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        ApplyId id = ((AdmissionOutcome.Admitted) ApplyEngineHarness.admission().admit(manualApply)).applyId();
        ApplyEngineHarness.requests().transition(tenant, id, RequestState.REQUESTED,
                RequestState.RUNNING, null);
        return id;
    }

    /** Routes every call to whichever fake owns that call's placement. */
    private static final class RoutingAdapter implements RuntimeAdapter {
        private final Map<PlacementRef, RuntimeAdapter> byPlacement;

        RoutingAdapter(Map<PlacementRef, RuntimeAdapter> byPlacement) {
            this.byPlacement = byPlacement;
        }

        private RuntimeAdapter of(PlacementRef p) {
            return byPlacement.get(p);
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            return of(placement).replicas(placement);
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            return of(placement).prepare(placement, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica replica, long generation,
                RenderedConfig config) {
            return of(replica.placement()).materializeConfig(replica, generation, config);
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
                String expectedCurrentHash) {
            return of(replica.placement()).rematerializeIfCurrent(replica, generation, config, expectedCurrentHash);
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
            return of(replica.placement()).startOrUpdate(replica, generation);
        }

        @Override
        public LifecycleResult stop(TargetReplica replica) {
            return of(replica.placement()).stop(replica);
        }

        @Override
        public RuntimeStatus status(TargetReplica replica) {
            return of(replica.placement()).status(replica);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef placement, long generation,
                String intendedHash) {
            return of(placement).assessRollback(placement, generation, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
            return of(placement).restore(placement, generation, targetHash);
        }
    }

    /** A pre-flight that always refuses with a plain RuntimeException -- an unrecoverable replica. */
    private static final class AlwaysThrowingAdapter implements RuntimeAdapter {
        private final PlacementRef placement;

        AlwaysThrowingAdapter(PlacementRef placement) {
            this.placement = placement;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            return List.of(new TargetReplica(placement, new com.solace.sm.dp.apply.model.ReplicaRef(
                    "replica-1")));
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            throw new RuntimeException("simulated: this placement never comes back");
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica replica, long generation,
                RenderedConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
                String expectedCurrentHash) {
            throw new UnsupportedOperationException("not used by this test double");
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LifecycleResult stop(TargetReplica replica) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeStatus status(TargetReplica replica) {
            throw new RuntimeException("simulated: unreachable");
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef placement, long generation,
                String intendedHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
            throw new UnsupportedOperationException();
        }
    }

    /** Delegates to a real fake, but throws a plain RuntimeException on prepare() until told to stop. */
    private static final class CrashOnceAdapter implements RuntimeAdapter {
        private final InMemoryRuntimeAdapter delegate;
        private volatile boolean crash = true;

        CrashOnceAdapter(InMemoryRuntimeAdapter delegate) {
            this.delegate = delegate;
        }

        void stopCrashing() {
            crash = false;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            return delegate.replicas(placement);
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            return delegate.prepare(placement, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica replica, long generation,
                RenderedConfig config) {
            if (crash) {
                throw new RuntimeException("simulated crash mid-materialize");
            }
            return delegate.materializeConfig(replica, generation, config);
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
                String expectedCurrentHash) {
            return delegate.rematerializeIfCurrent(replica, generation, config, expectedCurrentHash);
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
            return delegate.startOrUpdate(replica, generation);
        }

        @Override
        public LifecycleResult stop(TargetReplica replica) {
            return delegate.stop(replica);
        }

        @Override
        public RuntimeStatus status(TargetReplica replica) {
            return delegate.status(replica);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef placement, long generation,
                String intendedHash) {
            return delegate.assessRollback(placement, generation, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
            return delegate.restore(placement, generation, targetHash);
        }
    }
}
