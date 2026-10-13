package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The BLOCKER regression test the review asked for: "a boot-level test with a second context
 * against the same database while the first holds a RUNNING row."
 *
 * <p>Deliberately NOT a {@code @SpringBootTest}: JUnit builds and injects that ONE context
 * synchronously, before the test method body ever runs, which gives a test no chance to observe a
 * context mid-boot. This test instead builds two {@code ApplicationContext}s BY HAND, one on its
 * own thread, so it can catch context A while A's own startup-recovery sweep is still blocked
 * inside the adapter — i.e. AFTER A's fix-required guard acquisition, but before A has finished
 * booting — and attempt to boot context B against the SAME database at exactly that moment.
 *
 * <p>Before the fix, {@code ApplyStartupRecovery} held no {@link com.solace.sm.dp.apply.persistence.WorkerSingletonGuard}
 * reference at all: the guard was acquired only later, inside {@code ApplyWorker.start()} (a LATER
 * phase). Context B's own recovery sweep (an EARLIER phase than ITS OWN {@code ApplyWorker}) would
 * therefore have run to completion — including calling {@code resumeRunning}, which would have
 * opened a SECOND attempt against the very row context A is still actively working — before B's
 * {@code ApplyWorker.start()} ever discovered it had to refuse. With the fix, B's recovery sweep
 * acquires the guard as its very first action and never reaches {@code resumeRunning} at all.
 */
class SecondWorkerPoolRefusedBeforeSweepIT {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final UUID CRASHED_APPLY_ID = UUID.randomUUID();
    private static final PlacementRef PLACEMENT_REF = new PlacementRef(new TenantId(TENANT),
            new InstanceId(INSTANCE), new PlacementId(PLACEMENT));
    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    /** Held by context A's adapter inside {@code prepare()} -- released only once B's attempt is over. */
    private static final CountDownLatch PREPARE_ENTERED = new CountDownLatch(1);
    private static final CountDownLatch PREPARE_RELEASE = new CountDownLatch(1);

    @Test
    void aSecondContextIsRefusedBeforeItsSweepTouchesARowTheFirstContextIsStillWorking()
            throws InterruptedException {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        Map<String, String> baseProps = provisioned.properties();

        // Seed a RUNNING apply_request row with NO intents yet -- exactly the shape resumeOne()
        // re-enters via run() -> preflight() -> adapter.prepare(), which is where context A's own
        // startup sweep will block, latch in hand, guard already acquired.
        DeclaredInstances.registered(provisioned.db(), TENANT, INSTANCE, PLACEMENT);   // plan 010 T2.2: scope_has_instance; plan 008 S4: the placement is registered
        provisioned.db().ownerJdbc.getJdbcTemplate().update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (?, ?)",
                TENANT, INSTANCE);
        provisioned.db().ownerJdbc.getJdbcTemplate().update("INSERT INTO sm_data.apply_request"
                        + " (apply_id, tenant_id, instance_id, placement_id, operation, generation,"
                        + "  strategy, requested_by, requested_at, state)"
                        + " VALUES (?, ?, ?, ?, 'apply', 7, 'manual', 'operator-1', ?, 'RUNNING')",
                CRASHED_APPLY_ID, TENANT, INSTANCE, PLACEMENT, Timestamp.from(Instant.now()));

