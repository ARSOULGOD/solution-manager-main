package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The MAJOR regression test the review asked for by name:
 * {@code theWorkerPoolIsBoundedAndRespectsItsConfiguredSize}.
 *
 * <p>Five placements are admitted against a pool of size 2. Every one of the five blocks on the
 * SAME shared gate inside {@code adapter.prepare()}, so this test can observe, WHILE all five are
 * still {@code REQUESTED} or claimed, that the worker never has more than {@code pool-size} rows
 * simultaneously claimed ({@code RUNNING}) — the fix for the finding that claiming used to move
 * every claimable row to {@code RUNNING} in one poll tick regardless of the pool's own size,
 * handing the rest to an unbounded in-memory queue behind the fixed thread pool.
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ApplyWorkerBoundedClaimIT.TestApplyExecutorConfiguration.class)
class ApplyWorkerBoundedClaimIT {

    private static final int POOL_SIZE = 2;
    private static final int REQUEST_COUNT = 5;
    private static TwoSchemaTestDatabase database;

    private static final UUID TENANT = UUID.randomUUID();
    private static final List<PlacementRef> PLACEMENTS =
            List.of(placement(), placement(), placement(), placement(), placement());

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    /** Every one of the five blocks here until the test releases it. */
    private static final CountDownLatch GATE = new CountDownLatch(1);
    private static final AtomicInteger CONCURRENTLY_INSIDE = new AtomicInteger();
    private static final AtomicInteger MAX_OBSERVED = new AtomicInteger();

    private static PlacementRef placement() {
        return new PlacementRef(new TenantId(TENANT), new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
    }

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        database = TwoSchemaTestDatabase.provisionWithOwnExecutor(registry);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "100ms");
        registry.add("sm.dp.apply.worker.pool-size", () -> String.valueOf(POOL_SIZE));
        registry.add("sm.dp.apply.worker.shutdown-grace", () -> "1s");
    }

    @Autowired
    private ApplyRequestRepository requests;

    @Autowired
    private ApplyExecutor executor;

    @Test
    void theWorkerPoolIsBoundedAndRespectsItsConfiguredSize() throws InterruptedException {
        List<ApplyId> ids = PLACEMENTS.stream().map(p -> admit(p, 7)).toList();

        // Give the worker several poll ticks to try to over-claim, if it still can. Sampled
        // repeatedly rather than once, because the property under test is "NEVER exceeds
        // pool-size", not "happens not to exceed it at the one instant we looked".
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        int maxRunningObserved = 0;
        while (System.nanoTime() < deadline) {
            int running = countInState(ids, RequestState.RUNNING);
            maxRunningObserved = Math.max(maxRunningObserved, running);
            assertTrue(running <= POOL_SIZE,
                    () -> "more rows are RUNNING (" + running + ") than the configured pool size ("
                            + POOL_SIZE + ") -- claims are not bounded by the pool");
            assertTrue(MAX_OBSERVED.get() <= POOL_SIZE,
                    () -> "more requests were concurrently inside the adapter ("
                            + MAX_OBSERVED.get() + ") than the pool size (" + POOL_SIZE + ")");
            Thread.sleep(50);
        }
        assertEquals(POOL_SIZE, maxRunningObserved,
                "with five claimable rows and a pool of two, the worker must actually reach the "
                        + "bound at some point, not merely stay under it by accident (e.g. by "
                        + "claiming nothing)");
        assertEquals(POOL_SIZE, CONCURRENTLY_INSIDE.get(),
                "exactly pool-size requests must still be blocked inside the adapter right now");

        // Release everyone and confirm all five eventually land -- bounding the claim must not
        // strand the remaining three REQUESTED rows.
        GATE.countDown();
        for (ApplyId id : ids) {
            assertEquals(RequestState.STAGED, awaitTerminal(id, Duration.ofSeconds(10)));
        }
    }

    private ApplyId admit(PlacementRef p, long generation) {
        ScopeKey scope = p.scope();
        DeclaredInstances.registered(database, scope);
        ApplyCommand command =
                new ApplyCommand(scope, Operation.APPLY, generation, Strategy.MANUAL, null,
                        "operator-1");
        return ((AdmissionResult.Admitted) executor.admit(command)).id();
    }

    private int countInState(List<ApplyId> ids, RequestState state) {
        int count = 0;
        for (ApplyId id : ids) {
            if (requests.find(new TenantId(TENANT), id).orElseThrow().state() == state) {
                count++;
            }
        }
        return count;
    }

    private RequestState awaitTerminal(ApplyId id, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            RequestState state = requests.find(new TenantId(TENANT), id).orElseThrow().state();
            if (state.isTerminal()) {
                return state;
            }
            Thread.sleep(100);
        }
        fail("request " + id.value() + " did not reach a terminal state within " + timeout);
        throw new AssertionError("unreachable");
    }

    /** Blocks every {@code prepare()} call on one shared gate, tracking concurrency as it goes. */
    private static final class GatedAdapter implements RuntimeAdapter {
        private final Map<PlacementRef, InMemoryRuntimeAdapter> delegates;

        GatedAdapter(Map<PlacementRef, InMemoryRuntimeAdapter> delegates) {
            this.delegates = delegates;
        }

        private InMemoryRuntimeAdapter of(PlacementRef p) {
            return delegates.get(p);
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            return of(placement).replicas(placement);
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            int now = CONCURRENTLY_INSIDE.incrementAndGet();
            MAX_OBSERVED.getAndUpdate(prev -> Math.max(prev, now));
            try {
                GATE.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                CONCURRENTLY_INSIDE.decrementAndGet();
            }
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

    @TestConfiguration
    static class TestApplyExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            Map<PlacementRef, InMemoryRuntimeAdapter> delegates = new java.util.LinkedHashMap<>();
            for (PlacementRef p : PLACEMENTS) {
                delegates.put(p, new InMemoryRuntimeAdapter(p, true, "replica-1"));
            }
            return new GatedAdapter(delegates);
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
