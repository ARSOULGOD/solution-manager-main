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
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.worker.ApplyRecoveryStatus;
import com.solace.sm.dp.apply.worker.ApplyWorkerStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The full lifecycle, over a REAL {@code ApplyExecutor} (an in-memory adapter substituting only
 * for {@code RuntimeAdapter}/{@code RenderedConfigSource} — every engine service in between is the
 * real, production {@code AdmissionService}/{@code OutcomeService}/etc. beans
 * {@code ApplyEngineConfiguration} publishes).
 *
 * <p>Proves, against the real {@link ApplyWorker} scheduler (not a direct method call):
 * <ul>
 *   <li>a {@code RUNNING} row seeded BEFORE the application boots — simulating a crash under a
 *       previous process — is already terminal by the time this test method runs, which can only
 *       be true if {@link ApplyStartupRecovery} ran synchronously during startup, before the
 *       connector opened and certainly before any test code executed;</li>
 *   <li>a fresh {@code REQUESTED} row submitted AFTER boot, with no direct call to
 *       {@code runAdmitted} anywhere in this test, is claimed and landed by the worker's own poll
 *       loop within a few poll intervals — {@code 202} is a genuine promise, not a poll this test
 *       drives by hand.</li>
 * </ul>
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyWorkerLifecycleIT.TestApplyExecutorConfiguration.class)
class ApplyWorkerLifecycleIT {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final PlacementRef PLACEMENT_REF = new PlacementRef(new TenantId(TENANT),
            new InstanceId(INSTANCE), new PlacementId(PLACEMENT));
    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));
    private static final UUID CRASHED_APPLY_ID = UUID.randomUUID();

    // MAJOR post-review addition: the plan's Results claimed "the whole of Task 4b" was executed
    // with no multi-tenant test at the startup-sweep level -- the only level at which
    // TenantEnumerationRepository.tenantsWithNonTerminalWork()'s cross-TENANT enumeration is
    // actually exercised end to end. A single-tenant seed cannot distinguish "swept every tenant"
    // from "swept the one tenant this test happens to use". A second, wholly distinct tenant with
    // its own crashed RUNNING row closes that gap.
    private static final UUID TENANT_2 = UUID.randomUUID();
    private static final UUID INSTANCE_2 = UUID.randomUUID();
    private static final UUID PLACEMENT_2 = UUID.randomUUID();
    private static final UUID CRASHED_APPLY_ID_2 = UUID.randomUUID();

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase db = TwoSchemaTestDatabase.provisionWithOwnExecutor(registry);
        // A fast poll so the forward-path assertion below does not need a long wait.
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");

        // Seed a scope row and a RUNNING apply_request row directly -- simulating a worker that
        // claimed this row and then crashed, exactly the state Task 4b's sweep exists to recover.
        // Seeded BEFORE the application context (and therefore the sweep) exists.
        DeclaredInstances.registered(db, TENANT, INSTANCE, PLACEMENT);   // plan 010 T2.2: scope_has_instance; plan 008 S4: the placement is registered
        db.ownerJdbc.getJdbcTemplate().update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (?, ?)",
                TENANT, INSTANCE);
        db.ownerJdbc.getJdbcTemplate().update("INSERT INTO sm_data.apply_request"
                        + " (apply_id, tenant_id, instance_id, placement_id, operation, generation,"
                        + "  strategy, requested_by, requested_at, state)"
                        + " VALUES (?, ?, ?, ?, 'apply', 7, 'manual', 'operator-1', ?, 'RUNNING')",
                CRASHED_APPLY_ID, TENANT, INSTANCE, PLACEMENT, java.sql.Timestamp.from(Instant.now()));

        // A wholly SEPARATE tenant, so recovering it can only be explained by the sweep's own
        // tenant enumeration, never by anything scoped to TENANT above.
        DeclaredInstances.registered(db, TENANT_2, INSTANCE_2, PLACEMENT_2);
        db.ownerJdbc.getJdbcTemplate().update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (?, ?)",
                TENANT_2, INSTANCE_2);
        db.ownerJdbc.getJdbcTemplate().update("INSERT INTO sm_data.apply_request"
                        + " (apply_id, tenant_id, instance_id, placement_id, operation, generation,"
                        + "  strategy, requested_by, requested_at, state)"
                        + " VALUES (?, ?, ?, ?, 'apply', 3, 'manual', 'operator-1', ?, 'RUNNING')",
                CRASHED_APPLY_ID_2, TENANT_2, INSTANCE_2, PLACEMENT_2,
                java.sql.Timestamp.from(Instant.now()));
    }

    @Autowired
    private ApplyRequestRepository requests;

    @Autowired
    private ApplyExecutor executor;

    @Autowired
    private ApplyWorkerStatus workerStatus;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Autowired
    private ApplyRecoveryStatus recoveryStatus;

    /**
     * Plan 010 T6.2, M4 / M16 (ii): production now supplies an executor, adapter and config source of its own, and
     * {@code sm.dp.apply.production.enabled=false} withdraws them before this test's own are registered. Exactly one of
     * each is left, and it is this class's in-memory one: the seven ITs that bring their own keep testing the worker
     * against it, as before.
     */
    @Test
    void theSevenWorkerItsKeepTheirOwnExecutorWithProductionDisabled() {
        assertEquals(1, context.getBeansOfType(ApplyExecutor.class).size());
        assertEquals(1, context.getBeansOfType(RuntimeAdapter.class).size());
        assertTrue(context.getBean(RuntimeAdapter.class) instanceof InMemoryRuntimeAdapter,
                "the production SSH adapter was withdrawn; the test's in-memory one is the only adapter");
        assertEquals(1, context.getBeansOfType(RenderedConfigSource.class).size());
    }

    @Test
    void theStartupSweepAlreadyRecoveredTheCrashedRowByTheTimeThisTestRuns() {
        RequestState state =
                requests.find(new TenantId(TENANT), new ApplyId(CRASHED_APPLY_ID)).orElseThrow()
                        .state();
        assertTrue(state.isTerminal(),
                "the RUNNING row seeded before boot must already be terminal -- the sweep ran "
                        + "synchronously during startup, before this test method ever executed. "
                        + "(STAGED if this method runs before the other test in this class inserts "
                        + "a later generation on the same scope, SUPERSEDED if after -- either way "
                        + "proves the sweep already landed it out of RUNNING before this method ran)"
                        + " was: " + state);
        assertTrue(recoveryStatus.ran());
        assertTrue(workerStatus.running());
    }

    /**
     * MAJOR post-review addition: proves the sweep is a TENANT ENUMERATION, not a fluke of the one
     * tenant every other test in this class happens to use. A second, wholly separate tenant with
     * its own crashed {@code RUNNING} row, seeded before boot exactly like the first, must ALSO be
     * terminal by the time this method runs — recoverable only if
     * {@code ApplyStartupRecovery.start()} actually iterated {@code tenantsWithNonTerminalWork()}'s
     * full result rather than something scoped to a single tenant.
     */
    @Test
    void theStartupSweepRecoversEveryTenantNotJustOne() {
        RequestState state =
                requests.find(new TenantId(TENANT_2), new ApplyId(CRASHED_APPLY_ID_2)).orElseThrow()
                        .state();
        assertTrue(state.isTerminal(),
                "a second tenant's crashed RUNNING row, seeded before boot alongside the first "
                        + "tenant's, must also be recovered by the same startup sweep -- was: "
                        + state);
    }

    @Test
    void aFreshRequestSubmittedAfterBootIsClaimedAndLandedByTheRealWorkerPollLoop()
            throws InterruptedException {
        ScopeKey scope = new ScopeKey(new TenantId(TENANT), new InstanceId(INSTANCE),
                new PlacementId(PLACEMENT));
        ApplyCommand command = new ApplyCommand(scope, Operation.APPLY, 9L, Strategy.MANUAL, null,
                "operator-1");

        AdmissionResult result = executor.admit(command);
        ApplyId id = ((AdmissionResult.Admitted) result).id();
        assertEquals(RequestState.REQUESTED,
                requests.find(new TenantId(TENANT), id).orElseThrow().state());

        RequestState landed = awaitTerminal(id, Duration.ofSeconds(5));
        assertEquals(RequestState.STAGED, landed,
                "no test code called runAdmitted -- only the worker's own poll loop could have "
                        + "claimed and landed this row");
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

    @TestConfiguration
    static class TestApplyExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            return new InMemoryRuntimeAdapter(PLACEMENT_REF, true, "replica-1");
        }

        @Bean
        RenderedConfigSource renderedConfigSource() {
            return (placement, generation) -> GENERATION_7;
        }

        /**
         * The real {@code AdmissionService}/{@code OutcomeService}/{@code StepIntentService}/
         * {@code AttemptService}/{@code HealthGate}/{@code ResumePlanner}/
         * {@code ApplyEngineProperties} beans {@code ApplyEngineConfiguration} already publishes —
         * only the adapter and the config source are test doubles.
         */
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
