package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * MINOR fix (post-review): the plan's own
 * {@code shutdownDrainsInFlightWorkAndStopsClaimingNewWork} names TWO properties, and the only
 * shutdown test that existed ({@code ApplyWorkerPoisonAndDrainIT}) covered only the "beyond grace"
 * half. This is the other half: work that finishes WITHIN the shutdown-grace window must land
 * terminal — {@code stop()} waits for it rather than abandoning it the instant {@code stop()} is
 * called.
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(GracefulShutdownWithinGraceLandsTerminalIT.SlowButFinishingExecutorConfiguration.class)
class GracefulShutdownWithinGraceLandsTerminalIT {

    private static TwoSchemaTestDatabase database;

    private static final UUID TENANT = UUID.randomUUID();
    private static final PlacementRef PLACEMENT_REF = new PlacementRef(new TenantId(TENANT),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    private static final CountDownLatch CLAIMED = new CountDownLatch(1);

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        database = TwoSchemaTestDatabase.provisionWithOwnExecutor(registry);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "100ms");
        registry.add("sm.dp.apply.worker.pool-size", () -> "2");
        // Generous relative to the adapter's own 300ms delay below, so the work finishes WELL
        // inside the grace window rather than racing it.
        registry.add("sm.dp.apply.worker.shutdown-grace", () -> "5s");
    }

    @Autowired
    private ApplyRequestRepository requests;

    @Autowired
    private ApplyExecutor executor;

    @Autowired
    private ApplyWorker worker;

    @Test
    void workClaimedBeforeStopThatFinishesWithinGraceLandsTerminalNotRunning()
            throws InterruptedException {
        ScopeKey scope = PLACEMENT_REF.scope();
        DeclaredInstances.registered(database, scope);
        ApplyCommand command =
                new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null, "operator-1");
        ApplyId id = ((AdmissionResult.Admitted) executor.admit(command)).id();

        assertTrue(CLAIMED.await(10, TimeUnit.SECONDS), "the worker never claimed the row");

        // stop() is called the instant the row is claimed -- the adapter has not returned yet, so
        // this is exactly the drain, not an idle shutdown.
        worker.stop();

        assertEquals(RequestState.STAGED,
                requests.find(new TenantId(TENANT), id).orElseThrow().state(),
                "work that finishes within the grace window must land terminal -- stop() must have "
                        + "WAITED for it rather than abandoning it the instant stop() was called");
    }

    /** A materialize that takes a short, FIXED delay and then genuinely succeeds. */
    private static final class BrieflySlowAdapter implements RuntimeAdapter {
        private final InMemoryRuntimeAdapter delegate;

        BrieflySlowAdapter(InMemoryRuntimeAdapter delegate) {
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
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // Deliberately a FAILURE, not a silent fall-through: a real SSH/Docker write
                // interrupted mid-flight is not a call that quietly finished anyway. This is what
                // makes the test below discriminating -- if stop() ever called shutdownNow() before
                // waiting out the grace period, THIS is what a prematurely interrupted write would
                // do, and the landed state would not be STAGED.
                Thread.currentThread().interrupt();
                throw new com.solace.sm.dp.apply.runtime.RuntimeAdapterException(
                        new com.solace.sm.dp.apply.model.ErrorClass("materialize.interrupted"));
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
    static class SlowButFinishingExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            return new BrieflySlowAdapter(new InMemoryRuntimeAdapter(PLACEMENT_REF, true,
                    "replica-1"));
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
