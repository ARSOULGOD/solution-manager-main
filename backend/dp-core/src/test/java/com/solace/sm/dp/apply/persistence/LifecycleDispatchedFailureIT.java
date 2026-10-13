package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OR-146 (1), the rule for a LIFECYCLE adapter exception: an exception raised <b>after</b> the restart was dispatched
 * ({@link RuntimeAdapterException#afterDispatch()}: the agent adapter's wait ended with no terminal answer) means SM
 * cannot know whether the replica restarted, so the request lands {@code APPLY_INDETERMINATE} and the reconciler
 * resolves it from a read. An exception with nothing dispatched, or a definitive answer from the runtime, stays a
 * terminal {@code CONFIG_DIVERGED}.
 */
@Testcontainers(disabledWithoutDocker = true)
class LifecycleDispatchedFailureIT {

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    private TenantId tenant;
    private ScopeKey scope;
    private PlacementRef placement;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        PlacementId placementId = new PlacementId(UUID.randomUUID());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        scope = InventoryFixtures.registered(new ScopeKey(tenant, instance, placementId));
        placement = new PlacementRef(tenant, instance, placementId);
    }

    private RequestState landWith(Strategy strategy, Supplier<RuntimeAdapterException> failure) {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override
            public List<TargetReplica> replicas(PlacementRef p) {
                return delegate.replicas(p);
            }

            @Override
            public PreflightResult prepare(PlacementRef p, long generation) {
                return delegate.prepare(p, generation);
            }

            @Override
            public MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
                return delegate.materializeConfig(r, g, c);
            }

            @Override
            public MaterializeResult rematerializeIfCurrent(TargetReplica r, long g, RenderedConfig c, String expectedCurrentHash) {
                return delegate.rematerializeIfCurrent(r, g, c, expectedCurrentHash);
            }

            @Override
            public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
                throw failure.get();
            }

            @Override
            public LifecycleResult stop(TargetReplica r) {
                return delegate.stop(r);
            }

            @Override
            public RuntimeStatus status(TargetReplica r) {
                return delegate.status(r);
            }

            @Override
            public RollbackAssessment assessRollback(PlacementRef p, long g, String intendedHash) {
                return delegate.assessRollback(p, g, intendedHash);
            }

            @Override
            public RestoreResult restore(PlacementRef p, long g, String targetHash) {
                return delegate.restore(p, g, targetHash);
            }
        };
        ApplyExecutor executor = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7);
        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class,
                executor.execute(ApplyEngineHarness.rollingApply(scope, 7, strategy)));
        assertEquals(landed.state(), ApplyEngineHarness.requests().find(tenant, landed.applyId()).orElseThrow().state());
        return landed.state();
    }

    @Test
    void aFailureAfterTheRestartWasDispatchedLandsIndeterminateUnderEveryRestartingStrategy() {
        for (Strategy strategy : new Strategy[] {Strategy.ROLLING_HEALTH_GATED, Strategy.ROLLING_NO_VALIDATION,
                Strategy.RESTART_ALL}) {
            setUp();
            assertEquals(RequestState.APPLY_INDETERMINATE, landWith(strategy,
                    () -> RuntimeAdapterException.afterDispatch(new ErrorClass("agent.session.lost"))), strategy.toString());
        }
    }

    @Test
    void aFailureWithNothingDispatchedStaysTerminalConfigDiverged() {
        assertEquals(RequestState.CONFIG_DIVERGED, landWith(Strategy.ROLLING_HEALTH_GATED,
                () -> new RuntimeAdapterException(new ErrorClass("agent.session.absent"))));
    }
}
