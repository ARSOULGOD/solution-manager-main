package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The apply engine wired over the shared {@code sm_dp_runtime} data source — the object graph
 * {@code ApplyPersistenceConfiguration} + {@code ApplyEngineConfiguration} build in production,
 * assembled by hand so the engine ITs assert on behaviour rather than on Spring.
 *
 * <p><b>One transaction manager over the CACHED data source instance.</b> Task 1 measured why: Spring
 * keys the transactional connection by the {@code DataSource} INSTANCE, so a template over one
 * instance and a manager over another silently do not share a transaction, and every rollback test
 * passes for the wrong reason. {@link SmDataPostgres#runtimeDataSource()} caches, and this class
 * builds exactly one manager over it.
 *
 * <p>{@code ApplyPersistenceConfigurationIT} (Task 5) covers the Spring-wired path separately, so
 * nothing here is the only proof of anything.
 */
final class ApplyEngineHarness {

    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ApplyScopeRepository scopes;
    private static ApplyRequestRepository requests;
    private static StepIntentRepository intents;
    private static AttemptRepository attempts;

    private ApplyEngineHarness() {
    }

    static synchronized NamedParameterJdbcTemplate jdbc() {
        if (jdbc == null) {
            SmDataPostgres.bootstrap();
            jdbc = new NamedParameterJdbcTemplate(SmDataPostgres.runtimeDataSource());
        }
        return jdbc;
    }

    static synchronized TransactionTemplate transactions() {
        if (transactions == null) {
            jdbc();
            transactions = new TransactionTemplate(
                    new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        }
        return transactions;
    }

    static synchronized ApplyScopeRepository scopes() {
        if (scopes == null) {
            scopes = new ApplyScopeRepository(jdbc());
        }
        return scopes;
    }

    static synchronized ApplyRequestRepository requests() {
        if (requests == null) {
            requests = new ApplyRequestRepository(jdbc());
        }
        return requests;
    }

    static synchronized StepIntentRepository intents() {
        if (intents == null) {
            intents = new StepIntentRepository(jdbc());
        }
        return intents;
    }

    static synchronized AttemptRepository attempts() {
        if (attempts == null) {
            attempts = new AttemptRepository(jdbc());
        }
        return attempts;
    }

    /** A fresh service per test class: {@code AdmissionService} holds no state, but nor does it cost. */
    static AdmissionService admission() {
        return new AdmissionService(transactions(), scopes(), requests(), Clock.systemUTC());
    }

    static OutcomeService outcomes() {
        return new OutcomeService(transactions(), scopes(), requests(), Clock.systemUTC());
    }

    /**
     * As {@link #outcomes()} but over the REAL reconciliation ledger, as production wires it: a landing on
     * {@code APPLY_INDETERMINATE} opens the bookkeeping row and a give-up closes it ({@code gave_up_at}). The plain form
     * wires {@code ReconciliationLedger.none()}, which writes nothing.
     */
    static OutcomeService outcomesWithLedger() {
        return new OutcomeService(transactions(), scopes(), requests(), Clock.systemUTC(),
                com.solace.sm.dp.apply.events.ApplyEventEmitter.silent(),
                com.solace.sm.dp.config.LadderRecorder.none(), new JdbcReconciliationRepository(jdbc()));
    }

    static StepIntentService stepIntents() {
        return new StepIntentService(transactions(), requests(), intents(), Clock.systemUTC());
    }

    static AttemptService attemptsService(int maxAttempts) {
        return new AttemptService(transactions(), attempts(), new AttemptPolicy(maxAttempts),
                Clock.systemUTC());
    }

    /** The retry cap plan 005's executor runs under. Small, so the bound is cheap to exercise. */
    static int maxAttempts() {
        return 2;
    }

    /**
     * The engine bounds the ITs run under. The gate deadline and poll interval are milliseconds
     * rather than minutes so a gate that is SUPPOSED to fail costs the suite a third of a second
     * instead of three minutes; the step budget stays generous, because it bounds a call that
     * should always return.
     */
    static com.solace.sm.dp.apply.engine.ApplyEngineProperties engineProperties() {
        return new com.solace.sm.dp.apply.engine.ApplyEngineProperties(
                java.time.Duration.ofMinutes(5), java.time.Duration.ofMillis(400),
                java.time.Duration.ofMillis(10));
    }

    /** An executor over the real journal and whichever adapter and renderer a test hands it. */
    static com.solace.sm.dp.apply.engine.ApplyExecutor executor(
            com.solace.sm.dp.apply.runtime.RuntimeAdapter adapter,
            com.solace.sm.dp.apply.runtime.RenderedConfigSource configs) {
        return executor(adapter, configs, engineProperties());
    }

    /**
     * The same, with the engine's bounds supplied. The Cache walkthrough needs a gate deadline
     * measured against a real JVM's start-up rather than against the fake adapter's, and a suite
     * default long enough for that would make every failing-gate test cost minutes.
     */
    static com.solace.sm.dp.apply.engine.ApplyExecutor executor(
            com.solace.sm.dp.apply.runtime.RuntimeAdapter adapter,
            com.solace.sm.dp.apply.runtime.RenderedConfigSource configs,
            com.solace.sm.dp.apply.engine.ApplyEngineProperties props) {
        return new com.solace.sm.dp.apply.engine.ApplyExecutor(admission(), stepIntents(),
                attemptsService(maxAttempts()), outcomes(), adapter, configs,
                new com.solace.sm.dp.apply.engine.HealthGate(Clock.systemUTC(),
                        com.solace.sm.dp.apply.engine.Sleeper.real(), props),
                new com.solace.sm.dp.apply.engine.ResumePlanner(), props,
                com.solace.sm.dp.apply.events.ApplyEventEmitter.silent(), livePlacements());
    }

    /**
     * What an instance-wide stop addresses (plan 008 S3, {@code BE-D46}): the REGISTRY's live placements over the shared
     * runtime credential, as production wires it. A test that stops an instance-wide must register the placements it
     * expects to be stopped ({@code InventoryFixtures.placement}).
     */
    static com.solace.sm.dp.apply.engine.LivePlacements livePlacements() {
        return new com.solace.sm.dp.inventory.RegistryLivePlacements(new JdbcPlacementRepository(jdbc()));
    }

    /** A rolling apply command — the non-manual half of {@code SM-D15}'s strategy set. */
    static ApplyCommand rollingApply(ScopeKey scope, long generation, Strategy strategy) {
        return new ApplyCommand(scope, Operation.APPLY, generation, strategy,
                strategy == Strategy.RESTART_ALL ? "operator-1" : null, "operator-1");
    }

    // -------------------------------------------------------------------------------------------
    // Command builders. Named for what they mean rather than which arguments they fill in.
    // -------------------------------------------------------------------------------------------

    static ScopeKey scope(UUID tenant, UUID instance, PlacementId placement) {
        return new ScopeKey(new TenantId(tenant), new InstanceId(instance), placement);
    }

    static ApplyCommand apply(ScopeKey scope, long generation) {
        return new ApplyCommand(scope, Operation.APPLY, generation, Strategy.MANUAL, null,
                "operator-1");
    }

    static ApplyCommand restart(ScopeKey scope) {
        return new ApplyCommand(scope, Operation.RESTART, null, Strategy.ROLLING_HEALTH_GATED, null,
                "operator-1");
    }

    static ApplyCommand stop(ScopeKey scope) {
        return new ApplyCommand(scope, Operation.STOP, null, null, null, "operator-1");
    }

    static TenantId tenant(UUID id) {
        return new TenantId(id);
    }

    /**
     * A minimal legal {@code REQUESTED} row for the BELT tests — the ones that go around
     * {@code AdmissionService} to prove the fences hold on their own.
     */
    static ApplyRequestRow requestedRow(ScopeKey scope, Operation op, Long generation,
            Strategy strategy) {
        return new ApplyRequestRow(ApplyId.next(), scope, op, generation, strategy, null,
                "operator-1", Instant.now(), RequestState.REQUESTED, null);
    }

    /** The instance-wide apply the belt tests use most. */
    static ApplyRequestRow requestedApply(ScopeKey scope, long generation) {
        return requestedRow(scope, Operation.APPLY, generation, Strategy.MANUAL);
    }
}
