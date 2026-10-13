package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-2.1 Step 0 / MF1: two Flyway histories in one process, at the reactor's Flyway version, and the
 * migration entry point that now runs both.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmMetricsMigratorIT {

    private static List<String> versions(MetricsDb db, String schema) {
        return db.superuserJdbc().queryForList(
                "SELECT version FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL AND success ORDER BY installed_rank",
                Map.of(), String.class);
    }

    @Test
    void twoHistoriesInOneRunRecordSeparately() {
        MetricsDb db = MetricsDb.fresh("two_histories");

        assertEquals(List.of("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012", "013", "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "024", "025", "026", "027",
                        // V028-V036 are absent: the old agent's, removed by the OR-147 reconciliation
                        "037", "038", "039", "040", "041", "042", "043", "044", "045", "046"),
                versions(db, "sm_data"),
                "(i) sm_data's history is in sm_data and holds only sm_data's migrations");
        assertEquals(List.of("001", "002", "003", "004"), versions(db, "sm_metrics"),
                "(i) sm_metrics' history is in sm_metrics and holds only its own");
        assertEquals(List.of("sm_data", "sm_metrics"), db.superuserJdbc().queryForList(
                "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE c.relname = 'flyway_schema_history' ORDER BY n.nspname", Map.of(), String.class),
                "exactly one history table per schema, none in public");
        assertEquals(List.of(), db.superuserJdbc().queryForList(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE c.relname = 'flyway_schema_history' AND pg_get_userbyid(c.relowner) <> 'sm_dp_owner'",
                Map.of(), String.class), "both history tables are born owned by sm_dp_owner (initSql applies to each)");

        // (ii) each run sees only its own history: a second run of either executes nothing
        MigrateResult dataAgain = SmDataMigrator.migrate(SmDataPostgres.dataSource(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD));
        MigrateResult metricsAgain = SmMetricsMigrator.migrate(SmDataPostgres.dataSource(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD));
        assertEquals(0, dataAgain.migrationsExecuted);
        assertEquals(0, metricsAgain.migrationsExecuted);
        assertEquals("sm_metrics", metricsAgain.schemaName);
        System.out.println("MEASURED (MF1, Flyway 11.7.2): two Flyway instances, one process, schemas(sm_data) then schemas(sm_metrics),"
                + " default history table name: histories " + versions(db, "sm_data").size() + " + " + versions(db, "sm_metrics").size()
                + " rows, each in its own schema; a second run of each executed 0");
    }

    @Test
    void aFailureInTheSecondRunLeavesTheFirstRunsWorkCommitted() {
        MetricsDb db = MetricsDb.fresh("second_fails", MetricsDb.Migrated.NOTHING);
        // A Phase 0 whose sm_metrics the owner cannot create in: the second run fails, after the first has finished.
        // (Flyway refuses a NON-empty schema with no history, so the break is a privilege, not a planted table.)
        SqlScript.execute(db.superuser(), "REVOKE CREATE ON SCHEMA sm_metrics FROM sm_dp_owner");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> SmDataMigrationMain.runAll(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD));
        assertEquals("42501", Refusals.unwrap(failure).getSQLState(), "insufficient_privilege: " + failure.getMessage());

        assertEquals(37, versions(db, "sm_data").size(), "(iii) sm_data's thirty-seven migrations (V001-V027, V037-V046) stayed committed");
        assertEquals(Boolean.TRUE, db.superuserJdbc().queryForObject(
                "SELECT to_regclass('sm_metrics.flyway_schema_history') IS NULL AND to_regclass('sm_metrics.series') IS NULL",
                Map.of(), Boolean.class), "…and sm_metrics holds nothing: no history, no table");
    }

    @Test
    void runStillReturnsSmDataSResultAndRunAllReturnsBoth() {
        MetricsDb db = MetricsDb.fresh("run_all", MetricsDb.Migrated.NOTHING);
        MigrationReport report = SmDataMigrationMain.runAll(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);
        assertEquals(37, report.smData().migrationsExecuted);
        assertEquals(4, report.smMetrics().migrationsExecuted);
        MigrateResult viaRun = SmDataMigrationMain.run(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);
        assertEquals("sm_data", viaRun.schemaName, "run keeps its signature and still answers sm_data's result");
        assertEquals(0, viaRun.migrationsExecuted);
    }

    @Test
    void migrationMainPrintsNoUrlUserOrPasswordForEitherHistory() {
        MetricsDb ok = MetricsDb.fresh("print_ok", MetricsDb.Migrated.NOTHING);
        Printed success = exit(ok.url(), SmDataPostgres.MIGRATOR_PASSWORD);
        assertEquals(0, success.code(), success.err());
        assertTrue(success.out().contains("sm_data") && success.out().contains("sm_metrics"),
                "both histories are reported: " + success.out());
        assertTrue(success.out().contains("migrated 37") && success.out().contains("migrated 4 "), success.out());
        assertNoSecrets(success, ok.url());

        MetricsDb clash = MetricsDb.fresh("print_fail", MetricsDb.Migrated.NOTHING);
        SqlScript.execute(clash.superuser(), "REVOKE CREATE ON SCHEMA sm_metrics FROM sm_dp_owner");
        Printed failed = exit(clash.url(), SmDataPostgres.MIGRATOR_PASSWORD);
        assertEquals(1, failed.code());
        assertTrue(failed.err().contains("sm_metrics"), "the failure names WHICH history failed: " + failed.err());
        assertTrue(failed.err().contains("SQLSTATE 42501"), "classifiers only: " + failed.err());
        assertNoSecrets(failed, clash.url());

        Printed badPassword = exit(ok.url(), "wrong-password-sentinel-9f3a");
        assertEquals(1, badPassword.code());
        assertNoSecrets(badPassword, ok.url());
        assertFalse((badPassword.out() + badPassword.err()).contains("wrong-password-sentinel-9f3a"));
    }

    private record Printed(int code, String out, String err) {
    }

    private static Printed exit(String url, String password) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmDataMigrationMain.URL_VARIABLE, url);
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, "sm_dp_migrator");
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, password);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SmDataMigrationMain.exitCode(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Printed(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertNoSecrets(Printed p, String url) {
        for (String printed : List.of(p.out(), p.err())) {
            assertFalse(printed.contains(url), "no URL: " + printed);
            assertFalse(printed.contains("jdbc:"), "no URL at all: " + printed);
            assertFalse(printed.contains("sm_dp_migrator"), "no user name: " + printed);
            assertFalse(printed.contains(SmDataPostgres.MIGRATOR_PASSWORD), "no password: " + printed);
        }
    }
}
