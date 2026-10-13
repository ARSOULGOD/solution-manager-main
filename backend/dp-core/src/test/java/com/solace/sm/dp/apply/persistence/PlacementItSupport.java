package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shared wiring of plan 008 S3's data-plane ITs ({@code AddPlacementIT}, {@code AddPlacementLifecycleRaceIT},
 * {@code RetirePlacementIT}): the PRODUCTION inventory service, canonical constructor, over the real JDBC rows as
 * {@code sm_dp_runtime}, with the real ladder recorder and an emitter that publishes through the same after-commit
 * decorator the data plane uses, so "announced" means "announced after COMMIT" and a rolled-back change announces nothing.
 *
 * <p>(In the persistence test package: the Testcontainers harness {@code SmDataPostgres} is package-private.)
 */
final class PlacementItSupport {

    static final UUID ACTOR = UUID.randomUUID();
    static final String DIGEST = "sha256:" + "a".repeat(64);

    private static final AtomicInteger PORTS = new AtomicInteger(41000);
    private static final List<EventEnvelope> CONFIG_STATES = Collections.synchronizedList(new ArrayList<>());

    private static InventoryService service;
    private static JdbcMetricDefinitionRepository metrics;
    private static TransactionTemplate tx;
    private static ApplyEventEmitter emitter;

    private PlacementItSupport() {
    }

