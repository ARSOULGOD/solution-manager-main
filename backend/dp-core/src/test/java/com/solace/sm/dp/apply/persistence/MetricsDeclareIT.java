package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.logs.LoggerLevel;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException;
import com.solace.sm.dp.api.metrics.declare.PackageVersionMismatchException;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.ConfigSecretServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-4.1 against a real PostgreSQL, as {@code sm_dp_runtime}: declare writes the version's definitions, the
 * placement's metrics endpoints and the generation-0 active list in {@code reserve}'s own transaction (M-Q5, M-Q14,
 * M-Q15), and every generation the data plane mints is born with a list (a stage carries it; a copy-forward copies the
 * source's). The data plane evaluates nothing here: every list is handed in.
 *
 * <p>(In the persistence test package, not {@code dp.inventory}: the shared Testcontainers harness
 * {@code SmDataPostgres} is package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricsDeclareIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final AtomicInteger PORTS = new AtomicInteger(31000);

    private static NamedParameterJdbcTemplate runtime;
    private static JdbcMetricDefinitionRepository metrics;
    private static InventoryService inventory;
    private static ConfigRecordServiceImpl config;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        metrics = new JdbcMetricDefinitionRepository(runtime);
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        inventory = new InventoryService(tx, new JdbcInstanceStateRepository(runtime),
                new JdbcPlacementRepository(runtime), new JdbcTargetConnectionRepository(runtime),
                SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 51), null)),
                Clock.systemUTC(), metrics);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), TestHashers.keyed(), new Retention(50), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(runtime), metrics);
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static MetricDefinitionSpec metric(String name) {
        return new MetricDefinitionSpec(name, "cache", "gauge", "ratio", "http_json",
                "{\"kind\":\"http_json\",\"path\":\"/api/cache/stats\",\"port\":8081,\"onUnavailable\":\"degrade\"}", 30,
                true);
    }

    private static MetricsDeclaration declaration(String digest, Map<Integer, Integer> ports, String... active) {
        return new MetricsDeclaration(digest, List.of(metric("cache.hit_rate"), metric("cache.size")),
                List.of(new RestartSignalSpec("cache",
                        "{\"kind\":\"http_json\",\"path\":\"/actuator/info\",\"port\":9091,\"onUnavailable\":\"degrade\"}")),
                ports, List.of(active));
    }

    private static int freshHostPort() {
        return PORTS.incrementAndGet();
    }

    private static PlacementSpec placementSpec(int healthPort) {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return new PlacementSpec("sm-" + u, "/srv/sm/" + u, "/app/external/spring/config", "application.yml",
                new HealthProbeSpec("actuator-health", "/actuator/health", healthPort, ProbeStrength.READINESS));
    }

    private static DeclareInstanceCommand declare(UUID target, UUID catalog, String version, PlacementSpec placement,
            MetricsDeclaration declaration) {
        return new DeclareInstanceCommand(UUID.randomUUID(), catalog, "cache", version,
                "inst-" + UUID.randomUUID().toString().substring(0, 8), target, AcquisitionMode.DIRECT_HTTP, placement,
                ACTOR, declaration);
    }

    private static int count(String table, UUID tenant) {
        return runtime.queryForObject("SELECT count(*)::int FROM sm_data." + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Integer.class);
    }

    private static Optional<List<String>> activeList(UUID tenant, UUID instance, long generation) {
        return metrics.activeMetricsFor(tenant, instance, generation);
    }

    private static StageCommand stage(long base, String ttl, String... active) {
        return new StageCommand(base, "cache", "2.4", SELECTION, "{\"cache-1\":{\"ttl\":\"" + ttl + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + ttl + "\"")),
                GenerationOrigin.EDIT, null, ACTOR, null, null, List.of(active));
    }

    private static long stateVersion(UUID tenant, UUID instance) {
        return runtime.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance),
                Long.class);
    }

    /** A declared (and activated) instance with a generation-0 list of {@code active}. */
    private record Declared(UUID tenant, UUID target, UUID catalog, UUID instance, UUID placement) {
        static Declared fresh(String... active) {
            UUID tenant = SmDataPostgres.freshTenant();
            UUID target = InventoryFixtures.target(tenant);
            UUID catalog = UUID.randomUUID();
            InstanceView view = inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                    declaration(DIGEST_A, Map.of(8081, freshHostPort()), active)));
            inventory.activate(tenant, view.instanceId());
            return new Declared(tenant, target, catalog, view.instanceId(), view.placements().get(0).placementId());
        }

        long state() {
            return stateVersion(tenant, instance);
        }

        StageResult stageOk(String ttl, String... names) {
            return config.stage(tenant, instance, stage(state(), ttl, names));
        }

        /** An apply request the way the engine leaves a written one: this placement, this generation, APPLIED. */
        void appliedGeneration(long generation) {
            runtime.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                            + " ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
            runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id,"
                            + " operation, generation, strategy, requested_by, requested_at, state, terminal_at)"
                            + " VALUES (:a, :t, :i, :p, 'apply', :g, 'manual', 'operator-1', :now, 'APPLIED', :now)",
                    new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", tenant)
                            .addValue("i", instance).addValue("p", placement).addValue("g", generation)
                            .addValue("now", java.time.OffsetDateTime.now()));
        }
    }

    // ---- declare -------------------------------------------------------------------------------------------

    @Test
    void declareWritesDefinitionsEndpointsAndGenerationZeroInTheReserveTransaction() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        UUID catalog = UUID.randomUUID();
        int hostA = freshHostPort();
        int hostB = freshHostPort();

        InstanceView view = inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                declaration(DIGEST_A, Map.of(8081, hostA, 9091, hostB), "cache.hit_rate")));

        assertEquals(1, count("package_version_digest", tenant));
        assertEquals(2, metrics.definitionsFor(tenant, catalog, "2.4").size());
        assertEquals(1, metrics.restartSignalsFor(tenant, catalog, "2.4").size());
        UUID placement = view.placements().get(0).placementId();
        assertEquals(Map.of(8081, hostA, 9091, hostB), metrics.endpointsFor(tenant, placement));
        assertEquals(Optional.of(List.of("cache.hit_rate")), activeList(tenant, view.instanceId(), 0),
                "generation 0 is the declare-time set");
    }

    @Test
    void aSecondDeclareOfTheSameVersionWritesNoDefinitionTwiceButItsOwnEndpointsAndList() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        UUID catalog = UUID.randomUUID();
        InstanceView first = inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                declaration(DIGEST_A, Map.of(8081, freshHostPort()), "cache.hit_rate", "cache.size")));
        int definitions = count("metric_definition", tenant);

        InstanceView second = inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                declaration(DIGEST_A, Map.of(8081, freshHostPort()))));

        assertEquals(definitions, count("metric_definition", tenant), "the version's definitions are copied once");
        assertEquals(1, count("package_version_digest", tenant));
        assertEquals(2, count("placement_metrics_endpoint", tenant), "each placement has its own endpoints");
        assertEquals(Optional.of(List.of("cache.hit_rate", "cache.size")), activeList(tenant, first.instanceId(), 0));
        assertEquals(Optional.of(List.of()), activeList(tenant, second.instanceId(), 0),
                "an EMPTY generation-0 list is a list: nothing is collectable, and that is recorded");
    }

    @Test
    void aDeclareThatCarriesNoMetricsDeclarationWritesNoMetricsRowAtAll() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);

        InstanceView view = inventory.reserve(tenant, declare(target, UUID.randomUUID(), "2.4", placementSpec(freshHostPort()),
                MetricsDeclaration.none()));

        for (String table : List.of("package_version_digest", "metric_definition", "metric_restart_signal",
                "placement_metrics_endpoint", "generation_active_metrics")) {
            assertEquals(0, count(table, tenant), table);
        }
        assertEquals(Optional.empty(), activeList(tenant, view.instanceId(), 0));
    }

    // ---- a refusal inside reserve leaves nothing, on either table family ---------------------------------

    private record Snapshot(int instances, int placements, int digests, int definitions, int signals, int endpoints,
            int lists) {
        static Snapshot of(UUID tenant) {
            return new Snapshot(count("instance_state", tenant), count("placement", tenant),
                    count("package_version_digest", tenant), count("metric_definition", tenant),
                    count("metric_restart_signal", tenant), count("placement_metrics_endpoint", tenant),
                    count("generation_active_metrics", tenant));
        }
    }

    @Test
    void aRefusalInsideReserveLeavesNoMetricsRowAndNoPlacement() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        int taken = freshHostPort();
        int takenHealth = freshHostPort();
        inventory.reserve(tenant, declare(target, UUID.randomUUID(), "2.4", placementSpec(takenHealth),
                declaration(DIGEST_A, Map.of(8081, taken))));
        Snapshot before = Snapshot.of(tenant);
        String newVersion = "9.9";   // a version the refused declares would ALSO have recorded, were it not rolled back

        // (a) the metrics host port another live placement on the target already maps
        MetricsPortCollisionException inUse = assertThrows(MetricsPortCollisionException.class,
                () -> inventory.reserve(tenant, declare(target, UUID.randomUUID(), newVersion, placementSpec(freshHostPort()),
                        declaration(DIGEST_B, Map.of(8081, taken), "cache.size"))));
        assertEquals(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IN_USE, inUse.kind());
        assertEquals(before, Snapshot.of(tenant), "no instance, no placement, no digest, no definition, no endpoint, no list");

        // (b) the metrics host port another live placement on the target probes its health on
        MetricsPortCollisionException health = assertThrows(MetricsPortCollisionException.class,
                () -> inventory.reserve(tenant, declare(target, UUID.randomUUID(), newVersion, placementSpec(freshHostPort()),
                        declaration(DIGEST_B, Map.of(8081, takenHealth), "cache.size"))));
        assertEquals(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IS_ANOTHER_PLACEMENTS_HEALTH_PORT,
                health.kind());
        assertEquals(before, Snapshot.of(tenant));

        // (c) THIS placement's health port is a host port another live placement maps for its metrics: the refusal
        // comes from the placement insert, before any metrics row is attempted, and is the same collision class
        MetricsPortCollisionException reverse = assertThrows(MetricsPortCollisionException.class,
                () -> inventory.reserve(tenant, declare(target, UUID.randomUUID(), newVersion, placementSpec(taken),
                        declaration(DIGEST_B, Map.of(8081, freshHostPort()), "cache.size"))));
        assertEquals(MetricsPortCollisionException.Kind.HEALTH_PORT_IS_ANOTHER_PLACEMENTS_METRICS_PORT, reverse.kind());
        assertEquals(before, Snapshot.of(tenant));

        // (d) one declare mapping two source ports to one host port
        int same = freshHostPort();
        MetricsPortCollisionException twice = assertThrows(MetricsPortCollisionException.class,
                () -> inventory.reserve(tenant, declare(target, UUID.randomUUID(), newVersion, placementSpec(freshHostPort()),
                        declaration(DIGEST_B, Map.of(8081, same, 9091, same), "cache.size"))));
        assertEquals(MetricsPortCollisionException.Kind.TWO_SOURCE_PORTS_ONE_HOST_PORT, twice.kind());
        assertEquals(before, Snapshot.of(tenant));
    }

    @Test
    void aPackageVersionMismatchIsRefusedAndWritesNothing() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        UUID catalog = UUID.randomUUID();
        inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                declaration(DIGEST_A, Map.of(8081, freshHostPort()), "cache.size")));
        Snapshot before = Snapshot.of(tenant);

        assertThrows(PackageVersionMismatchException.class,
                () -> inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                        declaration(DIGEST_B, Map.of(8081, freshHostPort()), "cache.size"))));

        assertEquals(before, Snapshot.of(tenant), "the instance and its placement were rolled back with the refusal");
    }

    @Test
    void afterAnAbandonThePlacementIsRetiredItsPortsAreFreeAndItsRowsRemain() {
        // The over-quota path: reserve COMMITS before the control plane registers (BE-D32), so the metrics rows are
        // already committed when the refusal comes, and the runtime role may not delete them (write-once). The sweep's
        // abandon is the same call.
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        UUID catalog = UUID.randomUUID();
        int host = freshHostPort();
        InstanceView doomed = inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                declaration(DIGEST_A, Map.of(8081, host), "cache.size")));
        UUID doomedPlacement = doomed.placements().get(0).placementId();

        inventory.abandon(tenant, doomed.instanceId());

        assertEquals(1, runtime.queryForObject("SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p AND retired_at IS NOT NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", doomedPlacement), Integer.class),
                "the placement is retired, so nothing plans it for collection");
        assertEquals(Map.of(8081, host), metrics.endpointsFor(tenant, doomedPlacement), "its endpoint rows remain");
        assertEquals(Optional.of(List.of("cache.size")), activeList(tenant, doomed.instanceId(), 0),
                "and so does its generation-0 list: harmless and append-only");
        assertEquals(2, metrics.definitionsFor(tenant, catalog, "2.4").size());
        // the host port is free for a later declare on the same target
        InstanceView next = inventory.reserve(tenant, declare(target, catalog, "2.4", placementSpec(freshHostPort()),
                declaration(DIGEST_A, Map.of(8081, host), "cache.size")));
        assertEquals(Map.of(8081, host), metrics.endpointsFor(tenant, next.placements().get(0).placementId()));
        // ... and the runtime role cannot delete what is left (M-Q5's write-once rule is the database's, not a habit)
        assertThrows(RuntimeException.class, () -> runtime.update("DELETE FROM sm_data.generation_active_metrics"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant)));
    }

    // ---- stage ------------------------------------------------------------------------------------------------

    @Test
    void stagingStoresTheListWithItsGenerationAndNeverEditsAnOlderOne() {
        Declared d = Declared.fresh("cache.size");

        StageResult one = d.stageOk("10m", "cache.hit_rate");
        StageResult two = d.stageOk("20m", "cache.hit_rate", "cache.size");
        StageResult three = d.stageOk("30m");

        assertEquals(1, one.generation().generation());
        assertEquals(Optional.of(List.of("cache.size")), activeList(d.tenant, d.instance, 0), "the declare's list is untouched");
        assertEquals(Optional.of(List.of("cache.hit_rate")), activeList(d.tenant, d.instance, 1));
        assertEquals(Optional.of(List.of("cache.hit_rate", "cache.size")), activeList(d.tenant, d.instance, 2));
        assertEquals(Optional.of(List.of()), activeList(d.tenant, d.instance, 3),
                "a generation whose draft makes nothing collectable has an empty list, recorded");
        assertEquals(3, three.generation().generation());
    }

    @Test
    void anUnchangedStageMintsNothingSoItWritesNoListAndKeepsTheOld() {
        Declared d = Declared.fresh();
        d.stageOk("10m", "cache.hit_rate");

        // the same content, another list in the command: guard 1 answers "unchanged" and writes nothing
        StageResult again = d.stageOk("10m", "cache.size");

        assertTrue(again.unchanged());
        assertEquals(Optional.of(List.of("cache.hit_rate")), activeList(d.tenant, d.instance, 1),
                "a list is born with its generation and never edited");
        assertEquals(Optional.empty(), activeList(d.tenant, d.instance, 2));
    }

    @Test
    void aStageRefusedAsStaleWritesNoList() {
        Declared d = Declared.fresh();
        long stale = d.state();
        d.stageOk("10m", "cache.hit_rate");

        assertThrows(StaleStateVersionException.class,
                () -> config.stage(d.tenant, d.instance, stage(stale, "99m", "cache.size")));

        assertEquals(Optional.empty(), activeList(d.tenant, d.instance, 2));
        assertEquals(Optional.of(List.of("cache.hit_rate")), activeList(d.tenant, d.instance, 1));
    }

    @Test
    void theListIsPerTenant() {
        Declared a = Declared.fresh("cache.size");
        Declared b = Declared.fresh();

        assertEquals(Optional.empty(), metrics.activeMetricsFor(b.tenant, a.instance, 0),
                "another tenant's instance reads as nothing (I5)");
        assertEquals(Optional.of(List.of("cache.size")), metrics.activeMetricsFor(a.tenant, a.instance, 0));
    }

    // ---- copy-forward: the list is the source's, never re-evaluated ------------------------------------------

    @Test
    void rollbackReusesTheSourceGenerationsList() {
        Declared d = Declared.fresh();
        d.stageOk("10m", "cache.hit_rate");                     // generation 1
        d.stageOk("20m", "cache.size");                         // generation 2: a different list

        StageResult rolled = config.copyForward(d.tenant, d.instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        assertEquals(3, rolled.generation().generation());
        assertEquals(Optional.of(List.of("cache.hit_rate")), activeList(d.tenant, d.instance, 3),
                "generation 3 is a copy of generation 1, so it is born with generation 1's list, not the latest's");
        assertEquals(Optional.of(List.of("cache.size")), activeList(d.tenant, d.instance, 2), "the source of nothing is untouched");
    }

    @Test
    void restoreRuntimeCopiesTheLatestGenerationsList() {
        Declared d = Declared.fresh();
        d.stageOk("10m", "cache.hit_rate");
        d.stageOk("20m", "cache.size");

        Optional<StageResult> restored = config.copyLatestForward(d.tenant, d.instance, GenerationOrigin.RESTORE_RUNTIME,
                ACTOR);

        assertEquals(3, restored.orElseThrow().generation().generation());
        assertEquals(Optional.of(List.of("cache.size")), activeList(d.tenant, d.instance, 3));
    }

    @Test
    void aConfiguredLogLevelCopiesTheLatestGenerationsListBecauseTheDraftDidNotChange() {
        Declared d = Declared.fresh();
        d.stageOk("10m", "cache.hit_rate", "cache.size");
        // W2-01: a level change refuses to carry a draft the host never received, so generation 1 is written first
        d.appliedGeneration(1);

        Optional<StageResult> leveled = config.copyLatestWithLogLevel(d.tenant, d.instance, d.placement,
                "com.solace.sm.cache", LoggerLevel.DEBUG, ACTOR);

        assertEquals(2, leveled.orElseThrow().generation().generation());
        assertEquals(Optional.of(List.of("cache.hit_rate", "cache.size")), activeList(d.tenant, d.instance, 2));
    }

    @Test
    void aCopyOfAGenerationWithNoRecordedListRecordsAnEmptyOne() {
        // A generation minted before the list existed: simulate it by minting through the repository-less constructor.
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        ConfigRecordServiceImpl legacy = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), TestHashers.keyed(), new Retention(50), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(runtime));
        legacy.stage(tenant, instance, stage(stateVersion(tenant, instance), "10m"));
        legacy.stage(tenant, instance, stage(stateVersion(tenant, instance), "20m"));
        assertEquals(Optional.empty(), activeList(tenant, instance, 1));

        // generation 1 is not the latest, so this is a real copy (guard 1 would answer "unchanged" for the latest)
        StageResult copied = config.copyForward(tenant, instance, 1, GenerationOrigin.ROLLBACK, ACTOR);

        assertEquals(3, copied.generation().generation());
        assertEquals(Optional.of(List.of()), activeList(tenant, instance, 3));
    }

    // ---- the repository-less constructors refuse a list rather than lose it ----------------------------------

    @Test
    void aServiceBuiltWithoutAMetricsRepositoryRefusesAListItWouldLose() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        ConfigRecordServiceImpl legacy = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), TestHashers.keyed(), new Retention(50), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(runtime));

        assertThrows(IllegalStateException.class,
                () -> legacy.stage(tenant, instance, stage(stateVersion(tenant, instance), "10m", "cache.size")));
        assertEquals(List.of(), runtime.queryForList("SELECT generation FROM sm_data.config_generation WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class), "the refusal rolled the generation back");
        assertFalse(MetricDeclarationRows.DISCARDING.activeMetricsFor(tenant, instance, 0).isPresent());
    }

    // ---- the console-secret mint is the second place a generation is born ------------------------------------

    @Test
    void aConsoleSecretStageWritesTheListToo() {
        Declared d = Declared.fresh();
        ConfigSecretServiceImpl secrets = new ConfigSecretServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime),
                SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 52), null)),
                TestHashers.keyed(), new Retention(50), Clock.systemUTC(), metrics);

        StageResult result = secrets.storeAndStage(d.tenant, d.instance, d.state(), "s3cret-value".toCharArray(), ACTOR,
                reference -> new StageCommand(d.state(), "cache", "2.4", SELECTION,
                        "{\"cache-1\":{\"password\":\"secretRef://" + reference + "\"}}",
                        List.of(RenderedEntryValue.secret("cache-1", "cache-engine.password", reference)),
                        GenerationOrigin.EDIT, null, ACTOR, null, null, List.of("cache.hit_rate")));

        assertEquals(1, result.generation().generation());
        assertEquals(Optional.of(List.of("cache.hit_rate")), activeList(d.tenant, d.instance, 1));
    }
}