        Map<String, Object> propsA = new LinkedHashMap<>(baseProps);
        AtomicReference<ConfigurableApplicationContext> contextA = new AtomicReference<>();
        AtomicReference<Throwable> contextAFailure = new AtomicReference<>();
        Thread bootA = new Thread(() -> {
            try {
                contextA.set(new SpringApplicationBuilder(SolutionManagerApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(propsA)
                        .sources(BlockingOnPrepareExecutorConfiguration.class)
                        .run());
            } catch (Throwable t) {
                contextAFailure.set(t);
            }
        }, "context-A-boot");
        bootA.start();

        try {
            assertTrue(PREPARE_ENTERED.await(30, TimeUnit.SECONDS),
                    "context A's startup sweep never reached the adapter -- it cannot be mid-boot");

            // At this point (fix in place): A's ApplyStartupRecovery.start() already acquired the
            // guard, is blocked deep inside resumeRunning -> run -> preflight -> adapter.prepare(),
            // and A's own ApplicationContext has therefore NOT finished refreshing yet. Attempting
            // to boot a second context against the SAME database, right now, is exactly the
            // scenario the finding describes -- and must be refused immediately.
            Map<String, Object> propsB = new LinkedHashMap<>(baseProps);
            Exception refused = null;
            try {
                new SpringApplicationBuilder(SolutionManagerApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(propsB)
                        .sources(NonBlockingExecutorConfiguration.class)
                        .run();
                fail("a second worker pool against the same database must be refused, not booted");
            } catch (Exception e) {
                refused = e;
            }
            Throwable cause = refused;
            StringBuilder chain = new StringBuilder();
            while (cause != null) {
                chain.append(cause.getClass().getName()).append(": ")
                        .append(cause.getMessage()).append('\n');
                cause = cause.getCause();
            }
            assertTrue(chain.toString().contains("single worker pool"),
                    () -> "context B's failure must be the singleton guard's refusal, not some "
                            + "other boot error -- full chain:\n" + chain);

            // The decisive DB-level proof that B's sweep never even reached tenantsWithNonTerminalWork,
            // let alone resumeRunning: exactly ONE attempt exists for the crashed apply -- the
            // PREFLIGHT attempt A itself opened before blocking. A second worker pool's sweep
            // resuming the same row would have opened a SECOND attempt on the same intent (or
            // failed loudly on attempt_open_fence), either way changing this count.
            int attempts = provisioned.db().ownerJdbc.getJdbcTemplate().queryForObject(
                    "SELECT count(*) FROM sm_data.apply_attempt a"
                            + " JOIN sm_data.apply_step_intent i ON i.intent_id = a.intent_id"
                            + " WHERE i.apply_id = ?", Integer.class, CRASHED_APPLY_ID);
            assertEquals(1, attempts,
                    "context B's sweep must never have touched this row -- exactly one attempt "
                            + "(context A's own, still open) may exist");
        } finally {
            PREPARE_RELEASE.countDown();
            bootA.join(30_000);
        }

        assertNotNull(contextA.get(), () -> "context A must have booted successfully; failure was: "
                + contextAFailure.get());
        try {
            // Let A's own worker land the now-unblocked request, proving A itself is healthy and
            // was never disturbed by B's refused attempt.
            RequestStateAwaiter.awaitTerminal(provisioned.db(), TENANT, CRASHED_APPLY_ID,
                    Duration.ofSeconds(10));
        } finally {
            contextA.get().close();
        }
    }

    /** A {@code prepare()} that blocks until the test releases it, delegating everything else. */
    private static final class BlockingOnPrepareAdapter implements RuntimeAdapter {
        private final InMemoryRuntimeAdapter delegate;

        BlockingOnPrepareAdapter(InMemoryRuntimeAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            return delegate.replicas(placement);
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            PREPARE_ENTERED.countDown();
            try {
                PREPARE_RELEASE.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return delegate.prepare(placement, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica replica, long generation,
                RenderedConfig config) {
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
    static class BlockingOnPrepareExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            return new BlockingOnPrepareAdapter(
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

    /** Context B's executor -- never expected to run a single call; present only so B has one. */
    @TestConfiguration
    static class NonBlockingExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            return new InMemoryRuntimeAdapter(
                    new PlacementRef(new TenantId(UUID.randomUUID()),
                            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID())),
                    true, "replica-1");
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

    /** A tiny polling helper -- deliberately not shared with the other ITs' inline copies. */
    private static final class RequestStateAwaiter {
        static void awaitTerminal(TwoSchemaTestDatabase db, UUID tenant, UUID applyId,
                Duration timeout) throws InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                String state = db.ownerJdbc.queryForObject(
                        "SELECT state FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                        new MapSqlParameterSource().addValue("t", tenant).addValue("a", applyId),
                        String.class);
                if (!"REQUESTED".equals(state) && !"RUNNING".equals(state)) {
                    return;
                }
                Thread.sleep(100);
            }
            fail("apply " + applyId + " did not land within " + timeout);
        }
    }
}
