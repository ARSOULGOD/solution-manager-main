package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-2.1: the runway function under the conditions the shared-database tests cannot create (a missing
 * month, a held lock, an empty runway), each on a database of its own. Also the readiness of {@code sm_metrics} on a
 * real Spring context over the runtime credential.
 */
@Testcontainers(disabledWithoutDocker = true)
class PartitionRunwayIT {

    /** Removes the newest child so the next {@code ensure()} has to attach one (as the owner, never the runtime). */
    private static String dropNewestMonth(MetricsDb db) {
        List<String> children = db.children();
        String newest = children.get(children.size() - 1);
        db.asOwner("ALTER TABLE sm_metrics.metrics_raw DETACH PARTITION sm_metrics." + newest, "DROP TABLE sm_metrics." + newest);
        assertEquals(children.size() - 1, db.children().size());
        return newest;
    }

    private static Connection holdOpenInsert(MetricsDb db, boolean newSeries, MetricsDb.Placement p, long existingSeries)
            throws SQLException {
        Connection c = db.runtime().getConnection();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (newSeries) {
                s.execute("INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name, metric_type,"
                        + " unit, source_discriminator, labels_fingerprint, created_at) VALUES ('" + p.tenant() + "','"
                        + p.instance() + "','" + p.placement() + "','held.series','gauge','x','dp-poll','sha256:"
                        + "1".repeat(64) + "', now())");
            } else {
                s.execute("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, received_at) VALUES ('"
                        + p.tenant() + "'," + existingSeries + ", now(), 1, now())");
            }
        }
        return c;
    }

    /** MF4 + MF20: what ATTACH locks, measured through pg_locks. */
    @Test
    void attachTakesNoAccessExclusiveOnTheParent() throws Exception {
        MetricsDb db = MetricsDb.fresh("attach_locks");
        MetricsDb.Placement p = db.placement(UUID.randomUUID());
        long series = db.series(p, "cache.size", "gauge");
        JdbcPartitionRunwayRepository runway = new JdbcPartitionRunwayRepository(db.runtimeJdbc());

        // (i) a writer INSIDE metrics_raw (ROW EXCLUSIVE on the parent, ROW SHARE on series for the FK check)
        String missing = dropNewestMonth(db);
        try (Connection writer = holdOpenInsert(db, false, p, series)) {
            long t0 = System.nanoTime();
            runway.ensure();
            long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            System.out.println("MEASURED (MF4): ensure() attached " + missing + " in " + ms + " ms beside an open raw INSERT");
            assertTrue(ms < 4000, "ATTACH takes SHARE UPDATE EXCLUSIVE on the parent and does not wait for a row writer. Took " + ms);
            assertTrue(db.children().contains(missing));
            writer.rollback();
        }

        // (ii) a writer inside the REFERENCED table: an open INSERT into series (ROW EXCLUSIVE on series)
        dropNewestMonth(db);
        try (Connection seriesWriter = holdOpenInsert(db, true, p, series)) {
            CompletableFuture<LocalDate> attach = CompletableFuture.supplyAsync(runway::ensure);
            String waitingMode = null;
            for (int i = 0; i < 30 && waitingMode == null; i++) {
                Thread.sleep(100);
                List<String> waiting = db.superuserJdbc().queryForList(
                        "SELECT l.mode FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid"
                                + " WHERE NOT l.granted AND l.relation = 'sm_metrics.series'::regclass"
                                + "   AND a.query LIKE '%ensure_metric_partitions%'", Map.of(), String.class);
                waitingMode = waiting.isEmpty() ? null : waiting.get(0);
            }
            System.out.println("MEASURED (MF20): ATTACH waits on sm_metrics.series for " + waitingMode);
            assertEquals("ShareRowExclusiveLock", waitingMode,
                    "MF20: the FK clone locks the REFERENCED table SHARE ROW EXCLUSIVE, which an open series INSERT blocks");
            assertEquals(0, db.superuserJdbc().queryForObject(
                    "SELECT count(*)::int FROM pg_locks WHERE relation = 'sm_metrics.metrics_raw'::regclass"
                            + " AND mode = 'AccessExclusiveLock'", Map.of(), Integer.class),
                    "and the parent is never ACCESS EXCLUSIVE locked, held or waited for");
            Thread.sleep(6500);                               // past the first 5 s lock_timeout: attempt 1 failed, attempt 2 waits
            long t0 = System.nanoTime();
            seriesWriter.commit();
            LocalDate end = attach.get(10, TimeUnit.SECONDS);
            System.out.println("MEASURED (MF20): after the writer committed, the retry finished in "
                    + Duration.ofNanos(System.nanoTime() - t0).toMillis() + " ms; runway end " + end);
            assertEquals(4, db.children().size(), "the retry attached the month");
        }
    }

    @Test
    void aLockTimeoutIsRetriedThenRaisedAndNeverCorruptsTheRunway() throws Exception {
        MetricsDb db = MetricsDb.fresh("attach_timeout");
        MetricsDb.Placement p = db.placement(UUID.randomUUID());
        db.series(p, "cache.size", "gauge");
        JdbcPartitionRunwayRepository runway = new JdbcPartitionRunwayRepository(db.runtimeJdbc());
        String missing = dropNewestMonth(db);
        List<String> before = db.children();

        try (Connection seriesWriter = holdOpenInsert(db, true, p, 0)) {
            long t0 = System.nanoTime();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> CompletableFuture.supplyAsync(runway::ensure).get(40, TimeUnit.SECONDS));
            long seconds = Duration.ofNanos(System.nanoTime() - t0).toSeconds();
            PSQLExceptionHolder.assertLockNotAvailable(failure);
            assertTrue(seconds >= 14, "three attempts of 5 s each before it raises. Took " + seconds + " s");
            seriesWriter.rollback();
        }
        assertEquals(before, db.children(), "the failed attach left the runway as it was");
        assertEquals(0, db.superuserJdbc().queryForObject(
                "SELECT count(*)::int FROM pg_class WHERE relnamespace = 'sm_metrics'::regnamespace AND relname = :n",
                new MapSqlParameterSource("n", missing), Integer.class), "…and no orphan table: the subtransaction rolled back");
        runway.ensure();
        assertTrue(db.children().contains(missing), "and the next call succeeds once the lock is free");
    }

    @Test
    void aBootWithNoRunwayStillStarts() {
        MetricsDb db = MetricsDb.fresh("no_runway");
        for (String child : db.children()) {
            db.asOwner("ALTER TABLE sm_metrics.metrics_raw DETACH PARTITION sm_metrics." + child, "DROP TABLE sm_metrics." + child);
        }
        assertEquals(List.of(), db.children());
        runner(db.url()).run(context -> {
            assertNull(context.getStartupFailure(),
                    "Review Focus 5: readiness does not require runway; only ingest degrades. " + context.getStartupFailure());
            assertNotNull(context.getBean(JdbcPartitionRunwayRepository.class));
        });
    }

    @Test
    void anUnmigratedSmMetricsStopsTheContextWithAMessageNamingTheStep() {
        MetricsDb db = MetricsDb.fresh("metrics_unmigrated", MetricsDb.Migrated.SM_DATA_ONLY);
        runner(db.url()).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "a context over an UNMIGRATED sm_metrics must not start");
            StringBuilder chain = new StringBuilder();
            for (Throwable t = failure; t != null && t.getCause() != t; t = t.getCause()) {
                chain.append(t).append(" | ");
            }
            assertTrue(chain.toString().contains("sm_metrics is not migrated to the shape this build expects"), chain.toString());
            assertTrue(chain.toString().contains("SmDataMigrationMain with the sm_dp_migrator credential"), chain.toString());
        });
    }

    @Test
    void aRuntimeRoleThatCannotExecuteTheFunctionStopsTheContext() {
        MetricsDb db = MetricsDb.fresh("no_execute");
        db.asOwner("REVOKE EXECUTE ON FUNCTION sm_metrics.ensure_metric_partitions() FROM sm_dp_runtime");
        runner(db.url()).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "without EXECUTE the runway can never be kept: refuse to start");
            StringBuilder chain = new StringBuilder();
            for (Throwable t = failure; t != null && t.getCause() != t; t = t.getCause()) {
                chain.append(t).append(" | ");
            }
            assertTrue(chain.toString().contains("ensure_metric_partitions"), chain.toString());
        });
    }

    private static ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ApplyPersistenceConfiguration.class, MetricsPersistenceConfiguration.class)
                .withPropertyValues("sm.data.runtime.url=" + url, "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD);
    }

    /** Reads the SQLSTATE off the failure's cause chain. */
    private static final class PSQLExceptionHolder {
        static void assertLockNotAvailable(Throwable failure) {
            org.postgresql.util.PSQLException pg = Refusals.unwrap(failure);
            assertNotNull(pg, "expected the driver error in the chain: " + failure);
            assertEquals("55P03", pg.getSQLState(), "lock_not_available, re-raised after the third attempt: " + pg.getMessage());
        }
    }
}
