package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException;
import com.solace.sm.dp.api.metrics.declare.PackageVersionMismatchException;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-2.2 (sm_data, the metric-definition migration): per-package-version definitions copied once (M-Q5 (a')),
 * the immutable host-side metrics ports of a placement (M-Q14 (a)) and the per-generation active lists (M-Q15 (a)).
 * Every statement runs as {@code sm_dp_runtime} (a privilege proof made as the superuser proves nothing about the
 * credential the application holds), and every refusal is asserted by SQLSTATE AND name.
 *
 * <p>The concurrency tests hold one transaction open on the raw JDBC connection and run the second declare on another
 * thread: "the second waits for the first, then sees its rows" is a claim about two sessions, and a single-threaded test
 * cannot make it. The waiting is observed in {@code pg_stat_activity}, never inferred from a sleep.
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricDefinitionSchemaIT {

    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;
    private static JdbcMetricDefinitionRepository repo;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
        repo = new JdbcMetricDefinitionRepository(runtime);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------

    private static MetricDefinitionSpec metric(String name, String type) {
        return new MetricDefinitionSpec(name, "cache", type, "ratio", "http_json",
                "{\"port\":8081,\"path\":\"/api/cache/stats\",\"select\":\"$.x\"}", 30, true);
    }

    private static MetricsDeclaration declaration(String digest) {
        return new MetricsDeclaration(digest,
                List.of(metric("cache.hit_ratio", "gauge"), metric("cache.requests", "counter")),
                List.of(new RestartSignalSpec("cache", "{\"kind\":\"http_json\",\"path\":\"/actuator/info\"}")),
                Map.of(8081, 28081), List.of("cache.hit_ratio", "cache.requests"));
    }

    private static String freshVersion() {
        return "v-" + UUID.randomUUID();
    }

    private static int count(String table, UUID tenant) {
        return runtime.queryForObject("SELECT count(*)::int FROM sm_data." + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Integer.class);
    }

    private static MapSqlParameterSource placementRow(UUID tenant, UUID placement, UUID instance, UUID target,
            Integer healthPort) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("i", instance)
                .addValue("x", target).addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                .addValue("hk", healthPort == null ? null : "actuator-health")
                .addValue("hp", healthPort == null ? null : "/actuator/health")
                .addValue("hport", healthPort).addValue("hs", healthPort == null ? null : "readiness")
                .addValue("now", OffsetDateTime.now());
    }

    private static final String INSERT_PLACEMENT = "INSERT INTO sm_data.placement (tenant_id, placement_id,"
            + " instance_id, target_id, container_name, remote_config_directory, container_config_path, file_name,"
            + " mechanism, retained_generations, health_kind, health_path, health_port, health_strength, created_at)"
            + " VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config', 'application.yml',"
            + " 'ADDITIONAL_LOCATION', 10, :hk, :hp, :hport, :hs, :now)";

    /** A live placement of {@code instance} on {@code target} with the given health port (null = no probe). */
    private static UUID placement(UUID tenant, UUID instance, UUID target, Integer healthPort) {
        UUID placement = UUID.randomUUID();
        runtime.update(INSERT_PLACEMENT, placementRow(tenant, placement, instance, target, healthPort));
        return placement;
    }

    private static void retire(UUID tenant, UUID placement) {
        runtime.update("UPDATE sm_data.placement SET retired_at = :now WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement)
                        .addValue("now", OffsetDateTime.now()));
    }

    private static void endpoint(UUID tenant, UUID placement, UUID target, int sourcePort, int hostPort) {
        repo.insertEndpoints(tenant, placement, target, Map.of(sourcePort, hostPort), Instant.now());
    }

    private static final String INSERT_ENDPOINT = "INSERT INTO sm_data.placement_metrics_endpoint (tenant_id,"
            + " placement_id, target_id, source_port, host_port, created_at) VALUES (:t, :p, :x, :sp, :hp, :now)";

    private static MapSqlParameterSource endpointRow(UUID tenant, UUID placement, UUID target, int sp, int hp) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("x", target)
                .addValue("sp", sp).addValue("hp", hp).addValue("now", OffsetDateTime.now());
    }

    /** One tenant with one target and one declared instance: what most tests need. */
    private record World(UUID tenant, UUID target, UUID instance) {
        static World fresh() {
            UUID tenant = SmDataPostgres.freshTenant();
            UUID instance = UUID.randomUUID();
            InventoryFixtures.declare(tenant, instance);
            return new World(tenant, InventoryFixtures.target(tenant), instance);
        }
    }

    // ---- a held-open transaction on its own connection ------------------------------------------------------

    private static final class Held implements AutoCloseable {
        final Connection conn;
        final NamedParameterJdbcTemplate jdbc;
        final int pid;

        Held() {
            try {
                conn = SmDataPostgres.runtimeDataSource().getConnection();
                conn.setAutoCommit(false);
                jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(conn, true));
                pid = jdbc.getJdbcOperations().queryForObject("SELECT pg_backend_pid()", Integer.class);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        void commit() {
            try {
                conn.commit();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            try {
                conn.rollback();
                conn.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Runs {@code body} in its own held transaction on another thread; the future holds what it threw, or null. */
    private static CompletableFuture<Throwable> inOtherSession(java.util.function.Consumer<Held> body,
            java.util.concurrent.atomic.AtomicInteger pidOut) {
        return CompletableFuture.supplyAsync(() -> {
            try (Held h = new Held()) {
                pidOut.set(h.pid);
                body.accept(h);
                h.commit();
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
    }

    /** True once that backend is waiting on a lock — observed, not slept for. */
    private static boolean awaitBlocked(java.util.concurrent.atomic.AtomicInteger pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(superuser.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = :p AND wait_event_type = 'Lock')",
                    new MapSqlParameterSource("p", pid.get()), Boolean.class))) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    // ---- M-Q5: definitions are copied once per package version ------------------------------------------------

    @Test
    void definitionsAreWriteOncePerPackageVersion() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID solution = UUID.randomUUID();
        String version = freshVersion();

        int written = repo.insertDefinitionsIfAbsent(tenant, solution, version, declaration(DIGEST_A), Instant.now());

        assertEquals(3, written, "two definitions and one restart signal");
        assertEquals(List.of("cache.hit_ratio", "cache.requests"),
                repo.definitionsFor(tenant, solution, version).stream().map(MetricDefinitionSpec::metricName).toList());
        assertEquals(List.of("cache"),
                repo.restartSignalsFor(tenant, solution, version).stream().map(RestartSignalSpec::componentType)
                        .toList());
        MetricDefinitionSpec stored = repo.definitionsFor(tenant, solution, version).get(1);
        assertEquals("counter", stored.metricType());
        assertEquals(30, stored.intervalSeconds());
        assertTrue(stored.defaultChart());
        assertTrue(stored.sourceJson().contains("\"/api/cache/stats\""), stored.sourceJson());

        MapSqlParameterSource key = new MapSqlParameterSource().addValue("t", tenant).addValue("s", solution)
                .addValue("v", version);
        for (String sql : List.of(
                "UPDATE sm_data.metric_definition SET unit = 'x' WHERE tenant_id = :t",
                "DELETE FROM sm_data.metric_definition WHERE tenant_id = :t",
                "UPDATE sm_data.metric_restart_signal SET component_type = 'x' WHERE tenant_id = :t",
                "DELETE FROM sm_data.metric_restart_signal WHERE tenant_id = :t",
                "UPDATE sm_data.package_version_digest SET package_digest = '" + DIGEST_B + "' WHERE tenant_id = :t",
                "DELETE FROM sm_data.package_version_digest WHERE tenant_id = :t")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(sql, key), sql);
        }
        assertEquals(DIGEST_A, runtime.queryForObject("SELECT package_digest FROM sm_data.package_version_digest"
                + " WHERE tenant_id = :t AND catalog_solution_id = :s AND package_version = :v", key, String.class));
    }

    @Test
    void aSecondDeclareOfTheSameVersionWritesNothingNew() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID solution = UUID.randomUUID();
        String version = freshVersion();
        repo.insertDefinitionsIfAbsent(tenant, solution, version, declaration(DIGEST_A), Instant.now());
        int definitions = count("metric_definition", tenant);
        int signals = count("metric_restart_signal", tenant);

        int again = repo.insertDefinitionsIfAbsent(tenant, solution, version, declaration(DIGEST_A), Instant.now());

        assertEquals(0, again, "an idempotent retry writes nothing");
        assertEquals(definitions, count("metric_definition", tenant));
        assertEquals(signals, count("metric_restart_signal", tenant));
        assertEquals(1, count("package_version_digest", tenant));
    }

    @Test
    void aDigestMismatchForAKnownVersionIsRefused() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID solution = UUID.randomUUID();
        String version = freshVersion();
        repo.insertDefinitionsIfAbsent(tenant, solution, version, declaration(DIGEST_A), Instant.now());

        // the service half: a different digest for a known version, with a different set of definitions
        MetricsDeclaration other = new MetricsDeclaration(DIGEST_B, List.of(metric("cache.extra", "gauge")),
                List.of(), Map.of(), List.of());
        assertThrows(PackageVersionMismatchException.class,
                () -> repo.insertDefinitionsIfAbsent(tenant, solution, version, other, Instant.now()));
        assertEquals(2, count("metric_definition", tenant), "nothing was added by the refusal");

        // the database half, for every writer that is not the service: a child row whose digest is not the recorded one
        MapSqlParameterSource row = new MapSqlParameterSource().addValue("t", tenant).addValue("s", solution)
                .addValue("v", version).addValue("d", DIGEST_B);
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "package_digest_matches", () -> runtime.update(
                "INSERT INTO sm_data.metric_definition (tenant_id, catalog_solution_id, package_version, metric_name,"
                        + " component_type, metric_type, unit, source_kind, source, default_chart, package_digest)"
                        + " VALUES (:t, :s, :v, 'cache.sneaky', 'cache', 'gauge', 'x', 'http_json', '{}', false, :d)",
                row), "a definition");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "package_digest_matches", () -> runtime.update(
                "INSERT INTO sm_data.metric_restart_signal (tenant_id, catalog_solution_id, package_version,"
                        + " component_type, source, package_digest) VALUES (:t, :s, :v, 'other', '{}', :d)", row),
                "a restart signal");
        assertEquals(2, count("metric_definition", tenant));
    }

    @Test
    void theDigestMustHaveTheSha256Shape() {
        UUID tenant = SmDataPostgres.freshTenant();
        for (String bad : List.of("sha256:" + "A".repeat(64), "sha256:" + "a".repeat(63), "md5:" + "a".repeat(64), "")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "package_digest_grammar", () -> runtime.update(
                    "INSERT INTO sm_data.package_version_digest (tenant_id, catalog_solution_id, package_version,"
                            + " package_digest, created_at) VALUES (:t, :s, 'v', :d, :now)",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("s", UUID.randomUUID())
                            .addValue("d", bad).addValue("now", OffsetDateTime.now())), bad);
        }
    }

    @Test
    void aDefinitionIsRefusedWhenItsFieldsLeaveTheirGrammar() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID solution = UUID.randomUUID();
        String version = freshVersion();
        repo.insertDefinitionsIfAbsent(tenant, solution, version,
                new MetricsDeclaration(DIGEST_A, List.of(), List.of(), Map.of(), List.of()), Instant.now());
        record Bad(String constraint, MetricDefinitionSpec spec) { }
        MetricDefinitionSpec ok = metric("cache.ok", "gauge");
        List<Bad> bads = List.of(
                new Bad("metric_name_grammar", new MetricDefinitionSpec("Cache Hit", "cache", "gauge", "x", "http_json", "{}", 30, false)),
                new Bad("metric_name_grammar", new MetricDefinitionSpec("9lives", "cache", "gauge", "x", "http_json", "{}", 30, false)),
                new Bad("metric_type_known", new MetricDefinitionSpec(ok.metricName(), "cache", "summary", "x", "http_json", "{}", 30, false)),
                new Bad("metric_unit_length", new MetricDefinitionSpec(ok.metricName(), "cache", "gauge", "", "http_json", "{}", 30, false)),
                new Bad("metric_unit_length", new MetricDefinitionSpec(ok.metricName(), "cache", "gauge", "u".repeat(65), "http_json", "{}", 30, false)),
                new Bad("metric_source_kind_known", new MetricDefinitionSpec(ok.metricName(), "cache", "gauge", "x", "carrier_pigeon", "{}", 30, false)),
                new Bad("metric_interval_range", new MetricDefinitionSpec(ok.metricName(), "cache", "gauge", "x", "http_json", "{}", 9, false)),
                new Bad("metric_interval_range", new MetricDefinitionSpec(ok.metricName(), "cache", "gauge", "x", "http_json", "{}", 3601, false)));
        for (Bad b : bads) {
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, b.constraint(), () -> repo.insertDefinitionsIfAbsent(
                    tenant, solution, version,
                    new MetricsDeclaration(DIGEST_A, List.of(b.spec()), List.of(), Map.of(), List.of()), Instant.now()));
        }
        assertEquals(0, count("metric_definition", tenant), "nothing was written by any refusal");
        // a pushed metric has no interval
        assertEquals(1, repo.insertDefinitionsIfAbsent(tenant, solution, version, new MetricsDeclaration(DIGEST_A,
                List.of(new MetricDefinitionSpec("cache.pushed", "cache", "counter", "events", "solace_event", "{}", null, false)),
                List.of(), Map.of(), List.of()), Instant.now()));
        assertNull(repo.definitionsFor(tenant, solution, version).get(0).intervalSeconds());
    }

    @Test
    void aFailedDeclareRollsBackItsDefinitionsWithThePlacement() {
        World w = World.fresh();
        UUID solution = UUID.randomUUID();
        String version = freshVersion();
        UUID holder = placement(w.tenant(), w.instance(), w.target(), 18081);
        endpoint(w.tenant(), holder, w.target(), 8081, 28081);
        UUID newPlacement = UUID.randomUUID();

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(
                SmDataPostgres.runtimeDataSource()));
        // the same DataSource instance, so the fixture-style template below JOINS the transaction (SmDataPostgres)
        MetricsPortCollisionException refused = assertThrows(MetricsPortCollisionException.class,
                () -> tx.executeWithoutResult(status -> {
                    runtime.update(INSERT_PLACEMENT, placementRow(w.tenant(), newPlacement, w.instance(), w.target(), 18082));
                    repo.insertDefinitionsIfAbsent(w.tenant(), solution, version, declaration(DIGEST_A), Instant.now());
                    repo.insertEndpoints(w.tenant(), newPlacement, w.target(), Map.of(8081, 28081), Instant.now());
                }));

        assertEquals(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IN_USE, refused.kind());
        assertEquals(0, count("package_version_digest", w.tenant()), "the digest row rolled back with the placement");
        assertEquals(0, count("metric_definition", w.tenant()));
        assertEquals(0, count("metric_restart_signal", w.tenant()));
        assertEquals(0, runtime.queryForObject("SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", w.tenant())
                .addValue("p", newPlacement), Integer.class), "…and the placement itself");
    }

    // ---- M-Q14: host-side ports, collisions on one target --------------------------------------------------------

    @Test
    void aMetricsHostPortTakenByAnotherLivePlacementOnTheTargetIsRefused() {
        World w = World.fresh();
        UUID first = placement(w.tenant(), w.instance(), w.target(), 18081);
        UUID second = placement(w.tenant(), w.instance(), w.target(), 18082);
        endpoint(w.tenant(), first, w.target(), 8081, 28081);

        MetricsPortCollisionException e = assertThrows(MetricsPortCollisionException.class,
                () -> endpoint(w.tenant(), second, w.target(), 8081, 28081));
        assertEquals(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IN_USE, e.kind());
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "metrics_host_port_in_use",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), second, w.target(), 8081, 28081)));
        assertEquals(Map.of(), repo.endpointsFor(w.tenant(), second), "nothing was written by the refusal");
        // another host port for the same source port is fine, and so is the same host port on ANOTHER target
        endpoint(w.tenant(), second, w.target(), 8081, 28082);
        UUID otherTarget = InventoryFixtures.target(w.tenant());
        UUID third = placement(w.tenant(), w.instance(), otherTarget, 18081);
        endpoint(w.tenant(), third, otherTarget, 8081, 28081);
        assertEquals(Map.of(8081, 28082), repo.endpointsFor(w.tenant(), second));
        assertEquals(Map.of(8081, 28081), repo.endpointsFor(w.tenant(), third));
    }

    @Test
    void aMetricsHostPortEqualToAnotherLivePlacementsHealthPortIsRefused() {
        World w = World.fresh();
        placement(w.tenant(), w.instance(), w.target(), 28081);                 // its health port is 28081
        UUID second = placement(w.tenant(), w.instance(), w.target(), 18082);

        MetricsPortCollisionException e = assertThrows(MetricsPortCollisionException.class,
                () -> endpoint(w.tenant(), second, w.target(), 8081, 28081));

        assertEquals(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IS_ANOTHER_PLACEMENTS_HEALTH_PORT, e.kind());
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "metrics_host_port_is_a_health_port",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), second, w.target(), 8081, 28081)));
    }

    @Test
    void aPlacementMayShareItsOwnHealthPortWithItsMetricsPort() {
        World w = World.fresh();
        UUID cache = placement(w.tenant(), w.instance(), w.target(), 18081);   // Cache: 8081 serves both

        endpoint(w.tenant(), cache, w.target(), 8081, 18081);

        assertEquals(Map.of(8081, 18081), repo.endpointsFor(w.tenant(), cache));
    }

    @Test
    void aPlacementCannotMapTwoSourcePortsToOneHostPort() {
        World w = World.fresh();
        UUID cache = placement(w.tenant(), w.instance(), w.target(), 18081);

        MetricsPortCollisionException e = assertThrows(MetricsPortCollisionException.class,
                () -> repo.insertEndpoints(w.tenant(), cache, w.target(), Map.of(8081, 28081, 9090, 28081), Instant.now()));

        assertEquals(MetricsPortCollisionException.Kind.TWO_SOURCE_PORTS_ONE_HOST_PORT, e.kind());
        assertEquals(Map.of(), repo.endpointsFor(w.tenant(), cache), "one statement: the refusal leaves not even the first row");
        endpoint(w.tenant(), cache, w.target(), 8081, 28081);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_metrics_host_port_once",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), cache, w.target(), 9090, 28081)));
        UUID bare = placement(w.tenant(), w.instance(), w.target(), 18090);
        repo.insertEndpoints(w.tenant(), bare, w.target(), Map.of(), Instant.now());   // an empty map is not an error
        assertEquals(Map.of(), repo.endpointsFor(w.tenant(), bare));
    }

    @Test
    void aNewPlacementsHealthPortEqualToAnotherLiveMetricsPortIsRefused() {
        World w = World.fresh();
        UUID first = placement(w.tenant(), w.instance(), w.target(), 18081);
        endpoint(w.tenant(), first, w.target(), 8081, 28081);
        UUID second = UUID.randomUUID();

        PSQLException refused = Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "health_port_is_a_metrics_port",
                () -> runtime.update(INSERT_PLACEMENT,
                        placementRow(w.tenant(), second, w.instance(), w.target(), 28081)));

        assertFalse(refused.getMessage().contains(first.toString()), "the message names the class, not the other placement");
        assertEquals(Optional.of(MetricsPortCollisionException.Kind.HEALTH_PORT_IS_ANOTHER_PLACEMENTS_METRICS_PORT),
                JdbcMetricDefinitionRepository.collisionOf(
                                PgConstraintViolation.from(refused).orElseThrow()).map(MetricsPortCollisionException::kind),
                "plan 010's placement insert surfaces the raw violation; this maps it for the declare (P11-4.1)");
        // a placement with no probe, or another port, is fine
        placement(w.tenant(), w.instance(), w.target(), null);
        placement(w.tenant(), w.instance(), w.target(), 28082);
    }

    @Test
    void aRetiredPlacementFreesItsMetricsPorts() {
        World w = World.fresh();
        UUID first = placement(w.tenant(), w.instance(), w.target(), 28091);
        endpoint(w.tenant(), first, w.target(), 8081, 28081);
        UUID second = placement(w.tenant(), w.instance(), w.target(), 18082);
        assertThrows(MetricsPortCollisionException.class, () -> endpoint(w.tenant(), second, w.target(), 8081, 28081));
        assertThrows(MetricsPortCollisionException.class, () -> endpoint(w.tenant(), second, w.target(), 8081, 28091));

        retire(w.tenant(), first);

        endpoint(w.tenant(), second, w.target(), 8081, 28081);
        placement(w.tenant(), w.instance(), w.target(), 28091);                // …and its health port too
        assertEquals(Map.of(8081, 28081), repo.endpointsFor(w.tenant(), second));
        assertEquals(Map.of(8081, 28081), repo.endpointsFor(w.tenant(), first), "the retired placement's rows are kept");
    }

    @Test
    void anEndpointsTargetMustBeItsPlacementsTarget() {
        World w = World.fresh();
        UUID otherTarget = InventoryFixtures.target(w.tenant());
        UUID cache = placement(w.tenant(), w.instance(), w.target(), 18081);

        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "endpoint_has_placement_on_target",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), cache, otherTarget, 8081, 28081)),
                "another registered target");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "endpoint_has_placement_on_target",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), cache, UUID.randomUUID(), 8081, 28081)),
                "an unregistered target");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "endpoint_has_placement_on_target",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), UUID.randomUUID(), w.target(), 8081, 28081)),
                "an undeclared placement");
        assertEquals(Map.of(), repo.endpointsFor(w.tenant(), cache));
    }

    @Test
    void anEndpointPortIsATcpPortAndTheRowIsImmutable() {
        World w = World.fresh();
        UUID cache = placement(w.tenant(), w.instance(), w.target(), 18081);
        for (int bad : new int[] {0, 65536, -1}) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "metrics_source_port_range", () -> runtime.update(
                    INSERT_ENDPOINT, endpointRow(w.tenant(), cache, w.target(), bad, 28081)), "source " + bad);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "metrics_host_port_range", () -> runtime.update(
                    INSERT_ENDPOINT, endpointRow(w.tenant(), cache, w.target(), 8081, bad)), "host " + bad);
        }
        endpoint(w.tenant(), cache, w.target(), 8081, 28081);
        MapSqlParameterSource key = new MapSqlParameterSource("t", w.tenant());
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.placement_metrics_endpoint SET host_port = 28082 WHERE tenant_id = :t", key));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.placement_metrics_endpoint WHERE tenant_id = :t", key));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_metrics_endpoint_pkey", () -> runtime.update(
                INSERT_ENDPOINT, endpointRow(w.tenant(), cache, w.target(), 8081, 28082)), "one row per source port");
    }

    @Test
    void theCollisionMessageNamesTheClassNeverTheOtherInstance() {
        World w = World.fresh();
        UUID first = placement(w.tenant(), w.instance(), w.target(), 18081);
        UUID second = placement(w.tenant(), w.instance(), w.target(), 18082);
        endpoint(w.tenant(), first, w.target(), 8081, 29555);

        MetricsPortCollisionException e = assertThrows(MetricsPortCollisionException.class,
                () -> endpoint(w.tenant(), second, w.target(), 8081, 29555));
        PSQLException raw = Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "metrics_host_port_in_use",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(w.tenant(), second, w.target(), 8081, 29555)));

        for (String text : List.of(e.getMessage(), raw.getMessage())) {
            assertFalse(text.contains(first.toString()), text);
            assertFalse(text.contains(w.instance().toString()), text);
            assertFalse(text.contains(w.target().toString()), text);
            assertFalse(text.contains("29555"), "not even the value: " + text);
        }
        assertNull(raw.getServerErrorMessage().getDetail(), "no DETAIL: PostgreSQL's would echo the rejected key");
        PgConstraintViolation reshaped = PgConstraintViolation.from(raw).orElseThrow();
        assertNull(reshaped.getCause(), "no driver exception crosses the boundary");
    }

    // ---- the races: both directions, with the second session observed waiting ---------------------------------------

    @Test
    void twoConcurrentDeclaresOnOneTargetCannotBothTakeOnePort() throws Exception {
        World w = World.fresh();
        UUID a = placement(w.tenant(), w.instance(), w.target(), 18081);
        UUID b = placement(w.tenant(), w.instance(), w.target(), 18082);

        try (Held first = new Held()) {
            first.jdbc.update(INSERT_ENDPOINT, endpointRow(w.tenant(), a, w.target(), 8081, 28081));   // held open
            java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
            CompletableFuture<Throwable> second = inOtherSession(h ->
                    h.jdbc.update(INSERT_ENDPOINT, endpointRow(w.tenant(), b, w.target(), 8081, 28081)), pid);

            assertTrue(awaitBlocked(pid), "the second declare must WAIT for the first, not slip past its uncommitted row");
            assertFalse(second.isDone());
            first.commit();

            Throwable refused = second.get(10, TimeUnit.SECONDS);
            assertNotNull(refused, "after the first commits the second re-reads and is refused");
            assertEquals("23505", Refusals.unwrap(refused).getSQLState());
        }
        assertEquals(Map.of(8081, 28081), repo.endpointsFor(w.tenant(), a));
        assertEquals(Map.of(), repo.endpointsFor(w.tenant(), b));
    }

    @Test
    void twentyRacingDeclaresNeverBothCommit() throws Exception {
        int broken = 0;
        for (int i = 0; i < 20; i++) {
            World w = World.fresh();
            UUID a = placement(w.tenant(), w.instance(), w.target(), 18081);
            UUID b = placement(w.tenant(), w.instance(), w.target(), 18082);
            CyclicBarrier start = new CyclicBarrier(2);
            List<CompletableFuture<Throwable>> both = new ArrayList<>();
            for (UUID p : List.of(a, b)) {
                both.add(CompletableFuture.supplyAsync(() -> {
                    try (Held h = new Held()) {
                        start.await(10, TimeUnit.SECONDS);
                        h.jdbc.update(INSERT_ENDPOINT, endpointRow(w.tenant(), p, w.target(), 8081, 28081));
                        Thread.sleep(40);                                  // widen the window a lock must close
                        h.commit();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            long committed = both.stream().map(f -> f.join()).filter(t -> t == null).count();
            int rows = runtime.queryForObject("SELECT count(*)::int FROM sm_data.placement_metrics_endpoint"
                    + " WHERE tenant_id = :t AND host_port = 28081", new MapSqlParameterSource("t", w.tenant()),
                    Integer.class);
            if (committed != 1 || rows != 1) {
                broken++;
            }
        }
        System.out.println("MEASURED: " + broken + " of 20 racing interleavings let two declares take one host port");
        assertEquals(0, broken, "exactly one of two racing declares may take the port");
    }

    @Test
    void aConcurrentHealthPortAndMetricsPortOnOneTargetCannotBothCommit() throws Exception {
        // (i): B's placement (health 28081) is inserted and held open; then A's endpoint (host 28081) arrives.
        {
            World w = World.fresh();
            UUID a = placement(w.tenant(), w.instance(), w.target(), 18081);
            UUID bPlacement = UUID.randomUUID();
            try (Held first = new Held()) {
                first.jdbc.update(INSERT_PLACEMENT, placementRow(w.tenant(), bPlacement, w.instance(), w.target(), 28081));
                java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
                CompletableFuture<Throwable> second = inOtherSession(h ->
                        h.jdbc.update(INSERT_ENDPOINT, endpointRow(w.tenant(), a, w.target(), 8081, 28081)), pid);

                assertTrue(awaitBlocked(pid), "(i) the endpoint must wait for the uncommitted placement");
                first.commit();

                Throwable refused = second.get(10, TimeUnit.SECONDS);
                assertNotNull(refused, "(i) exactly one commits: the endpoint is refused once the placement is visible");
                assertEquals("23505", Refusals.unwrap(refused).getSQLState());
                assertEquals(Map.of(), repo.endpointsFor(w.tenant(), a));
            }
        }
        // (ii): A's endpoint (host 28081) is held open; then B's placement (health 28081) arrives.
        {
            World w = World.fresh();
            UUID a = placement(w.tenant(), w.instance(), w.target(), 18081);
            UUID bPlacement = UUID.randomUUID();
            try (Held first = new Held()) {
                first.jdbc.update(INSERT_ENDPOINT, endpointRow(w.tenant(), a, w.target(), 8081, 28081));
                java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
                CompletableFuture<Throwable> second = inOtherSession(h ->
                        h.jdbc.update(INSERT_PLACEMENT,
                                placementRow(w.tenant(), bPlacement, w.instance(), w.target(), 28081)), pid);

                assertTrue(awaitBlocked(pid), "(ii) the placement must wait for the uncommitted endpoint");
                first.commit();

                Throwable refused = second.get(10, TimeUnit.SECONDS);
                assertNotNull(refused, "(ii) exactly one commits: the placement is refused once the endpoint is visible");
                assertEquals("23505", Refusals.unwrap(refused).getSQLState());
                assertEquals(0, runtime.queryForObject("SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t"
                        + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", w.tenant())
                        .addValue("p", bPlacement), Integer.class));
            }
        }
    }

    // ---- M-Q15: the active list ------------------------------------------------------------------------------------

    @Test
    void theActiveListIsImmutable() {
        World w = World.fresh();
        repo.insertActiveMetrics(w.tenant(), w.instance(), 0, List.of("cache.hit_ratio", "cache.requests"), Instant.now());

        assertEquals(Optional.of(List.of("cache.hit_ratio", "cache.requests")),
                repo.activeMetricsFor(w.tenant(), w.instance(), 0));
        assertEquals(Optional.empty(), repo.activeMetricsFor(w.tenant(), w.instance(), 1));
        MapSqlParameterSource key = new MapSqlParameterSource("t", w.tenant());
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.generation_active_metrics SET metric_names = ARRAY['x'] WHERE tenant_id = :t", key));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.generation_active_metrics WHERE tenant_id = :t", key));
        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, "generation_active_metrics_pkey",
                () -> repo.insertActiveMetrics(w.tenant(), w.instance(), 0, List.of("other"), Instant.now()));
        assertEquals(Optional.of(List.of("cache.hit_ratio", "cache.requests")),
                repo.activeMetricsFor(w.tenant(), w.instance(), 0), "a refused rewrite changed nothing");
        // generation 5 is its own immutable row; a negative generation is not a generation; an empty list is a list
        repo.insertActiveMetrics(w.tenant(), w.instance(), 5, List.of(), Instant.now());
        assertEquals(Optional.of(List.of()), repo.activeMetricsFor(w.tenant(), w.instance(), 5));
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "active_metrics_generation_nonneg",
                () -> repo.insertActiveMetrics(w.tenant(), w.instance(), -1, List.of("x"), Instant.now()));
        Refusals.assertViolation(Refusals.FOREIGN_KEY_VIOLATION, "active_metrics_has_instance",
                () -> repo.insertActiveMetrics(w.tenant(), UUID.randomUUID(), 0, List.of("x"), Instant.now()));
    }

    // ---- tenancy -----------------------------------------------------------------------------------------------

    @Test
    void noRowNamesAnotherTenant() {
        World a = World.fresh();
        World b = World.fresh();
        UUID solution = UUID.randomUUID();
        String version = freshVersion();
        UUID cache = placement(a.tenant(), a.instance(), a.target(), 18081);
        repo.insertDefinitionsIfAbsent(a.tenant(), solution, version, declaration(DIGEST_A), Instant.now());
        endpoint(a.tenant(), cache, a.target(), 8081, 28081);
        repo.insertActiveMetrics(a.tenant(), a.instance(), 0, List.of("cache.hit_ratio"), Instant.now());

        assertEquals(List.of(), repo.definitionsFor(b.tenant(), solution, version));
        assertEquals(List.of(), repo.restartSignalsFor(b.tenant(), solution, version));
        assertEquals(Map.of(), repo.endpointsFor(b.tenant(), cache));
        assertEquals(Optional.empty(), repo.activeMetricsFor(b.tenant(), a.instance(), 0));

        // another tenant's version is its own: the same (solution, version) under B is a first declare with its own digest
        assertEquals(3, repo.insertDefinitionsIfAbsent(b.tenant(), solution, version, declaration(DIGEST_B), Instant.now()));
        assertEquals(DIGEST_A, runtime.queryForObject("SELECT package_digest FROM sm_data.package_version_digest"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", a.tenant()), String.class));

        // and no row of B may reference A's: the composite foreign keys all lead with tenant_id
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "endpoint_has_placement_on_target",
                () -> runtime.update(INSERT_ENDPOINT, endpointRow(b.tenant(), cache, a.target(), 8081, 28083)));
        Refusals.assertViolation(Refusals.FOREIGN_KEY_VIOLATION, "active_metrics_has_instance",
                () -> repo.insertActiveMetrics(b.tenant(), a.instance(), 7, List.of("x"), Instant.now()));
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "metric_definition_has_version", () -> runtime.update(
                "INSERT INTO sm_data.metric_definition (tenant_id, catalog_solution_id, package_version, metric_name,"
                        + " component_type, metric_type, unit, source_kind, source, default_chart, package_digest)"
                        + " VALUES (:t, :s, 'never-declared', 'cache.x', 'cache', 'gauge', 'x', 'http_json', '{}', false, :d)",
                new MapSqlParameterSource().addValue("t", b.tenant()).addValue("s", solution).addValue("d", DIGEST_A)));
        assertEquals(0, count("placement_metrics_endpoint", b.tenant()));
    }

    // ---- readiness -----------------------------------------------------------------------------------------------

    @Test
    void readinessRefusesASchemaWithoutTheMigration() {
        // an install that skipped the migration step altogether: nothing of this area exists, and the message says what
        MetricsDb none = MetricsDb.fresh("md_ready_none", MetricsDb.Migrated.NOTHING);
        IllegalStateException unmigrated = assertThrows(IllegalStateException.class,
                () -> MetricDefinitionPersistenceConfiguration.verify(none.runtime()));
        assertTrue(unmigrated.getMessage().contains("metric_definitions_and_endpoints"),
                "the message names the missing migration: " + unmigrated.getMessage());
        assertTrue(unmigrated.getMessage().contains("SmDataMigrationMain"), unmigrated.getMessage());
        for (String object : allObjects()) {
            assertTrue(unmigrated.getMessage().contains(object), object + " in: " + unmigrated.getMessage());
        }

        // a migrated schema passes, with nothing missing
        MetricsDb whole = MetricsDb.fresh("md_ready_whole", MetricsDb.Migrated.SM_DATA_ONLY);
        MetricDefinitionPersistenceConfiguration.verify(whole.runtime());
        assertEquals(List.of(), MetricDefinitionPersistenceConfiguration.missing(whole.runtime()));
    }

    /** Each table and each trigger, lost ALONE in a database of its own, is refused and named: a count would miss a swap. */
    @Test
    void readinessRefusesTheLossOfAnySingleTableOrTrigger() {
        for (String object : allObjects()) {
            String name = object.substring(object.indexOf(' ') + 1);
            MetricsDb db = MetricsDb.fresh("md_ready_" + name.substring(0, Math.min(name.length(), 24)),
                    MetricsDb.Migrated.SM_DATA_ONLY);
            SqlScript.execute(db.superuser(), object.startsWith("table ")
                    ? "DROP TABLE sm_data." + name + " CASCADE"
                    : "DROP TRIGGER " + name + " ON sm_data." + (name.startsWith("metric_definition")
                            ? "metric_definition" : "metric_restart_signal"));
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> MetricDefinitionPersistenceConfiguration.verify(db.runtime()), "losing " + object);
            assertTrue(refused.getMessage().contains(object), "names " + object + ": " + refused.getMessage());
            assertTrue(refused.getMessage().contains("metric_definitions_and_endpoints"), refused.getMessage());
        }
    }

    /** Written out here, NOT read from the class under test: a test that derives its list from the code cannot see the code lose an entry. */
    private static List<String> allObjects() {
        return List.of("table package_version_digest", "table metric_definition", "table metric_restart_signal",
                "table placement_metrics_endpoint", "table generation_active_metrics",
                "trigger metric_definition_digest", "trigger metric_restart_signal_digest");
    }
}
