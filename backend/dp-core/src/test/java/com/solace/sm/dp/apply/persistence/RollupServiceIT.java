package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import com.solace.sm.dp.metrics.rollup.RollupService;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-3.2: type-aware hourly and daily rollups over real PostgreSQL as the runtime credential, on a database of
 * its own. Raw rows are planted with their increments exactly as ingest would have left them, so each case states the
 * arithmetic it expects. "now" is passed in, so no wall clock is waited on.
 */
@Testcontainers(disabledWithoutDocker = true)
class RollupServiceIT {

    /** Day 2 of this UTC month: inside the migration's partitions however close to a month end the run is. */
    private static final String D = YearMonth.now(ZoneOffset.UTC).atDay(2).toString();
    private static final String D1 = YearMonth.now(ZoneOffset.UTC).atDay(3).toString();

    private static MetricsDb db;
    private static NamedParameterJdbcTemplate superuser;
    private static RollupService service;

    @BeforeAll
    static void start() {
        db = MetricsDb.fresh("rollup_service");
        superuser = db.superuserJdbc();
        service = serviceOver(new JdbcRollupRepository(db.runtimeJdbc()));
    }

    private static RollupService serviceOver(JdbcRollupRepository repository) {
        return new RollupService(repository, new RollupProperties(Duration.ofMinutes(5), Duration.ofHours(2), Duration.ofHours(24)));
    }

    private static Instant at(String day, String time) {
        return Instant.parse(day + "T" + time + "Z");
    }

    private static MetricsDb.Placement site() {
        return db.placement(UUID.randomUUID());
    }

    private static void roll(MetricsDb.Placement p, Instant now) {
        service.rollUp(new TenantId(p.tenant()), now);
    }

