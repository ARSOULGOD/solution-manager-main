package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.worker.ApplyWorker;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * MINOR fix (post-review): {@code ApplyWorker.stop()} used to call
 * {@code WorkerSingletonGuard.release()} unconditionally, even when {@code awaitTermination} timed
 * out and {@code shutdownNow()} only interrupted the pool's threads — which does nothing at all
 * against a thread blocked inside a non-interruptible adapter call (SSH or Docker I/O in
 * production). This proves the fix: when the pool cannot be drained, {@code stop()} leaves the
 * singleton lock held rather than releasing it out from under a worker thread that may still be
 * driving an adapter call.
 *
 * <p>The adapter below models "does not respond to interruption" directly — it loops on a plain
 * {@link AtomicBoolean} flag rather than the thread's interrupted status, so {@code shutdownNow()}'s
 * interrupt cannot unblock it, exactly like a synchronous SSH or Docker API call would not.
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WorkerSingletonLockHeldOnUndrainedShutdownIT.UninterruptibleExecutorConfiguration.class)
class WorkerSingletonLockHeldOnUndrainedShutdownIT {

    private static final UUID TENANT = UUID.randomUUID();
    private static final PlacementRef PLACEMENT_REF = new PlacementRef(new TenantId(TENANT),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    /** Set once the worker has actually claimed the row and entered the blocking adapter call. */
    private static final CountDownLatch CLAIMED = new CountDownLatch(1);
    /** Set only by {@link #releaseTheBlockedThread()}, after this test's assertions are done. */
    private static final AtomicBoolean RELEASE_ADAPTER = new AtomicBoolean(false);

    private static TwoSchemaTestDatabase database;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        database = TwoSchemaTestDatabase.provisionWithOwnExecutor(registry);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "100ms");
        registry.add("sm.dp.apply.worker.pool-size", () -> "1");
        registry.add("sm.dp.apply.worker.shutdown-grace", () -> "300ms");
    }

    /** Tidy exit: let the blocked adapter thread finish, so the JVM has no daemon left spinning. */
    @AfterAll
    static void releaseTheBlockedThread() {
        RELEASE_ADAPTER.set(true);
    }

    @Autowired
    private ApplyExecutor executor;

    @Autowired
    private ApplyWorker worker;

    @Test
    void stopLeavesTheSingletonLockHeldWhenTheWorkerThreadIgnoresInterruption()
            throws InterruptedException {
        ScopeKey scope = PLACEMENT_REF.scope();
        DeclaredInstances.registered(database, scope);
        ApplyCommand command =
                new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null, "operator-1");
        executor.admit(command);

        assertTrue(CLAIMED.await(10, TimeUnit.SECONDS), "the worker never claimed the row");

        long before = System.nanoTime();
        worker.stop();
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        assertTrue(elapsedMs < 5000,
                "stop() must still return promptly even though the pool never drained: " + elapsedMs
                        + "ms");

        // The decisive proof: a second, wholly independent WorkerSingletonGuard against the SAME
        // database must still be refused -- the lock was never released, because the pool never
        // actually terminated.
        WorkerSingletonGuard second = new WorkerSingletonGuard(database.superuser);
        assertThrows(IllegalStateException.class, second::acquireOrRefuseToStart,
                "the singleton lock must still be held after an undrained stop()");
    }

    /**
     * A {@code materializeConfig} that ignores interruption entirely — unlike a
     * {@link CountDownLatch#await()}, this loop checks a plain flag rather than the thread's
     * interrupted status, which is what makes it immune to {@code shutdownNow()}, the property
     * under test.
     */
    private static final class UninterruptibleAdapter implements RuntimeAdapter {
        private final InMemoryRuntimeAdapter delegate;

        UninterruptibleAdapter(InMemoryRuntimeAdapter delegate) {
            this.delegate = delegate;
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
            CLAIMED.countDown();
            while (!RELEASE_ADAPTER.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // Deliberately NOT re-set and NOT propagated -- a real SSH/Docker call blocked
                    // in native I/O would not notice an interrupt either.
                }
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

    @TestConfiguration
    static class UninterruptibleExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            return new UninterruptibleAdapter(
                    new InMemoryRuntimeAdapter(PLACEMENT_REF, true, "replica-1"));
        }

        @Bean
        RenderedConfigSource renderedConfigSource() {
            return (placement, generation) -> GENERATION_7;
        }

        @Bean
        ApplyExecutor applyExecutor(com.solace.sm.dp.apply.engine.AdmissionService admissionService,
                com.solace.sm.dp.apply.engine.StepIntentService stepIntentService,
                com.solace.sm.dp.apply.engine.AttemptService attemptService,
                com.solace.sm.dp.apply.engine.OutcomeService outcomeService,
                RuntimeAdapter runtimeAdapter, RenderedConfigSource renderedConfigSource,
                com.solace.sm.dp.apply.engine.HealthGate healthGate,
                com.solace.sm.dp.apply.engine.ResumePlanner resumePlanner,
                com.solace.sm.dp.apply.engine.ApplyEngineProperties applyEngineProperties) {
            return new ApplyExecutor(admissionService, stepIntentService, attemptService,
                    outcomeService, runtimeAdapter, renderedConfigSource, healthGate, resumePlanner,
                    applyEngineProperties);
        }
    }
}
