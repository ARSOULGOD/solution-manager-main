package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-2.1: the shape and the privileges of {@code sm_metrics} V001, asserted as the roles that will use it.
 * Everything here is non-destructive and runs on the shared database; the tests that detach, lock or plant into the
 * runway live in {@link PartitionRunwayIT} on a database of their own.
 *
 * <p>Every refusal is asserted with its SQLSTATE and, where PostgreSQL supplies one, its constraint NAME (the
 * design's harness rule, {@link Refusals}).
 */
@Testcontainers(disabledWithoutDocker = true)
class SmMetricsSchemaIT {

    private static final MetricsDb DB = MetricsDb.shared();

    @Test
    void phase0CreatesSmMetricsOwnedBySmDpOwnerWithRuntimeUsageOnly() {
        NamedParameterJdbcTemplate su = DB.superuserJdbc();

        assertEquals("sm_dp_owner",
                su.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'sm_metrics'",
                        Map.of(), String.class), "M-Q19: DP-owned, owned like sm_data");
        assertEquals(Boolean.TRUE, su.queryForObject(
                "SELECT has_schema_privilege('sm_dp_runtime', 'sm_metrics', 'USAGE')", Map.of(), Boolean.class));
        assertEquals(Boolean.FALSE, su.queryForObject(
                "SELECT has_schema_privilege('sm_dp_runtime', 'sm_metrics', 'CREATE')", Map.of(), Boolean.class),
                "the runtime role may not create objects in sm_metrics");
        assertEquals(List.of(), su.queryForList(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'sm_metrics' AND c.relkind IN ('r','p','i')"
                        + "   AND pg_get_userbyid(c.relowner) <> 'sm_dp_owner'", Map.of(), String.class),
                "every sm_metrics table and index is born owned by sm_dp_owner, not the migrator login");
    }

    @Test
    void theRuntimeRoleCannotCreateDropOrAlterAnythingInSmMetrics() {
        NamedParameterJdbcTemplate rt = DB.runtimeJdbc();
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("CREATE TABLE sm_metrics.smuggled (x int)"));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("DROP TABLE sm_metrics.series CASCADE"));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("ALTER TABLE sm_metrics.series ADD COLUMN x int"));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("ALTER TABLE sm_metrics.metrics_raw DETACH PARTITION "
                        + "sm_metrics.metrics_raw_2000_01"));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("CREATE TABLE public.smuggled (x int)"));
    }

    @Test
    void theRuntimeRoleCanExecuteTheRunwayFunctionAndNothingElseOwnerLevel() {
        NamedParameterJdbcTemplate su = DB.superuserJdbc();

        List<Map<String, Object>> functions = su.queryForList(
                "SELECT p.proname, pg_get_userbyid(p.proowner) AS owner, p.prosecdef, p.pronargs"
                        + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'sm_metrics'",
                Map.of());
        assertEquals(1, functions.size(), "the one function is the whole owner-level surface: " + functions);
        assertEquals("ensure_metric_partitions", functions.get(0).get("proname"));
        assertEquals("sm_dp_owner", functions.get(0).get("owner"));
        assertEquals(Boolean.TRUE, functions.get(0).get("prosecdef"), "SECURITY DEFINER (M-Q13 c')");
        assertEquals(0, ((Number) functions.get(0).get("pronargs")).intValue(), "NO argument: no caller input reaches the DDL (OR-77)");

        assertNotNull(DB.runtimeJdbc().queryForObject("SELECT sm_metrics.ensure_metric_partitions()", Map.of(),
                java.sql.Date.class), "the runtime role can EXECUTE it");
        // …and that is all the owner-level power it has: the same session still cannot do the DDL itself.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> DB.runtimeJdbc().getJdbcTemplate().execute("SET ROLE sm_dp_owner"));
    }

    @Test
    void publicCannotExecuteTheRunwayFunction() {
        NamedParameterJdbcTemplate su = DB.superuserJdbc();
        if (Boolean.FALSE.equals(su.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sm_metrics_probe')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(DB.superuser(), "CREATE ROLE sm_metrics_probe NOLOGIN");
        }
        assertEquals(Boolean.FALSE, su.queryForObject(
                "SELECT has_function_privilege('sm_metrics_probe', 'sm_metrics.ensure_metric_partitions()', 'EXECUTE')",
                Map.of(), Boolean.class), "MF2: the default PUBLIC EXECUTE was revoked in the creating migration");
        assertEquals(Boolean.FALSE, su.queryForObject(
                "SELECT coalesce(bool_or(a.grantee = 0), false) FROM pg_proc p, aclexplode(p.proacl) a"
                        + " WHERE p.oid = 'sm_metrics.ensure_metric_partitions()'::regprocedure", Map.of(), Boolean.class),
                "no ACL entry for PUBLIC (grantee 0)");
        assertEquals(Boolean.TRUE, su.queryForObject(
                "SELECT has_function_privilege('sm_dp_runtime', 'sm_metrics.ensure_metric_partitions()', 'EXECUTE')",
                Map.of(), Boolean.class));
    }

    @Test
    void theFunctionIgnoresPlantedCatalogShadows() throws SQLException {
        // The runtime role may create TEMP tables (TEMP comes through PUBLIC; Phase 0 does not revoke it). Plant temp
        // pg_inherits and pg_class with rows that would make the runway look decades long. MF2, MF21.
        java.time.LocalDate honest = DB.runtimeJdbc().queryForObject("SELECT sm_metrics.ensure_metric_partitions()",
                Map.of(), java.time.LocalDate.class);
        List<String> before = DB.children();
        try (Connection c = DB.runtime().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TEMP TABLE pg_class (oid oid, relname name)");
            s.execute("CREATE TEMP TABLE pg_inherits (inhrelid oid, inhparent oid)");
            s.execute("INSERT INTO pg_class VALUES (4242, 'metrics_raw_2099_01')");
            s.execute("INSERT INTO pg_inherits VALUES (4242, 'sm_metrics.metrics_raw'::regclass)");
            java.sql.ResultSet rs = s.executeQuery("SELECT sm_metrics.ensure_metric_partitions()");
            assertTrue(rs.next());
            assertEquals(honest, rs.getObject(1, java.time.LocalDate.class),
                    "a planted temp pg_class/pg_inherits must not move the runway (MF21)");
        }
        assertEquals(before, DB.children(), "and attached nothing");
    }

    @Test
    void theFunctionSearchPathEndsWithPgTemp() {
        List<String> config = DB.superuserJdbc().queryForObject(
                "SELECT proconfig FROM pg_proc WHERE oid = 'sm_metrics.ensure_metric_partitions()'::regprocedure",
                Map.of(), (rs, i) -> List.of((String[]) rs.getArray(1).getArray()));
        String searchPath = config.stream().filter(e -> e.startsWith("search_path=")).findFirst().orElseThrow();
        assertEquals("search_path=sm_metrics, pg_temp", searchPath, "MF2: pg_temp LAST, never omitted");
        assertTrue(config.contains("lock_timeout=5s"), "lock_timeout pinned: " + config);
        assertTrue(config.contains("TimeZone=UTC"), "UTC pinned so months are UTC months: " + config);
    }

    @Test
    void theFunctionBodyQualifiesEveryCatalogRelation() {
        String body = DB.superuserJdbc().queryForObject(
                "SELECT prosrc FROM pg_proc WHERE oid = 'sm_metrics.ensure_metric_partitions()'::regprocedure",
                Map.of(), String.class);
        assertNotNull(body);
        assertTrue(body.contains("pg_catalog.pg_inherits") && body.contains("pg_catalog.pg_class"),
                "the catalog lookup is there");
        Pattern unqualified = Pattern.compile("(?<!pg_catalog\\.)\\bpg_(inherits|class|namespace|attribute|constraint|index|type)\\b");
        assertFalse(unqualified.matcher(body).find(),
                "MF21 defence in depth: no catalog relation is named without pg_catalog. in front of it");
    }

    @Test
    void theRunwayIsThisMonthPlusThreeAndIdempotent() {
        NamedParameterJdbcTemplate su = DB.superuserJdbc();
        List<String> expected = su.queryForList(
                "SELECT 'metrics_raw_' || to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => i), 'YYYY_MM')"
                        + " FROM generate_series(0, 3) i ORDER BY 1", Map.of(), String.class);
        assertEquals(expected, DB.children(), "this month plus three ahead, UTC months, nothing else");

        java.time.LocalDate first = DB.runtimeJdbc().queryForObject("SELECT sm_metrics.ensure_metric_partitions()",
                Map.of(), java.time.LocalDate.class);
        java.time.LocalDate second = DB.runtimeJdbc().queryForObject("SELECT sm_metrics.ensure_metric_partitions()",
                Map.of(), java.time.LocalDate.class);
        assertEquals(first, second, "idempotent");
        assertEquals(expected, DB.children(), "…and creates nothing the second time");
        assertEquals(su.queryForObject("SELECT (date_trunc('month', now() AT TIME ZONE 'UTC') + interval '4 months')::date",
                Map.of(), java.time.LocalDate.class), first, "runway end = first day of the month after the last child");
    }

    @Test
    void partitionNamesFollowTheClosedGrammarOnly() {
        for (String child : DB.children()) {
            assertTrue(child.matches("^metrics_raw_\\d{4}_\\d{2}$"), "OQ-21 (2a): only metrics_raw_YYYY_MM. Was " + child);
        }
        assertEquals(List.of(), DB.superuserJdbc().queryForList(
                "SELECT c.relname FROM pg_class c WHERE c.relnamespace = 'sm_metrics'::regnamespace AND c.relkind = 'p'"
                        + " AND c.relname <> 'metrics_raw'", Map.of(), String.class), "metrics_raw is the ONLY partitioned parent");
    }

    @Test
    void theHourlyTierIsUnpartitioned() {
        assertEquals(List.of("metrics_rollup_1d:r", "metrics_rollup_1h:r"), DB.superuserJdbc().queryForList(
                "SELECT relname || ':' || relkind FROM pg_class WHERE relnamespace = 'sm_metrics'::regnamespace"
                        + " AND relname IN ('metrics_rollup_1h','metrics_rollup_1d') ORDER BY relname", Map.of(), String.class),
                "OQ-21 (2a): ordinary tables, so the carve-out's grammar stays one parent");
    }

    @Test
    void everySmMetricsForeignKeyTargetsSmMetricsOrSmDataPlacementOnly() {
        List<String> targets = DB.superuserJdbc().queryForList(
                "SELECT DISTINCT n.nspname || '.' || t.relname FROM pg_constraint k"
                        + " JOIN pg_class s ON s.oid = k.conrelid JOIN pg_namespace sn ON sn.oid = s.relnamespace"
                        + " JOIN pg_class t ON t.oid = k.confrelid JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " WHERE k.contype = 'f' AND sn.nspname = 'sm_metrics'", Map.of(), String.class);
        assertFalse(targets.isEmpty());
        for (String t : targets) {
            assertTrue(t.startsWith("sm_metrics.") || t.equals("sm_data.placement"),
                    "I7 / M-Q19: an FK from sm_metrics may target sm_metrics or sm_data.placement only. Was " + t);
        }
        assertTrue(targets.contains("sm_data.placement"), "and the permitted one is really used");
    }

    @Test
    void aCounterRollupRowWithNullDeltaIsAccepted() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long counter = DB.series(p, "cache.hits", "counter");
        DB.runtimeJdbc().update(rollup("metrics_rollup_1h", "counter", "NULL, NULL, NULL, NULL, NULL"),
                rollupParams(p, counter));
        assertEquals(1, DB.superuserJdbc().queryForObject(
                "SELECT count(*)::int FROM sm_metrics.metrics_rollup_1h WHERE series_id = :s AND tenant_id = :t",
                new MapSqlParameterSource("s", counter).addValue("t", p.tenant()), Integer.class));
    }

    @Test
    void aRollupRowWhoseTypeDiffersFromItsSeriesIsRefused() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long counter = DB.series(p, "cache.hits", "counter");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "rollup_1h_series_typed",
                () -> DB.runtimeJdbc().update(rollup("metrics_rollup_1h", "gauge", "1, 1, 1, NULL, NULL"),
                        rollupParams(p, counter)), "a gauge-typed hour for a counter series");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "rollup_1d_series_typed",
                () -> DB.runtimeJdbc().update(rollup("metrics_rollup_1d", "gauge", "1, 1, 1, NULL, NULL"),
                        rollupParams(p, counter)), "the daily tier is typed the same way");
    }

    @Test
    void aGaugeRollupRowWithADeltaIsRefused() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long gauge = DB.series(p, "cache.size", "gauge");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "rollup_1h_type_shape",
                () -> DB.runtimeJdbc().update(rollup("metrics_rollup_1h", "gauge", "1, 1, 1, 5, 60"),
                        rollupParams(p, gauge)));
    }

    @Test
    void anInsertWithNoPartitionIsRefusedNotRoutedToADefault() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long s = DB.series(p, "cache.size", "gauge");
        var e = Refusals.assertRefused(Refusals.CHECK_VIOLATION, null,
                () -> DB.raw(p, s, "2099-01-01 00:00:00+00", 1, null));
        System.out.println("MEASURED (MF23): no partition accepts the row -> SQLSTATE " + e.getSQLState()
                + ", constraint name " + e.getServerErrorMessage().getConstraint());
        assertEquals(null, e.getServerErrorMessage().getConstraint(), "the routing failure carries NO constraint name");
        assertEquals(0, DB.superuserJdbc().queryForObject(
                "SELECT count(*)::int FROM pg_inherits WHERE inhparent = 'sm_metrics.metrics_raw'::regclass"
                        + " AND inhrelid::regclass::text LIKE '%default%'", Map.of(), Integer.class), "no DEFAULT partition");
        assertEquals(Boolean.FALSE, DB.superuserJdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_partitioned_table pt WHERE pt.partrelid = 'sm_metrics.metrics_raw'::regclass"
                        + " AND pt.partdefid <> 0)", Map.of(), Boolean.class), "partdefid = 0: there is no default partition");
    }

    @Test
    void aNonFiniteValueIsRefused() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long s = DB.series(p, "cache.size", "gauge");
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var e = Refusals.assertRefused(Refusals.CHECK_VIOLATION, "raw_value_finite",
                    () -> DB.raw(p, s, java.time.OffsetDateTime.now().toString(), bad, null));
            System.out.println("MEASURED (MF23): value " + bad + " -> SQLSTATE " + e.getSQLState() + ", constraint "
                    + e.getServerErrorMessage().getConstraint());
        }
    }

    @Test
    void theRoutingFailureAndTheFiniteCheckAreDistinguishable() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long s = DB.series(p, "cache.size", "gauge");
        var routing = Refusals.assertRefused(Refusals.CHECK_VIOLATION, null,
                () -> DB.raw(p, s, "2099-01-01 00:00:00+00", 1, null));
        var finite = Refusals.assertRefused(Refusals.CHECK_VIOLATION, "raw_value_finite",
                () -> DB.raw(p, s, java.time.OffsetDateTime.now().toString(), Double.NaN, null));
        assertEquals(routing.getSQLState(), finite.getSQLState(), "same SQLSTATE: only the constraint name tells them apart");
        assertEquals(null, routing.getServerErrorMessage().getConstraint());
        assertEquals("raw_value_finite", finite.getServerErrorMessage().getConstraint());
        // JdbcPartitionRunwayRepository.classify is the one place that reads that difference for the ingest writer.
        assertEquals(JdbcPartitionRunwayRepository.Refusal.NO_PARTITION, JdbcPartitionRunwayRepository.classify(routing));
        assertEquals(JdbcPartitionRunwayRepository.Refusal.CHECK, JdbcPartitionRunwayRepository.classify(finite));
        assertEquals(JdbcPartitionRunwayRepository.Refusal.OTHER,
                JdbcPartitionRunwayRepository.classify(new org.postgresql.util.PSQLException("x",
                        org.postgresql.util.PSQLState.UNEXPECTED_ERROR)));
    }

    @Test
    void aSeriesMustReferenceALivePlacementRowOfTheSameTenant() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        UUID otherTenant = UUID.randomUUID();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "series_placement",
                () -> DB.series(new MetricsDb.Placement(otherTenant, p.instance(), p.placement()), "cache.size", "gauge"),
                "tenant B naming tenant A's placement");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "series_placement",
                () -> DB.series(new MetricsDb.Placement(p.tenant(), p.instance(), UUID.randomUUID()), "cache.size", "gauge"),
                "a placement that does not exist");
    }

    @Test
    void everyStatementShapeIsTenantScoped() {
        // The key of every tenant-bearing table begins with tenant_id, so a series_id alone never finds a row.
        List<String> notLed = DB.superuserJdbc().queryForList(
                "SELECT c.relname FROM pg_class c JOIN pg_constraint k ON k.conrelid = c.oid AND k.contype = 'p'"
                        + " WHERE c.relnamespace = 'sm_metrics'::regnamespace AND c.relkind IN ('r','p')"
                        + " AND NOT c.relispartition AND c.relname NOT IN ('partition_freeze_log', 'flyway_schema_history')"
                        + " AND (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum = k.conkey[1]) <> 'tenant_id'",
                Map.of(), String.class);
        assertEquals(List.of(), notLed, "I5: tenant_id leads every primary key");

        MetricsDb.Placement a = DB.placement(UUID.randomUUID());
        MetricsDb.Placement b = DB.placement(UUID.randomUUID());
        long sa = DB.series(a, "cache.size", "gauge");
        DB.raw(a, sa, java.time.OffsetDateTime.now().toString(), 7, null);
        assertEquals(0, DB.runtimeJdbc().queryForObject(
                "SELECT count(*)::int FROM sm_metrics.metrics_raw WHERE tenant_id = :t AND series_id = :s",
                new MapSqlParameterSource("t", b.tenant()).addValue("s", sa), Integer.class),
                "tenant B cannot read tenant A's series by id");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "raw_series",
                () -> DB.raw(b, sa, java.time.OffsetDateTime.now().toString(), 1, null),
                "tenant B cannot write under tenant A's series id");
    }

    @Test
    void theRuntimeRoleCannotDeleteOrUpdateRawFacts() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        long s = DB.series(p, "cache.size", "gauge");
        DB.raw(p, s, java.time.OffsetDateTime.now().toString(), 1, null);
        NamedParameterJdbcTemplate rt = DB.runtimeJdbc();
        for (String table : List.of("metrics_raw", "series", "series_segment", "series_reset")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                    () -> rt.getJdbcTemplate().execute("DELETE FROM sm_metrics." + table), "DELETE on " + table);
        }
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("UPDATE sm_metrics.metrics_raw SET value = 2"), "UPDATE on metrics_raw");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("UPDATE sm_metrics.series SET unit = 'x'"), "UPDATE on series");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> rt.getJdbcTemplate().execute("TRUNCATE sm_metrics.metrics_raw"), "TRUNCATE");
    }

    @Test
    void theRuntimeRoleVacuumIsSkippedNotApplied() throws SQLException {
        String child = DB.children().get(0);
        long ageBefore = frozenAge(child);
        for (int i = 0; i < 3; i++) {
            DB.superuserJdbc().queryForObject("SELECT txid_current()", Map.of(), Long.class);
        }
        List<String> warnings = SqlScript.execute(DB.runtime(), "VACUUM (FREEZE) sm_metrics." + child);
        assertEquals(1, warnings.size(), "MF3: skipped with a WARNING, not an error: " + warnings);
        assertTrue(warnings.get(0).contains("only table or database owner can vacuum it"), warnings.get(0));
        assertTrue(frozenAge(child) >= ageBefore, "and nothing was frozen: relfrozenxid did not advance");
    }

    @Test
    void theFreezeLogIsReadableByTheRuntimeRoleAndWritableOnlyByTheOwner() {
        assertNotNull(DB.runtimeJdbc().queryForList("SELECT partition_name FROM sm_metrics.partition_freeze_log", Map.of()));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> DB.runtimeJdbc().update("INSERT INTO sm_metrics.partition_freeze_log VALUES ('metrics_raw_2000_01', now())",
                        Map.of()));
    }

    @Test
    void theReasonListIsClosedAndCarriesTheTwoSittingAdditionsAndClockSkew() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        for (String reason : List.of("scrape_failed", "parse_failed", "cap_rejected", "undeclared_dropped",
                "decrease_without_restart", "runway_degraded", "deadline_missed", "buffer_dropped", "out_of_order",
                "ambiguous_placement", "clock_skew")) {
            DB.runtimeJdbc().update("INSERT INTO sm_metrics.ingest_counter (tenant_id, placement_id, reason, day, count)"
                    + " VALUES (:t, :p, :r, current_date, 1)", new MapSqlParameterSource("t", p.tenant())
                    .addValue("p", p.placement()).addValue("r", reason));
        }
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "ingest_counter_reason",
                () -> DB.runtimeJdbc().update("INSERT INTO sm_metrics.ingest_counter (tenant_id, placement_id, reason, day,"
                        + " count) VALUES (:t, :p, 'made_up', current_date, 1)", new MapSqlParameterSource("t", p.tenant())
                        .addValue("p", p.placement())));
    }

    // ---- salvaged from the orphan backend/src/test/sql/V001_constraints_test.sql (discovery default D-8) -----------------

    @Test
    void twoPlacementsOfOneInstanceAreTwoSeriesNotAMerge() {
        MetricsDb.Placement first = DB.placement(UUID.randomUUID());
        MetricsDb.Placement second = DB.secondPlacementOfTheSameInstance(first);
        long a = DB.series(first, "cache.hit_rate", "gauge");
        long b = DB.series(second, "cache.hit_rate", "gauge");
        assertNotEquals(a, b, "the multi-site merge defect: same instance, same metric, different placement");
    }

    @Test
    void anIdenticalSeriesTwiceIsRefusedByTheIdentityConstraint() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        DB.series(p, "cache.hit_rate", "gauge");
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "series_identity", () -> DB.series(p, "cache.hit_rate", "gauge"));
    }

    @Test
    void aRetypeIsAnotherSeriesNotASilentRetype() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        assertNotEquals(DB.series(p, "cache.hit_rate", "gauge"), DB.series(p, "cache.hit_rate", "counter"));
    }

    @Test
    void twoSamplesOfOneSeriesAtOneTimestampAreRefusedAndTwoSeriesMayShareOne() {
        MetricsDb.Placement first = DB.placement(UUID.randomUUID());
        MetricsDb.Placement second = DB.secondPlacementOfTheSameInstance(first);
        long a = DB.series(first, "cache.hit_rate", "gauge");
        long b = DB.series(second, "cache.hit_rate", "gauge");
        String ts = java.time.OffsetDateTime.now().toString();
        DB.raw(first, a, ts, 0.9, null);
        var e = Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, null, () -> DB.raw(first, a, ts, 0.1, null),
                "no silent overwrite (D-2: the writer's ON CONFLICT DO NOTHING is P11-3.1's, not the table's)");
        assertTrue(e.getServerErrorMessage().getConstraint().endsWith("_pkey"), e.getServerErrorMessage().getConstraint());
        DB.raw(second, b, ts, 0.5, null);                      // no cross-site collision
    }

    @Test
    void aSampleForANonexistentSeriesIsRefused() {
        MetricsDb.Placement p = DB.placement(UUID.randomUUID());
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "raw_series",
                () -> DB.raw(p, 999_999_999L, java.time.OffsetDateTime.now().toString(), 1, null));
    }

    private static long frozenAge(String child) {
        return DB.superuserJdbc().queryForObject("SELECT age(relfrozenxid)::bigint FROM pg_class WHERE oid = :n::regclass",
                new MapSqlParameterSource("n", "sm_metrics." + child), Long.class);
    }

    private static String rollup(String table, String type, String sumMinMaxDeltaCovered) {
        return "INSERT INTO sm_metrics." + table + " (tenant_id, series_id, bucket, metric_type, sample_count, sum, min, max,"
                + " delta, covered_seconds, last_value, last_ts, computed_at) VALUES (:t, :s, date_trunc('hour', now()), '"
                + type + "', 1, " + sumMinMaxDeltaCovered + ", 1, now(), now())";
    }

    private static MapSqlParameterSource rollupParams(MetricsDb.Placement p, long series) {
        return new MapSqlParameterSource("t", p.tenant()).addValue("s", series);
    }
}