    private static Map<String, Object> hour(MetricsDb.Placement p, long series, String day, String time) {
        List<Map<String, Object>> rows = superuser.queryForList("SELECT * FROM sm_metrics.metrics_rollup_1h WHERE tenant_id = :t"
                        + " AND series_id = :s AND bucket = :b",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", series)
                        .addValue("b", OffsetDateTime.ofInstant(at(day, time), ZoneOffset.UTC)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> dayRow(MetricsDb.Placement p, long series, String day) {
        List<Map<String, Object>> rows = superuser.queryForList("SELECT * FROM sm_metrics.metrics_rollup_1d WHERE tenant_id = :t"
                        + " AND series_id = :s AND bucket = :b",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", series)
                        .addValue("b", OffsetDateTime.ofInstant(at(day, "00:00:00"), ZoneOffset.UTC)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static double num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    // ---- gauges -------------------------------------------------------------------------------------------------

    @Test
    void aGaugeHourIsSumCountMinMaxNeverAvgOfAvg() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        // First half-hour: three readings of 1. Second half: one reading of 9. The mean is 3; the mean of means is 5.
        db.raw(p, s, D + "T10:00:00Z", 1, null);
        db.raw(p, s, D + "T10:05:00Z", 1, null);
        db.raw(p, s, D + "T10:10:00Z", 1, null);
        db.raw(p, s, D + "T10:40:00Z", 9, null);
        roll(p, at(D, "13:00:00"));
        Map<String, Object> row = hour(p, s, D, "10:00:00");
        assertNotNull(row);
        assertEquals("gauge", row.get("metric_type"));
        assertEquals(4, row.get("sample_count"));
        assertEquals(12.0, num(row, "sum"), "sum and count are kept, so a mean is sum / count = 3, never avg(avg) = 5");
        assertEquals(1.0, num(row, "min"));
        assertEquals(9.0, num(row, "max"));
        assertEquals(9.0, num(row, "last_value"));
        assertEquals(at(D, "10:40:00"), ((java.sql.Timestamp) row.get("last_ts")).toInstant());
        assertNull(row.get("delta"));
        assertNull(row.get("covered_seconds"));
        assertEquals(0, row.get("reset_count"));
        assertNull(row.get("expected_count"), "no segment: nothing to expect");
    }

    @Test
    void theExpectedCountComesFromTheIntervalInForce() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        superuser.update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from, package_version,"
                        + " interval_seconds) VALUES (:t, :s, :f, '2.4', 30)",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", s)
                        .addValue("f", OffsetDateTime.ofInstant(at(D, "00:00:00"), ZoneOffset.UTC)));
        db.raw(p, s, D + "T10:00:00Z", 1, null);
        roll(p, at(D, "13:00:00"));
        assertEquals(120, hour(p, s, D, "10:00:00").get("expected_count"), "3600 / 30 s");
    }

    // ---- counters -----------------------------------------------------------------------------------------------

    @Test
    void aCounterHourIsTheSumOfItsIncrementsWithTheActualCoveredSeconds() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.requests", "counter");
        db.raw(p, s, D + "T10:00:00Z", 100, null);       // first sample of the series
        db.raw(p, s, D + "T10:10:00Z", 110, 10.0);       // 600 s since the previous sample
        db.raw(p, s, D + "T10:20:00Z", 125, 15.0);       // 600 s
        db.raw(p, s, D + "T10:50:00Z", 400, null);       // across a gap: no delta
        roll(p, at(D, "13:00:00"));
        Map<String, Object> row = hour(p, s, D, "10:00:00");
        assertEquals("counter", row.get("metric_type"));
        assertEquals(4, row.get("sample_count"));
        assertEquals(25.0, num(row, "delta"), "the sum of the increments");
        assertEquals(1200.0, num(row, "covered_seconds"), "600 + 600: exactly the samples whose increment is not null");
        assertEquals(400.0, num(row, "last_value"));
        assertNull(row.get("sum"));
        assertNull(row.get("min"));
        assertNull(row.get("max"));
    }

    @Test
    void aCounterHourWithOnlyNullIncrementsRollsUpWithNullDeltaNotZero() {
        MetricsDb.Placement p = site();
        long first = db.series(p, "cache.requests", "counter");
        // a new series' first sample, alone in its hour
        db.raw(p, first, D + "T10:15:00Z", 100, null);
        // an hour made only of post-gap samples: the previous sample is hours away
        db.raw(p, first, D + "T11:30:00Z", 500, null);
        roll(p, at(D, "13:00:00"));
        for (String time : List.of("10:00:00", "11:00:00")) {
            Map<String, Object> row = hour(p, first, D, time);
            assertNotNull(row, "the row is written and the insert does not fail: " + time);
            assertNull(row.get("delta"), "NULL, never 0: " + time);
            assertNull(row.get("covered_seconds"), time);
            assertEquals(1, row.get("sample_count"));
        }
        Map<String, Object> day = dayRow(p, first, D);
        assertNull(day.get("delta"), "a day whose every hour is NULL is NULL");
        assertNull(day.get("covered_seconds"));
        assertEquals(2, day.get("sample_count"));
    }

    @Test
    void multipleResetsInOneBucketAndOneOnABoundary() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.requests", "counter");
        db.raw(p, s, D + "T10:00:00Z", 1000, null);
        db.raw(p, s, D + "T10:20:00Z", 50, 50.0);
        db.raw(p, s, D + "T10:45:00Z", 20, 20.0);
        db.raw(p, s, D + "T11:00:00Z", 30, 30.0);
        for (String when : List.of("10:20:00", "10:45:00", "11:00:00")) {
            db.runtimeJdbc().update("INSERT INTO sm_metrics.series_reset (tenant_id, series_id, at, signal) VALUES (:t, :s, :a, 'docker')",
                    new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", s)
                            .addValue("a", OffsetDateTime.ofInstant(at(D, when), ZoneOffset.UTC)));
        }
        roll(p, at(D, "13:00:00"));
        assertEquals(2, hour(p, s, D, "10:00:00").get("reset_count"), "two resets inside the hour");
        assertEquals(1, hour(p, s, D, "11:00:00").get("reset_count"),
                "the reset exactly on the hour boundary belongs to the hour that starts there");
        assertEquals(70.0, num(hour(p, s, D, "10:00:00"), "delta"));
        assertEquals(3, dayRow(p, s, D).get("reset_count"), "the day sums its hours");
        assertEquals(100.0, num(dayRow(p, s, D), "delta"));
    }

    @Test
    void aSampleOnTheHourBoundaryBelongsToTheHourThatStartsThere() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        db.raw(p, s, D + "T10:59:59Z", 1, null);
        db.raw(p, s, D + "T11:00:00Z", 2, null);
        roll(p, at(D, "13:00:00"));
        assertEquals(1, hour(p, s, D, "10:00:00").get("sample_count"));
        assertEquals(1, hour(p, s, D, "11:00:00").get("sample_count"));
    }

