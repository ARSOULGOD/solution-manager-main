package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.reconcile.ReconcileProperties;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter.Fault;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.worker.ApplyRecoveryStatus;
import com.solace.sm.dp.apply.worker.ApplyStartupRecovery;
import com.solace.sm.dp.apply.worker.ApplyWorker;
import com.solace.sm.dp.apply.worker.ApplyWorkerProperties;
import com.solace.sm.dp.apply.worker.ApplyWorkerStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.8: the two places the reconciler is hung on the existing worker, over the real engine and PostgreSQL.
 * Startup ENQUEUES an indeterminate request and returns - it touches no host, so boot cannot wait on one (MINOR 9) - and the
 * durable worker's poll RUNS the due reconciliation, with nobody calling it (the launcher IT proves the same over HTTP).
 */
@Testcontainers(disabledWithoutDocker = true)
class ReconcilerWiringIT {

    private static final RenderedConfig G8 = TestHashers.rendered("generation: 8\n");

    private ReconcilerFixture fx;
    private InMemoryRuntimeAdapter host;
    private MineOnly mine;
    private ApplyId id;

    @BeforeEach
    void cutOffApply() {
        fx = new ReconcilerFixture();
        host = ReconcilerFixture.host(fx.placement, "replica-1");
        host.fault(Fault.BASELINE_UNREADABLE);   // the verb is refused before it is issued: file written, nothing restarted
        mine = new MineOnly(host, fx.tenant);
        fx.engine(mine, (p, g) -> G8);
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(((com.solace.sm.dp.apply.engine.ApplyOutcome.Landed)
                fx.executor.execute(fx.apply(8, Strategy.ROLLING_NO_VALIDATION))).applyId()));
        id = fx.onlyOpenRequest();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (bean != null) {
            factory.registerSingleton("bean", bean);
        }
        return factory.getBeanProvider(type);
    }

    private static WorkerSingletonGuard guard() {
        var pg = SmDataPostgres.container();
        return new WorkerSingletonGuard(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @Test
    void startupEnqueuesAnIndeterminateRequestAndDoesNotTouchTheHost() {
        IndeterminateReconciler reconciler = fx.reconciler(ReconcileProperties.defaults());
        host.failStatusWith("ssh.connect.timeout");   // the host is unreachable while the process boots
        int polls = mine.statusCallsForMyTenant();
        WorkerSingletonGuard guard = guard();
        ApplyStartupRecovery recovery = new ApplyStartupRecovery(provider(ApplyExecutor.class, fx.executor),
                new TenantEnumerationRepository(ApplyEngineHarness.jdbc()), guard, new ApplyRecoveryStatus(),
                provider(IndeterminateReconciler.class, reconciler));

        try {
            Instant started = Instant.now();
            recovery.start();
            assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0,
                    "boot never waits on a host");
        } finally {
            recovery.stop();
            guard.release();
        }

        assertEquals(polls, mine.statusCallsForMyTenant(), "the sweep observed no host for this request");
        assertEquals(fx.clock.instant(), fx.ledger.find(fx.tenant, id).orElseThrow().nextAttemptAt(),
                "the request is handed to the reconciler, due now");
        assertEquals(RequestState.APPLY_INDETERMINATE, fx.stateOf(id));
    }

    @Test
    void theDurableWorkersPollRunsTheDueReconciliation() throws Exception {
        host.fault(Fault.NONE);   // the host has recovered
        IndeterminateReconciler reconciler = fx.reconciler(ReconcileProperties.defaults());
        WorkerSingletonGuard guard = guard();
        ApplyWorker worker = new ApplyWorker(provider(ApplyExecutor.class, fx.executor),
                new ApplyClaimRepository(ApplyEngineHarness.transactions(), ApplyEngineHarness.jdbc(),
                        ApplyEngineHarness.requests()),
                new TenantEnumerationRepository(ApplyEngineHarness.jdbc()),
                new ApplyWorkerProperties(2, Duration.ofMillis(100), Duration.ofSeconds(5)), guard,
                new ApplyWorkerStatus(), provider(IndeterminateReconciler.class, reconciler));
        try {
            worker.start();
            Instant deadline = Instant.now().plusSeconds(30);
            while (fx.stateOf(id) != RequestState.APPLIED && Instant.now().isBefore(deadline)) {
                Thread.sleep(100);
            }
        } finally {
            worker.stop();
        }

        assertEquals(RequestState.APPLIED, fx.stateOf(id), "the poll reconciled it: the restart was re-run and verified");
        assertEquals(1, host.restarts());
        assertEquals(0, host.rematerializeCalls());
    }

    /**
     * The shared test database also holds other ITs' open requests, and the worker and the sweep visit every tenant: this
     * adapter serves THIS fixture's tenant and refuses every other (a classified failure, which the reconciler files as a
     * failed inspection of someone else's request), so no leftover can touch the host under test, and counts its reads.
     */
    private static final class MineOnly implements com.solace.sm.dp.apply.runtime.RuntimeAdapter {

        private final InMemoryRuntimeAdapter mem;
        private final com.solace.sm.dp.apply.model.TenantId tenant;
        private final java.util.concurrent.atomic.AtomicInteger statusReads = new java.util.concurrent.atomic.AtomicInteger();

        MineOnly(InMemoryRuntimeAdapter mem, com.solace.sm.dp.apply.model.TenantId tenant) {
            this.mem = mem;
            this.tenant = tenant;
        }

        int statusCallsForMyTenant() {
            return statusReads.get();
        }

        private void mine(com.solace.sm.dp.apply.runtime.PlacementRef p) {
            if (!p.tenant().equals(tenant)) {
                throw new com.solace.sm.dp.apply.runtime.RuntimeAdapterException(
                        new com.solace.sm.dp.apply.model.ErrorClass("test.foreign.tenant"));
            }
        }

        @Override
        public java.util.List<com.solace.sm.dp.apply.runtime.TargetReplica> replicas(
                com.solace.sm.dp.apply.runtime.PlacementRef p) {
            mine(p);
            return mem.replicas(p);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.PreflightResult prepare(com.solace.sm.dp.apply.runtime.PlacementRef p,
                long generation) {
            mine(p);
            return mem.prepare(p, generation);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult materializeConfig(
                com.solace.sm.dp.apply.runtime.TargetReplica r, long generation, RenderedConfig c) {
            mine(r.placement());
            return mem.materializeConfig(r, generation, c);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult rematerializeIfCurrent(
                com.solace.sm.dp.apply.runtime.TargetReplica r, long generation, RenderedConfig c, String expected) {
            mine(r.placement());
            return mem.rematerializeIfCurrent(r, generation, c, expected);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult startOrUpdate(
                com.solace.sm.dp.apply.runtime.TargetReplica r, long generation) {
            mine(r.placement());
            return mem.startOrUpdate(r, generation);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult stop(com.solace.sm.dp.apply.runtime.TargetReplica r) {
            mine(r.placement());
            return mem.stop(r);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RuntimeStatus status(com.solace.sm.dp.apply.runtime.TargetReplica r) {
            mine(r.placement());
            statusReads.incrementAndGet();
            return mem.status(r);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RollbackAssessment assessRollback(
                com.solace.sm.dp.apply.runtime.PlacementRef p, long generation, String intendedHash) {
            mine(p);
            return mem.assessRollback(p, generation, intendedHash);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RestoreResult restore(com.solace.sm.dp.apply.runtime.PlacementRef p,
                long generation, String targetHash) {
            mine(p);
            return mem.restore(p, generation, targetHash);
        }
    }
}
