package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-2.1: the operator command, against a real database and a real closed month. Each test owns its
 * database, because freeze, detach, archive and drop destroy what the next test would read.
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricsPartitionMainIT {

    private static final String CLOSED = "2020_01";
    private static final String PARTITION = "metrics_raw_" + CLOSED;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path archive;

    private record Printed(int code, String out, String err) {
        String all() {
            return out + "\n" + err;
        }
    }

    /** A database with a closed month holding {@code 3 + 2} rows for two tenants. */
    private record Fixture(MetricsDb db, MetricsDb.Placement a, MetricsDb.Placement b) {
    }

    private static Fixture closedMonth(String name) {
        MetricsDb db = MetricsDb.fresh(name);
        db.attachPastMonth(CLOSED, "2020-01-01", "2020-02-01");
        MetricsDb.Placement a = db.placement(UUID.randomUUID());
        MetricsDb.Placement b = db.placement(UUID.randomUUID());
        long sa = db.series(a, "cache.hits", "counter");
        long sb = db.series(b, "cache.size", "gauge");
        db.raw(a, sa, "2020-01-15 10:00:00+00", 10.5, null);
        db.raw(a, sa, "2020-01-15 10:00:30.250+00", 11.5, 1.0);
        db.raw(a, sa, "2020-01-31 23:59:59+00", 14.0, 2.5);
        db.raw(b, sb, "2020-01-02 00:00:00+00", 7.0, null);
        db.raw(b, sb, "2020-01-03 00:00:00+00", 8.0, null);
        // The detach guard (P11-3.2) wants each series' last hour rolled up after its late window: so it was.
        rollUp(db, a, sa, "counter", "2020-01-31 23:00:00+00", "2020-02-01 04:00:00+00");
        rollUp(db, b, sb, "gauge", "2020-01-03 00:00:00+00", "2020-01-03 04:00:00+00");
        return new Fixture(db, a, b);
    }

    /** One hourly rollup row as the runtime role writes it; {@code computedAt} is what the guard reads. */
    private static void rollUp(MetricsDb db, MetricsDb.Placement p, long series, String type, String bucket, String computedAt) {
        boolean gauge = "gauge".equals(type);
        db.runtimeJdbc().update("INSERT INTO sm_metrics.metrics_rollup_1h (tenant_id, series_id, bucket, metric_type, sample_count,"
                        + " sum, min, max, delta, covered_seconds, last_value, last_ts, reset_count, computed_at) VALUES (:t, :s,"
                        + " :b::timestamptz, :ty, 1, :sum, :min, :max, NULL, NULL, 1, :b::timestamptz, 0, :c::timestamptz)",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", series).addValue("b", bucket)
                        .addValue("ty", type).addValue("sum", gauge ? 1.0 : null, java.sql.Types.DOUBLE)
                        .addValue("min", gauge ? 1.0 : null, java.sql.Types.DOUBLE)
                        .addValue("max", gauge ? 1.0 : null, java.sql.Types.DOUBLE).addValue("c", computedAt));
    }

    private static Map<String, String> envWith(MetricsDb db, String name, String value) {
        Map<String, String> env = env(db, SmDataPostgres.MIGRATOR_PASSWORD);
        env.put(name, value);
        return env;
    }

    // ---- the detach guard: the rollup watermark (plan 011 P11-3.2, the P11-2.1 wave review's obligation) ------------

    @Test
    void detachRefusesUntilTheSeriesLastHourWasRolledUpAfterItsNewestRowArrived() {
        MetricsDb db = MetricsDb.fresh("detach_guard_rollup");
        db.attachPastMonth(CLOSED, "2020-01-01", "2020-02-01");
        MetricsDb.Placement p = db.placement(UUID.randomUUID());
        long s = db.series(p, "cache.hits", "counter");
        db.raw(p, s, "2020-01-31 23:59:59+00", 14.0, 2.5);

        Printed none = main(db, "detach", CLOSED);
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, none.code(), none.all());
        assertTrue(none.err().contains("1 series") && none.err().contains("no rollup"), none.err());
        assertTrue(none.err().contains("rollup 2020_01"), "the refusal names its remedy: " + none.err());
        assertTrue(db.children().contains(PARTITION), "nothing was detached");
        assertFalse(none.all().contains(p.tenant().toString()), "no tenant id in the operator's output");

        // computed at 23:30, BEFORE the hour's newest row (received 23:59:59) arrived: the rollup never saw it
        rollUp(db, p, s, "counter", "2020-01-31 23:00:00+00", "2020-01-31 23:30:00+00");
        Printed early = main(db, "detach", CLOSED);
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, early.code(), "a rollup older than the newest row has not seen it: " + early.all());
        assertTrue(db.children().contains(PARTITION));

        db.superuserJdbc().update("UPDATE sm_metrics.metrics_rollup_1h SET computed_at = '2020-02-01 00:00:01+00' WHERE tenant_id = :t",
                new MapSqlParameterSource("t", p.tenant()));
        Printed done = main(db, "detach", CLOSED);
        assertEquals(0, done.code(), done.all());
        assertFalse(db.children().contains(PARTITION));
    }

    @Test
    void anHourRolledUpOnceInsideItsLateWindowAndNeverAgainDoesNotBlockTheDetachForever() {
        // W2-04: the loop only recomputes a bucket while it is inside its late window, so an hour no run covered after
        // the window (SM down 01:55-03:10; a tenant whose last instance retired) used to be refused permanently
        MetricsDb db = MetricsDb.fresh("detach_guard_stranded");
        db.attachPastMonth(CLOSED, "2020-01-01", "2020-02-01");
        MetricsDb.Placement p = db.placement(UUID.randomUUID());
        long s = db.series(p, "cache.hits", "counter");
        db.raw(p, s, "2020-01-31 23:59:59+00", 14.0, 2.5);
        rollUp(db, p, s, "counter", "2020-01-31 23:00:00+00", "2020-02-01 00:30:00+00"); // before 02:00, the old rule's bar

        Printed done = main(db, "detach", CLOSED);

        assertEquals(0, done.code(), done.all());
        assertFalse(db.children().contains(PARTITION));
    }

    @Test
    void theRollupSubcommandRecomputesAMonthTheLoopStoppedShortOfAndThatUnblocksTheDetach() {
        MetricsDb db = MetricsDb.fresh("detach_guard_backfill");
        db.attachPastMonth(CLOSED, "2020-01-01", "2020-02-01");
        MetricsDb.Placement p = db.placement(UUID.randomUUID());
        MetricsDb.Placement other = db.placement(UUID.randomUUID());
        long hits = db.series(p, "cache.hits", "counter");
        long size = db.series(p, "cache.size", "gauge");
        long done = db.series(other, "cache.size", "gauge");
        db.raw(p, hits, "2020-01-31 22:00:00+00", 10.0, null);
        db.raw(p, hits, "2020-01-31 22:00:30+00", 12.0, 2.0);
        db.raw(p, size, "2020-01-31 23:10:00+00", 5.0, null);
        db.raw(p, size, "2020-01-31 23:50:00+00", 7.0, null);
        db.raw(other, done, "2020-01-05 01:00:00+00", 9.0, null);
        // 'size' was rolled up at 23:20, before its 23:50 row arrived; 'hits' never was; the other tenant is current
        rollUp(db, p, size, "gauge", "2020-01-31 23:00:00+00", "2020-01-31 23:20:00+00");
        rollUp(db, other, done, "gauge", "2020-01-05 01:00:00+00", "2020-01-05 03:00:00+00");
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, main(db, "detach", CLOSED).code());

        Printed rolled = main(db, "rollup", CLOSED);

        assertEquals(0, rolled.code(), rolled.all());
        assertTrue(rolled.out().contains("1 tenant(s) with 2 series behind"), rolled.out());
        assertFalse(rolled.all().contains(p.tenant().toString()), "no tenant id in the operator's output");
        assertEquals(2L, db.superuserJdbc().queryForObject("SELECT sample_count FROM sm_metrics.metrics_rollup_1h"
                        + " WHERE tenant_id = :t AND series_id = :s AND bucket = '2020-01-31 23:00:00+00'",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", size), Long.class),
                "the stale hour now counts both rows");
        assertEquals(1L, db.superuserJdbc().queryForObject("SELECT count(*) FROM sm_metrics.metrics_rollup_1d"
                        + " WHERE tenant_id = :t AND series_id = :s", new MapSqlParameterSource()
                        .addValue("t", p.tenant()).addValue("s", hits), Long.class), "and the day was written");
        assertTrue(db.children().contains(PARTITION), "rollup never detaches");
        Printed again = main(db, "rollup", CLOSED);
        assertEquals(0, again.code(), again.all());
        assertTrue(again.out().contains("nothing to do"), again.out());
        assertEquals(0, main(db, "detach", CLOSED).code(), "the guard now passes");
        assertFalse(db.children().contains(PARTITION));
    }

    @Test
    void rollupRefusesAnUnknownMonthAndANonMonth() {
        MetricsDb db = MetricsDb.fresh("rollup_refusals");
        db.attachPastMonth(CLOSED, "2020-01-01", "2020-02-01");

        assertEquals(MetricsPartitionMain.EXIT_REFUSED, main(db, "rollup", "2019_01").code(), "no such partition");
        assertEquals(MetricsPartitionMain.EXIT_USAGE, main(db, "rollup", "2020-01").code(), "not a month");
    }

    @Test
    void detachRefusesWhileTheRawTierOrTheLateWindowHasNotPassedTheMonthEnd() {
        Fixture f = closedMonth("detach_guard_time");
        // 2020 is long gone, but a raw tier of a hundred thousand days has not passed it
        Printed tier = main(envWith(f.db(), MetricsPartitionMain.RAW_TIER_VARIABLE, "P100000D"),
                MetricsPartitionMain.DEFAULT_FACTORY, "detach", CLOSED);
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, tier.code(), tier.all());
        assertTrue(tier.err().contains("raw tier"), tier.err());
        Printed late = main(envWith(f.db(), MetricsPartitionMain.LATE_WINDOW_VARIABLE, "P100000D"),
                MetricsPartitionMain.DEFAULT_FACTORY, "detach", CLOSED);
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, late.code(), late.all());
        assertTrue(f.db().children().contains(PARTITION), "refused: still attached");
        assertEquals(MetricsPartitionMain.EXIT_USAGE, main(envWith(f.db(), MetricsPartitionMain.RAW_TIER_VARIABLE, "7 days"),
                MetricsPartitionMain.DEFAULT_FACTORY, "detach", CLOSED).code(), "not an ISO duration: a usage error");
    }

    @Test
    void anEmptyClosedPartitionDetachesWithNoRollups() {
        MetricsDb db = MetricsDb.fresh("detach_guard_empty");
        db.attachPastMonth(CLOSED, "2020-01-01", "2020-02-01");
        assertEquals(0, main(db, "detach", CLOSED).code(), "no series has raw rows in it: nothing is unrolled");
    }

    private static Map<String, String> env(MetricsDb db, String password) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmDataMigrationMain.URL_VARIABLE, db.url());
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, "sm_dp_migrator");
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, password);
        return env;
    }

    private static Printed main(MetricsDb db, String... args) {
        return main(env(db, SmDataPostgres.MIGRATOR_PASSWORD), MetricsPartitionMain.DEFAULT_FACTORY, args);
    }

    private static Printed main(Map<String, String> env, MetricsPartitionMain.DataSourceFactory factory, String... args) {
        return mainWithTimeout(env, factory, MetricsPartitionMain.DEFAULT_LOCK_TIMEOUT, args);
    }

    private static Printed mainWithTimeout(Map<String, String> env, MetricsPartitionMain.DataSourceFactory factory, String lockTimeout,
            String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MetricsPartitionMain.exitCode(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), factory, lockTimeout);
        return new Printed(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static boolean tableExists(MetricsDb db, String name) {
        return db.superuserJdbc().queryForObject("SELECT to_regclass(:n) IS NOT NULL",
                new MapSqlParameterSource("n", "sm_metrics." + name), Boolean.class);
    }

    @Test
    void freezeDetachArchiveDropRunInOrderAndDropRefusesWithoutAManifest() {
        Fixture f = closedMonth("lifecycle");
        MetricsDb db = f.db();

        Printed early = main(db, "archive", CLOSED, archive.toString());
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, early.code(), "archive needs a DETACHED partition: " + early.all());
        Printed earlyDrop = main(db, "drop", CLOSED, archive.toString());
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, earlyDrop.code(), "drop needs a detached partition: " + earlyDrop.all());
        assertTrue(tableExists(db, PARTITION));

        Printed freeze = main(db, "freeze", CLOSED);
        assertEquals(0, freeze.code(), freeze.all());
        Printed detach = main(db, "detach", CLOSED);
        assertEquals(0, detach.code(), detach.all());
        assertFalse(db.children().contains(PARTITION), "detached");
        assertTrue(tableExists(db, PARTITION), "…but the table, and the rows, are still there");

        Printed noManifest = main(db, "drop", CLOSED, archive.toString());
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, noManifest.code(), "no manifest: " + noManifest.all());
        assertTrue(tableExists(db, PARTITION), "drop REFUSED without a verified manifest and removed nothing");

        Printed archived = main(db, "archive", CLOSED, archive.toString());
        assertEquals(0, archived.code(), archived.all());

        Printed dropped = main(db, "drop", CLOSED, archive.toString());
        assertEquals(0, dropped.code(), dropped.all());
        assertFalse(tableExists(db, PARTITION), "dropped once the manifests verified");
    }

    @Test
    void archiveWritesOneFilePerTenantAndEachHoldsOnlyThatTenantsRows() throws Exception {
        Fixture f = closedMonth("archive_content");
        assertEquals(0, main(f.db(), "detach", CLOSED).code());

        Printed archived = main(f.db(), "archive", CLOSED, archive.toString());
        assertEquals(0, archived.code(), archived.all());

        for (var entry : Map.of(f.a().tenant(), 3, f.b().tenant(), 2).entrySet()) {
            Path csv = archive.resolve(PARTITION + "." + entry.getKey() + ".csv.gz");
            Path manifest = archive.resolve(PARTITION + "." + entry.getKey() + ".manifest.json");
            assertTrue(Files.exists(csv), "one archive per tenant (OQ-27 b): " + csv);
            assertTrue(Files.exists(manifest), "one manifest per tenant");

            List<String> lines = gunzipLines(csv);
            assertEquals("tenant_id,series_id,ts,value,increment,received_at", lines.get(0));
            assertEquals(entry.getValue(), lines.size() - 1, "row count");
            for (String line : lines.subList(1, lines.size())) {
                assertTrue(line.startsWith(entry.getKey() + ","), "only this tenant's rows. Was " + line);
            }
            JsonNode m = JSON.readTree(manifest.toFile());
            assertEquals(PARTITION, m.get("partition").asText());
            assertEquals(entry.getKey().toString(), m.get("tenantId").asText());
            assertEquals(entry.getValue().intValue(), m.get("rows").asInt());
            assertEquals(sha256(csv), m.get("sha256").asText(), "the manifest's hash is the file's");
        }
        // a value round-trips exactly, a null increment is empty, and the timestamp is UTC ISO-8601
        List<String> aLines = gunzipLines(archive.resolve(PARTITION + "." + f.a().tenant() + ".csv.gz"));
        assertTrue(aLines.stream().anyMatch(l -> l.contains(",2020-01-15T10:00:30.250Z,11.5,1.0,")), aLines.toString());
        assertTrue(aLines.stream().anyMatch(l -> l.contains(",2020-01-15T10:00:00Z,10.5,,")), aLines.toString());
    }

    @Test
    void dropRefusesAnArchiveThatNoLongerMatchesItsManifest() throws IOException {
        Fixture f = closedMonth("tamper");
        assertEquals(0, main(f.db(), "detach", CLOSED).code());
        assertEquals(0, main(f.db(), "archive", CLOSED, archive.toString()).code());

        Path csv = archive.resolve(PARTITION + "." + f.a().tenant() + ".csv.gz");
        byte[] original = Files.readAllBytes(csv);
        Files.write(csv, "x".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, main(f.db(), "drop", CLOSED, archive.toString()).code(),
                "a file whose hash differs from its manifest is not a verified archive");
        assertTrue(tableExists(f.db(), PARTITION));
        Files.write(csv, original);

        Path manifest = archive.resolve(PARTITION + "." + f.b().tenant() + ".manifest.json");
        String text = Files.readString(manifest);
        Files.writeString(manifest, text.replace("\"rows\" : 2", "\"rows\" : 3").replace("\"rows\":2", "\"rows\":3"));
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, main(f.db(), "drop", CLOSED, archive.toString()).code(),
                "a manifest whose row count is not the partition's is not verified either");
        assertTrue(tableExists(f.db(), PARTITION));
        Files.writeString(manifest, text);

        // a tenant with rows in the partition and NO manifest
        Files.delete(archive.resolve(PARTITION + "." + f.b().tenant() + ".manifest.json"));
        assertEquals(MetricsPartitionMain.EXIT_REFUSED, main(f.db(), "drop", CLOSED, archive.toString()).code(),
                "every tenant in the partition needs its own verified manifest");
        Files.writeString(manifest, text);

        assertEquals(0, main(f.db(), "drop", CLOSED, archive.toString()).code(), "restored: now it verifies");
    }

    @Test
    void freezeRecordsTheMonthInTheFreezeLogAndActuallyFreezes() {
        Fixture f = closedMonth("freeze_log");
        MetricsDb db = f.db();
        long ageBefore = age(db);
        for (int i = 0; i < 3; i++) {
            db.superuserJdbc().queryForObject("SELECT txid_current()", Map.of(), Long.class);
        }

        assertEquals(List.of(), db.runtimeJdbc().queryForList("SELECT partition_name FROM sm_metrics.partition_freeze_log",
                Map.of(), String.class));
        Printed freeze = main(db, "freeze", CLOSED);
        assertEquals(0, freeze.code(), freeze.all());

        assertEquals(List.of(PARTITION), db.runtimeJdbc().queryForList("SELECT partition_name FROM sm_metrics.partition_freeze_log",
                Map.of(), String.class), "the runtime can SELECT the log");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> db.runtimeJdbc().update(
                "INSERT INTO sm_metrics.partition_freeze_log VALUES ('metrics_raw_2019_01', now())", Map.of()),
                "…and cannot INSERT into it");
        assertTrue(age(db) < ageBefore, "VACUUM (FREEZE) really ran: relfrozenxid advanced past the txids consumed in between");
        assertEquals(0, main(db, "freeze", CLOSED).code(), "freezing again is harmless");
        assertEquals(1, db.runtimeJdbc().queryForObject("SELECT count(*)::int FROM sm_metrics.partition_freeze_log",
                Map.of(), Integer.class));
    }

    private static long age(MetricsDb db) {
        return db.superuserJdbc().queryForObject("SELECT age(relfrozenxid)::bigint FROM pg_class WHERE oid = :n::regclass",
                new MapSqlParameterSource("n", "sm_metrics." + PARTITION), Long.class);
    }

    @Test
    void aWellFormedMonthWithNoPartitionIsRefusedByTheCatalogLookup() {
        MetricsDb db = MetricsDb.fresh("no_such_partition");
        for (String sub : List.of("freeze", "rollup", "detach")) {
            Printed p = main(db, sub, "2019_05");
            assertEquals(MetricsPartitionMain.EXIT_REFUSED, p.code(), sub + ": " + p.all());
            assertTrue(p.err().contains("metrics_raw_2019_05"), "names the partition it did not find: " + p.err());
        }
        for (String sub : List.of("archive", "drop")) {
            assertEquals(MetricsPartitionMain.EXIT_REFUSED, main(db, sub, "2019_05", archive.toString()).code(), sub);
        }
    }

    @Test
    void theOpenMonthCanNeitherBeFrozenNorDetached() {
        MetricsDb db = MetricsDb.fresh("open_month");
        String current = db.children().get(0);
        String month = current.substring("metrics_raw_".length());
        for (String sub : List.of("freeze", "rollup", "detach")) {
            Printed p = main(db, sub, month);
            assertEquals(MetricsPartitionMain.EXIT_REFUSED, p.code(), sub + " of the open month: " + p.all());
        }
        assertTrue(db.children().contains(current), "still attached: the current month is where ingest writes");
        assertEquals(List.of(), db.superuserJdbc().queryForList("SELECT partition_name FROM sm_metrics.partition_freeze_log",
                Map.of(), String.class));
    }

    @Test
    void anInterruptedDetachIsFinishedByRunningDetachAgainAndStatusShowsItPending() throws Exception {
        Fixture f = closedMonth("detach_pending");
        MetricsDb db = f.db();
        Map<String, String> env = env(db, SmDataPostgres.MIGRATOR_PASSWORD);

        Printed interrupted;
        try (java.sql.Connection reader = db.superuser().getConnection()) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (java.sql.Statement s = reader.createStatement();
                    java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM sm_metrics.metrics_raw")) {
                rs.next();                                     // an older transaction that has read the parent
            }
            interrupted = mainWithTimeout(env, MetricsPartitionMain.DEFAULT_FACTORY, "1s", "detach", CLOSED);
            assertNotEquals(0, interrupted.code(), "the second phase waits on the reader and lock_timeout cancels it");
            assertTrue(interrupted.err().contains("PENDING"),
                    "a fixed message says the partition is pending and that re-running finishes it: " + interrupted.err());
            assertTrue(interrupted.err().contains("detach command again"), interrupted.err());
            assertFalse(interrupted.all().contains("SQLSTATE") || interrupted.all().contains("jdbc"), "fixed words only (I6)");
            assertTrue(pending(db), "inhdetachpending = true: the partition is stranded half-detached");

            Printed status = mainWithTimeout(env, MetricsPartitionMain.DEFAULT_FACTORY, "1s", "status");
            assertEquals(0, status.code(), status.all());
            assertTrue(status.out().contains("pending detach: 1") && status.out().contains(PARTITION + " pending detach"),
                    "status lists pending partitions: " + status.out());
            Printed early = main(db, "archive", CLOSED, archive.toString());
            assertEquals(MetricsPartitionMain.EXIT_REFUSED, early.code(), "archive refuses until detach finishes");
        }                                                      // the reader ends here

        Printed finished = main(db, "detach", CLOSED);
        assertEquals(0, finished.code(), finished.all());
        assertFalse(pending(db), "FINALIZE completed the detach");
        assertFalse(db.children().contains(PARTITION), "no longer a partition");
        Printed archived = main(db, "archive", CLOSED, archive.toString());
        assertEquals(0, archived.code(), "archive works after the finish: " + archived.all());
    }

    private static boolean pending(MetricsDb db) {
        return db.superuserJdbc().queryForObject("SELECT EXISTS (SELECT 1 FROM pg_inherits h JOIN pg_class c ON c.oid = h.inhrelid"
                + " WHERE c.relname = :n AND h.inhdetachpending)", new MapSqlParameterSource("n", PARTITION), Boolean.class);
    }

    @Test
    void detachIsConcurrentAndNeverRunsInsideATransaction() {
        Fixture f = closedMonth("detach_shape");
        List<SqlRecorder.Executed> log = new ArrayList<>();
        Printed p = main(env(f.db(), SmDataPostgres.MIGRATOR_PASSWORD), SqlRecorder.recording(log), "detach", CLOSED);
        assertEquals(0, p.code(), p.all());

        List<SqlRecorder.Executed> detaches = log.stream().filter(e -> e.sql().toUpperCase().contains("DETACH PARTITION")).toList();
        assertEquals(1, detaches.size(), log.toString());
        String sql = detaches.get(0).sql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.endsWith("CONCURRENTLY"), "MF4: DETACH ... CONCURRENTLY. Was: " + sql);
        assertTrue(sql.contains("\"" + PARTITION + "\""), "built from the catalog's relname, quoted: " + sql);
        assertTrue(detaches.get(0).autoCommit(), "CONCURRENTLY cannot run in a transaction block");
        assertTrue(log.stream().noneMatch(e -> e.sql().trim().equalsIgnoreCase("BEGIN")));
        assertTrue(log.stream().anyMatch(e -> e.sql().toLowerCase().replace(" ", "").contains("lock_timeout")),
                "a lock_timeout is set before it");
    }

    @Test
    void statusPrintsTheByteFiguresAndTheApiNeverDoes() {
        Fixture f = closedMonth("status");
        Printed p = main(f.db(), "status");
        assertEquals(0, p.code(), p.all());
        assertTrue(p.out().matches("(?s).*sm_metrics bytes: \\d+.*"), p.out());
        assertTrue(p.out().matches("(?s).*database bytes: \\d+.*"), p.out());
        assertTrue(p.out().contains("budget bytes: unconfigured"), "OQ-16: shown, never silently ok. " + p.out());
        assertTrue(p.out().contains("unfrozen closed partitions: 1"), p.out());
        assertTrue(p.out().contains(PARTITION + " closed unfrozen"), p.out());
        assertTrue(p.out().matches("(?s).*runway end: \\d{4}-\\d{2}-01.*"), p.out());

        Map<String, String> withBudget = env(f.db(), SmDataPostgres.MIGRATOR_PASSWORD);
        withBudget.put(MetricsPartitionMain.BUDGET_VARIABLE, "1000000000");
        assertTrue(main(withBudget, MetricsPartitionMain.DEFAULT_FACTORY, "status").out().contains("budget bytes: 1000000000"));
        assertEquals(0, main(f.db(), "freeze", CLOSED).code());
        assertTrue(main(f.db(), "status").out().contains("unfrozen closed partitions: 0"));

        // the API side: the runway repository answers a date and nothing else, so no byte figure can reach a response
        for (var m : JdbcPartitionRunwayRepository.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                assertTrue(m.getReturnType() == java.time.LocalDate.class || m.getReturnType() == void.class,
                        "public surface of the runtime-side repository: " + m);
            }
        }
    }

    @Test
    void partitionMainPrintsNoUrlUserOrPassword() {
        Fixture f = closedMonth("no_echo");
        MetricsDb db = f.db();
        List<Printed> runs = new ArrayList<>();
        for (String[] args : List.of(new String[] {"status"}, new String[] {"freeze", CLOSED}, new String[] {"detach", CLOSED},
                new String[] {"archive", CLOSED, archive.toString()}, new String[] {"drop", CLOSED, archive.toString()})) {
            runs.add(main(db, args));                                               // success
            runs.add(main(env(db, "wrong-password-sentinel-9f3a"), MetricsPartitionMain.DEFAULT_FACTORY, args));   // failure
            Map<String, String> unreachable = env(db, SmDataPostgres.MIGRATOR_PASSWORD);
            unreachable.put(SmDataMigrationMain.URL_VARIABLE, "jdbc:postgresql://127.0.0.1:1/sm_unreachable");
            runs.add(main(unreachable, MetricsPartitionMain.DEFAULT_FACTORY, args)); // failure
        }
        assertEquals(0, runs.get(0).code(), "the success path really ran");
        int failures = 0;
        for (Printed p : runs) {
            for (String secret : List.of(db.url(), "jdbc:", "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD,
                    "wrong-password-sentinel-9f3a", "127.0.0.1:1", "sm_unreachable")) {
                assertFalse(p.all().contains(secret), "neither stream may carry '" + secret + "'. Was: " + p.all());
            }
            if (p.code() == MetricsPartitionMain.EXIT_FAILED) {
                failures++;
                assertTrue(p.err().contains("SQLSTATE") || p.err().contains("Exception"), "classifiers only: " + p.err());
            }
        }
        assertTrue(failures >= 10, "every subcommand failed twice with a connection-class failure. Failures: " + failures);
        assertNotEquals(0, failures);
    }

    // -------------------------------------------------------------------------------------------

    private static List<String> gunzipLines(Path gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