    // ---- the late window ---------------------------------------------------------------------------------------

    @Test
    void aLateSampleInsideTheWindowCorrectsItsBucketAndTheDay() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        db.raw(p, s, D + "T11:10:00Z", 4, null);
        roll(p, at(D, "12:05:00"));                      // hour 11 has just closed
        assertEquals(1, hour(p, s, D, "11:00:00").get("sample_count"));
        assertEquals(4.0, num(dayRow(p, s, D), "sum"));
        db.raw(p, s, D + "T11:59:50Z", 6, null);        // a late batch lands after the rollup ran
        roll(p, at(D, "12:20:00"));
        Map<String, Object> row = hour(p, s, D, "11:00:00");
        assertEquals(2, row.get("sample_count"), "the bucket was recomputed");
        assertEquals(10.0, num(row, "sum"));
        assertEquals(6.0, num(row, "last_value"));
        assertEquals(10.0, num(dayRow(p, s, D), "sum"), "and so was the day");
        assertEquals(2, dayRow(p, s, D).get("sample_count"));
    }

    @Test
    void aBucketOlderThanTheWindowIsFinal() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        db.raw(p, s, D + "T06:10:00Z", 4, null);
        roll(p, at(D, "07:30:00"));                      // hour 06 is inside this run's window
        assertEquals(1, hour(p, s, D, "06:00:00").get("sample_count"));
        db.raw(p, s, D + "T06:40:00Z", 5, null);        // arrives after the window has closed
        roll(p, at(D, "13:00:00"));                      // the window is now hours 10..12
        assertEquals(1, hour(p, s, D, "06:00:00").get("sample_count"), "never touched again");
        assertEquals(4.0, num(hour(p, s, D, "06:00:00"), "sum"));
    }

    @Test
    void outOfOrderArrivalWithinTheWindow() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.requests", "counter");
        db.raw(p, s, D + "T11:00:00Z", 100, null);
        db.raw(p, s, D + "T11:30:00Z", 160, 60.0);       // inserted BEFORE the 11:10 row
        db.raw(p, s, D + "T11:10:00Z", 120, 20.0);
        roll(p, at(D, "12:30:00"));
        Map<String, Object> row = hour(p, s, D, "11:00:00");
        assertEquals(3, row.get("sample_count"));
        assertEquals(160.0, num(row, "last_value"), "last is by timestamp, never by insertion order");
        assertEquals(80.0, num(row, "delta"));
        assertEquals(600.0 + 1200.0, num(row, "covered_seconds"), "spacing follows the timestamps");
    }

    // ---- UTC, tenants, idempotence ---------------------------------------------------------------------------------

    @Test
    void theDayBoundaryIsUtc() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        db.raw(p, s, D + "T23:30:00Z", 3, null);
        db.raw(p, s, D1 + "T00:30:00Z", 5, null);
        roll(p, at(D1, "02:00:00"));
        assertEquals(3.0, num(dayRow(p, s, D), "sum"), "23:30 UTC belongs to day D");
        assertEquals(5.0, num(dayRow(p, s, D1), "sum"), "00:30 UTC belongs to day D+1");
    }

    /** A session in a half-hour zone must not move a bucket: date_trunc would have, date_bin does not. */
    @Test
    void bucketsAreUtcWhateverTheSessionTimeZone() {
        DelegatingDataSource kolkata = new DelegatingDataSource(db.runtime()) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection c = super.getConnection();
                try (Statement s = c.createStatement()) {
                    s.execute("SET TimeZone = 'Asia/Kolkata'");
                }
                return c;
            }
        };
        RollupService inKolkata = serviceOver(new JdbcRollupRepository(new NamedParameterJdbcTemplate(kolkata)));
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.size", "gauge");
        db.raw(p, s, D + "T10:45:00Z", 1, null);
        db.raw(p, s, D + "T23:45:00Z", 2, null);
        inKolkata.rollUp(new TenantId(p.tenant()), at(D, "13:00:00"));      // the 10:00Z hour
        inKolkata.rollUp(new TenantId(p.tenant()), at(D1, "02:00:00"));     // the 23:00Z hour
        assertNotNull(hour(p, s, D, "10:00:00"), "10:45Z is in the 10:00Z hour, not the 10:30Z one");
        assertEquals(3.0, num(dayRow(p, s, D), "sum"), "and the day is the UTC day");
        List<Integer> minutes = superuser.queryForList("SELECT extract(minute FROM bucket AT TIME ZONE 'UTC')::int"
                        + " FROM sm_metrics.metrics_rollup_1h WHERE tenant_id = :t",
                new MapSqlParameterSource("t", p.tenant()), Integer.class);
        assertTrue(minutes.stream().allMatch(m -> m == 0), minutes.toString());
    }

    @Test
    void rollupReadsOnlyItsTenant() {
        MetricsDb.Placement a = site();
        MetricsDb.Placement b = site();
        long sa = db.series(a, "cache.size", "gauge");
        long sb = db.series(b, "cache.size", "gauge");
        db.raw(a, sa, D + "T10:10:00Z", 1, null);
        db.raw(b, sb, D + "T10:10:00Z", 2, null);
        roll(a, at(D, "13:00:00"));
        assertNotNull(hour(a, sa, D, "10:00:00"));
        assertNull(hour(b, sb, D, "10:00:00"), "the other tenant was never rolled up");
        assertEquals(0, superuser.queryForObject("SELECT count(*)::int FROM sm_metrics.metrics_rollup_1d WHERE tenant_id = :t",
                new MapSqlParameterSource("t", b.tenant()), Integer.class));
    }

    @Test
    void rerunningARollupChangesNothingAndTheTypedForeignKeyHolds() {
        MetricsDb.Placement p = site();
        long s = db.series(p, "cache.requests", "counter");
        db.raw(p, s, D + "T10:00:00Z", 100, null);
        db.raw(p, s, D + "T10:10:00Z", 110, 10.0);
        roll(p, at(D, "13:00:00"));
        Map<String, Object> first = hour(p, s, D, "10:00:00");
        RollupService again = serviceOver(new JdbcRollupRepository(db.runtimeJdbc()));
        again.rollUp(new TenantId(p.tenant()), at(D, "13:10:00"));
        Map<String, Object> second = hour(p, s, D, "10:00:00");
        assertEquals(first.get("delta"), second.get("delta"));
        assertEquals(first.get("sample_count"), second.get("sample_count"));
        assertEquals(1, superuser.queryForObject("SELECT count(*)::int FROM sm_metrics.metrics_rollup_1h WHERE tenant_id = :t"
                + " AND bucket = :b", new MapSqlParameterSource().addValue("t", p.tenant())
                .addValue("b", OffsetDateTime.ofInstant(at(D, "10:00:00"), ZoneOffset.UTC)), Integer.class));
    }
}