    static synchronized InventoryService service() {
        if (service == null) {
            SmDataPostgres.bootstrap();
            NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
            metrics = new JdbcMetricDefinitionRepository(jdbc);
            tx = new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
            emitter = new ApplyEventEmitter(new AfterCommitEventPublisher(event -> {
                if (event.event().equals("instance.config-state")) {
                    CONFIG_STATES.add(event);
                }
            }, new SpringTransactionBoundary()), Clock.systemUTC(), UUID::randomUUID);
            com.solace.sm.dp.secret.EnvelopeSecretStore secrets = SecretFixtures
                    .store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 81), null));
            service = new InventoryService(tx, new JdbcInstanceStateRepository(jdbc), new JdbcPlacementRepository(jdbc),
                    new JdbcTargetConnectionRepository(jdbc), secrets, secrets, Clock.systemUTC(), metrics,
                    new LadderRecorder(new JdbcLadderRepository(jdbc)), emitter);
        }
        return service;
    }

    /** The emitter the service uses: an apply landing through it announces into the same list. */
    static ApplyEventEmitter emitter() {
        service();
        return emitter;
    }

    static TransactionTemplate transactions() {
        service();
        return tx;
    }

    static JdbcMetricDefinitionRepository metrics() {
        service();
        return metrics;
    }

    static NamedParameterJdbcTemplate runtime() {
        SmDataPostgres.bootstrap();
        return SmDataPostgres.runtimeJdbc();
    }

    /** The {@code instance.config-state} notices released after a commit, for one tenant (a database shared by every IT). */
    static List<EventEnvelope> configStates(UUID tenant) {
        synchronized (CONFIG_STATES) {
            return CONFIG_STATES.stream().filter(e -> e.organization().equals(tenant.toString())).toList();
        }
    }

    static int freshPort() {
        return PORTS.incrementAndGet();
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    static MetricDefinitionSpec pulled(String name) {
        return new MetricDefinitionSpec(name, "cache-engine", "gauge", "ratio", "http_json",
                "{\"kind\":\"http_json\",\"path\":\"/api/cache/stats\",\"port\":8081,\"onUnavailable\":\"degrade\"}", 30,
                true);
    }

    static MetricDefinitionSpec pushed(String name) {
        return new MetricDefinitionSpec(name, "cache-engine", "counter", "1", "solace_event",
                "{\"kind\":\"solace_event\",\"topic\":\"orders/evictions\"}", null, true);
    }

    /** One pulled metric on source port 8081, mapped to {@code hostPort}; {@code active} is the generation-0 list. */
    static MetricsDeclaration declaration(int hostPort, String... active) {
        return new MetricsDeclaration(DIGEST, List.of(pulled("cache.hit_rate")), List.of(), Map.of(8081, hostPort),
                List.of(active));
    }

    static PlacementSpec freshSpec(int healthPort) {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return new PlacementSpec("sm-" + u, "/srv/sm/" + u, "/app/external/spring/config", "application.yml",
                new HealthProbeSpec("actuator-health", "/actuator/health", healthPort, ProbeStrength.LIVENESS));
    }

    /** A declared and ACTIVATED instance with one placement on a target of its own, and its metrics declaration. */
    record Declared(UUID tenant, UUID target, UUID catalog, UUID instance, UUID placement, PlacementSpec spec,
            int metricsHostPort) {

        static Declared fresh(String... active) {
            UUID tenant = SmDataPostgres.freshTenant();
            UUID target = InventoryFixtures.target(tenant);
            UUID catalog = UUID.randomUUID();
            PlacementSpec spec = freshSpec(freshPort());
            int metricsHostPort = freshPort();
            InstanceView view = service().reserve(tenant, new DeclareInstanceCommand(UUID.randomUUID(), catalog, "cache",
                    "2.4", "inst-" + UUID.randomUUID().toString().substring(0, 8), target, AcquisitionMode.DIRECT_HTTP,
                    spec, ACTOR, declaration(metricsHostPort, active)));
            service().activate(tenant, view.instanceId());
            return new Declared(tenant, target, catalog, view.instanceId(), view.placements().get(0).placementId(), spec,
                    metricsHostPort);
        }
    }

    /**
     * The journal evidence for an instance whose ONLY placement has reached {@code restart-observed}: generation 1 staged
     * through the real config of record, then applied under {@code rolling-health-gated} by the real engine over the
     * in-memory runtime, landing through {@link #emitter()} so the stored rung and the announcement are the production ones.
     * The ladder of this instance reads {@code restart-observed}; adding a placement must drop it to {@code rendered}.
     */
    static Declared atRestartObserved() {
        Declared d = Declared.fresh("cache.hit_rate");
        NamedParameterJdbcTemplate jdbc = runtime();
        var hasher = TestHashers.keyed();
        ConfigRecordServiceImpl config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(jdbc), hasher, new Retention(10), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(jdbc), metrics());
        long generation = config.stage(d.tenant(), d.instance(), new StageCommand(stateVersion(d.tenant(), d.instance()),
                "cache", "2.4", "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}",
                "{\"cache-1\":{\"marker\":\"one\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"one\"")), GenerationOrigin.EDIT, null,
                ACTOR, null, null, List.of("cache.hit_rate"))).generation().generation();

        TenantId tenant = new TenantId(d.tenant());
        ScopeKey scope = new ScopeKey(tenant, new InstanceId(d.instance()), new PlacementId(d.placement()));
        PlacementRef ref = new PlacementRef(tenant, new InstanceId(d.instance()), new PlacementId(d.placement()));
        var props = ApplyEngineHarness.engineProperties();
        ApplyEventEmitter e = emitter();
        OutcomeService outcomes = new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e, new LadderRecorder(new JdbcLadderRepository(jdbc)));
        ApplyExecutor executor = new ApplyExecutor(new AdmissionService(ApplyEngineHarness.transactions(),
                ApplyEngineHarness.scopes(), ApplyEngineHarness.requests(), Clock.systemUTC(), e),
                ApplyEngineHarness.stepIntents(), ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts()),
                outcomes, new InMemoryRuntimeAdapter(ref, true, "replica-1", "replica-2"),
                (p, g) -> RenderedConfig.of(tenant, ("generation: " + g + "\n").getBytes(StandardCharsets.UTF_8), hasher),
                new HealthGate(Clock.systemUTC(), Sleeper.real(), props), new ResumePlanner(), props, e);
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(
                ApplyEngineHarness.rollingApply(scope, generation, Strategy.ROLLING_HEALTH_GATED));
        if (landed.state() != RequestState.APPLIED) {
            throw new AssertionError("the arrangement needs a landed gated apply, not " + landed.state());
        }
        return d;
    }

    /** {@code instance_state.reached_rung}: the cache {@code instance.config-state} fires from. */
    static String storedRung(UUID tenant, UUID instance) {
        return runtime().queryForObject("SELECT reached_rung FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance),
                String.class);
    }

    // ---- reads (as the runtime credential: every table below is SELECT-able) ------------------------------------

    static int count(String table, UUID tenant) {
        return runtime().queryForObject("SELECT count(*)::int FROM sm_data." + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Integer.class);
    }

    static int livePlacements(UUID tenant, UUID instance) {
        return runtime().queryForObject("SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t"
                + " AND instance_id = :i AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Integer.class);
    }

    static long stateVersion(UUID tenant, UUID instance) {
        return runtime().queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance),
                Long.class);
    }

    // ---- the writers the race ITs simulate ----------------------------------------------------------------------

    /**
     * A lifecycle writer (plan 012's undeclare, simulated): holds the instance row on its own connection until released,
     * optionally flipping the lifecycle to {@code abandoned} (V007's trigger is stepped around with
     * {@code SET LOCAL session_replication_role = replica}, which only a superuser may do; the runtime credential under
     * test is never given that).
     *
     * <p>The row is held {@code FOR NO KEY UPDATE}, <b>not</b> {@code FOR UPDATE} (W2 review W2A-4): {@code FOR UPDATE}
     * conflicts with the {@code FOR KEY SHARE} a foreign key takes on {@code instance_state} when a placement or an
     * {@code apply_scope} row is inserted, so a test holding it would make the operation wait on that foreign key even if
     * the operation never asked for the instance lock, and the test could not tell the two apart. {@code FOR NO KEY UPDATE}
     * excludes only an explicit row lock or an update, which is exactly what {@code InventoryService.lockedActive} takes.
     */
    static final class LifecycleWriter implements AutoCloseable {
        private final Connection connection;

        LifecycleWriter(UUID tenant, UUID instance, boolean flipToAbandoned) throws SQLException {
            connection = SmDataPostgres.superuser().getConnection();
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                if (flipToAbandoned) {
                    s.execute("SET LOCAL session_replication_role = replica");
                }
                s.execute("SELECT 1 FROM sm_data.instance_state WHERE tenant_id = '" + tenant + "' AND instance_id = '"
                        + instance + "' FOR NO KEY UPDATE");
                if (flipToAbandoned) {
                    s.executeUpdate("UPDATE sm_data.instance_state SET lifecycle = 'abandoned' WHERE tenant_id = '"
                            + tenant + "' AND instance_id = '" + instance + "'");
                }
            }
        }

        void commit() throws SQLException {
            connection.commit();
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** A held lock on the target's connection row, which the placement insert's trigger (V016) asks for {@code FOR UPDATE}. */
    static final class TargetGate implements AutoCloseable {
        private final Connection connection;

        TargetGate(UUID tenant, UUID target) throws SQLException {
            connection = SmDataPostgres.superuser().getConnection();
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.execute("SELECT 1 FROM sm_data.target_connection WHERE tenant_id = '" + tenant + "' AND target_id = '"
                        + target + "' FOR UPDATE");
            }
        }

        void release() throws SQLException {
            connection.commit();
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /**
     * Blocks until at least {@code count} backends of this database are waiting on a lock, or fails after 15 seconds. This
     * is what makes a race IT deterministic: "both operations have reached their wait" is observed, not slept for.
     */
    static void awaitLockWaiters(int count) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        try (Connection c = SmDataPostgres.superuser().getConnection(); Statement s = c.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND wait_event_type = 'Lock'")) {
                    rs.next();
                    if (rs.getInt(1) >= count) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("fewer than " + count + " backends reached a lock wait within 15 seconds");
    }

    /** The instance's {@code apply_scope} row, created if the instance has never had an admission. */
    static void ensureScopeRow(UUID tenant, UUID instance) {
        runtime().update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
    }

    /** A request row in {@code state}; terminal states carry their timestamp ({@code terminal_has_timestamp}). */
    static UUID request(UUID tenant, UUID instance, UUID placement, String operation, String state) {
        ensureScopeRow(tenant, instance);
        boolean open = List.of("REQUESTED", "RUNNING", "APPLY_INDETERMINATE").contains(state);
        UUID id = UUID.randomUUID();
        runtime().update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, :op, :g, :s, 'operator-1', :now, :state, :terminal)",
                new MapSqlParameterSource().addValue("a", id).addValue("t", tenant).addValue("i", instance)
                        .addValue("p", placement).addValue("op", operation)
                        .addValue("g", "apply".equals(operation) ? 3L : null)
                        .addValue("s", "apply".equals(operation) ? "manual" : null)
                        .addValue("now", java.time.OffsetDateTime.now()).addValue("state", state)
                        .addValue("terminal", open ? null : java.time.OffsetDateTime.now()));
        return id;
    }
}
