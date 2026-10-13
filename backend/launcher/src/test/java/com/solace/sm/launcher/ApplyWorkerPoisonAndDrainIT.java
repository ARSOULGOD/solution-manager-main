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
import com.solace.sm.dp.apply.worker.ApplyWorker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Two of Task 4a's properties that only show up under a real, multi-request worker: one poisoned
 * request must not head-of-line block its siblings, and a graceful shutdown drains in-flight work
 * rather than corrupting it.
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ApplyWorkerPoisonAndDrainIT.TestApplyExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplyWorkerPoisonAndDrainIT {

    private static TwoSchemaTestDatabase database;

    private static final UUID TENANT = UUID.randomUUID();
    private static final PlacementRef GOOD_1 = placement();
    private static final PlacementRef GOOD_2 = placement();
    private static final PlacementRef POISONED = placement();
    private static final PlacementRef SLOW = placement();

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    /** Held by the slow placement's adapter -- released only when a test wants it to finish. */
    private static final CountDownLatch SLOW_RELEASE = new CountDownLatch(1);
    private static final CountDownLatch SLOW_CLAIMED = new CountDownLatch(1);

    private static PlacementRef placement() {
        return new PlacementRef(new TenantId(TENANT), new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
    }

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        database = TwoSchemaTestDatabase.provisionWithOwnExecutor(registry);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "150ms");
        registry.add("sm.dp.apply.worker.pool-size", () -> "3");
        registry.add("sm.dp.apply.worker.shutdown-grace", () -> "500ms");
    }

    @Autowired
    private ApplyRequestRepository requests;

    @Autowired
    private ApplyExecutor executor;

    @Autowired
    private ApplyWorker worker;

    private ApplyId admit(PlacementRef p, long generation) {
        ScopeKey scope = p.scope();
        DeclaredInstances.registered(database, scope);
        ApplyCommand command =
                new ApplyCommand(scope, Operation.APPLY, generation, Strategy.MANUAL, null,
                        "operator-1");
        return ((AdmissionResult.Admitted) executor.admit(command)).id();
    }

    @Test
    @Order(1)
    void onePoisonedRequestDoesNotStopTheOthersFromLanding() throws InterruptedException {
        ApplyId good1 = admit(GOOD_1, 7);
        ApplyId good2 = admit(GOOD_2, 7);
        ApplyId poisoned = admit(POISONED, 7);

        assertEquals(RequestState.STAGED, awaitTerminal(good1, Duration.ofSeconds(5)),
                "a sibling request must land normally regardless of another tenant's poison");
        assertEquals(RequestState.STAGED, awaitTerminal(good2, Duration.ofSeconds(5)));
        // Not necessarily TERMINAL: a materialize failure on a placement with no prior write has
        // no known rollback target, so BE-D22's assessment correctly lands this on
        // APPLY_INDETERMINATE ("no known prior state -- rule 0 forbids acting without the journal
        // entry"), which design deliberately makes a NON-terminal landing place (it blocks its own
        // scope for an operator to resolve). Either way, "landed" -- left REQUESTED/RUNNING -- is
        // the property this test is actually about: the engine's own bounded retry decided this
        // row's fate, and the worker never simply hangs on it forever.
        assertTrue(awaitLanded(poisoned, Duration.ofSeconds(10)),
                "the poisoned request must land somewhere -- terminal or APPLY_INDETERMINATE -- "
                        + "rather than the worker hanging on it forever");
    }

    /**
     * MINOR fix (post-review): renamed from {@code gracefulShutdownDrainsAndLeavesAnInFlightRowRunningNotCorrupted}
     * — the old name claimed "drains", but the row it asserts on is left {@code RUNNING}, which is
     * the NOT-drained outcome (beyond the grace deadline). This class's only OTHER shutdown-relevant
     * test is {@link #onePoisonedRequestDoesNotStopTheOthersFromLanding}, which does not call
     * {@code stop()} at all. The two halves this test's old name implied — work that finishes
     * WITHIN grace landing terminal, and no NEW claim being taken after {@code stop()} — are each
     * covered by their own dedicated test now: {@code GracefulShutdownWithinGraceLandsTerminalIT}
     * and {@code NoNewClaimsAfterStopIT}.
     */
    @Test
    @Order(2)
    void gracefulShutdownBeyondTheGraceDeadlineLeavesAnInFlightRowRunningNotCorrupted()
            throws InterruptedException {
        ApplyId slow = admit(SLOW, 7);

        // Wait until the worker has actually claimed it (row RUNNING) before stopping -- otherwise
        // this would only prove an idle shutdown, not a drain.
        assertTrue(SLOW_CLAIMED.await(5, TimeUnit.SECONDS), "the worker never claimed the slow row");

        long before = System.nanoTime();
        worker.stop();
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertTrue(elapsedMs < 2000,
                "a graceful stop must return within roughly its shutdown-grace deadline, not hang: "
                        + elapsedMs + "ms");
        assertEquals(RequestState.RUNNING,
                requests.find(new TenantId(TENANT), slow).orElseThrow().state(),
                "beyond the grace deadline the row is left RUNNING -- recoverable, not corrupted");

        SLOW_RELEASE.countDown();   // let the blocked adapter thread finish, tidily
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

    /** True once the row has left REQUESTED/RUNNING -- terminal, or the APPLY_INDETERMINATE landing. */
    private boolean awaitLanded(ApplyId id, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            RequestState state = requests.find(new TenantId(TENANT), id).orElseThrow().state();
            if (state != RequestState.REQUESTED && state != RequestState.RUNNING) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** A materialize that blocks until released, so a test can catch the worker mid-claim. */
    private static final class SlowOnMaterializeAdapter implements RuntimeAdapter {
        private final InMemoryRuntimeAdapter delegate;

        SlowOnMaterializeAdapter(InMemoryRuntimeAdapter delegate) {
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
            SLOW_CLAIMED.countDown();
            try {
                SLOW_RELEASE.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
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

    /** Routes to whichever fake owns the call's placement. */
    private static final class RoutingAdapter implements RuntimeAdapter {
        private final Map<PlacementRef, RuntimeAdapter> byPlacement;

        RoutingAdapter(Map<PlacementRef, RuntimeAdapter> byPlacement) {
            this.byPlacement = byPlacement;
        }

        private RuntimeAdapter of(PlacementRef p) {
            RuntimeAdapter a = byPlacement.get(p);
            if (a == null) {
                throw new IllegalStateException("no fake registered for " + p);
            }
            return a;
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

    @TestConfiguration
    static class TestApplyExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            InMemoryRuntimeAdapter good1 = new InMemoryRuntimeAdapter(GOOD_1, true, "replica-1");
            InMemoryRuntimeAdapter good2 = new InMemoryRuntimeAdapter(GOOD_2, true, "replica-1");
            InMemoryRuntimeAdapter poisoned =
                    new InMemoryRuntimeAdapter(POISONED, true, "replica-1");
            poisoned.failMaterializeAlways();
            InMemoryRuntimeAdapter slowDelegate = new InMemoryRuntimeAdapter(SLOW, true,
                    "replica-1");
            return new RoutingAdapter(Map.of(GOOD_1, good1, GOOD_2, good2, POISONED, poisoned,
                    SLOW, new SlowOnMaterializeAdapter(slowDelegate)));
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
