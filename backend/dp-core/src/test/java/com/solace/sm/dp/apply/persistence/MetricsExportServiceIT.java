package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.ExportPlan;
import com.solace.sm.dp.api.metrics.export.ExportTooLargeException;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.export.ExportProperties;
import com.solace.sm.dp.metrics.export.ExportRows;
import com.solace.sm.dp.metrics.export.MetricsExportServiceImpl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-6.2: the export over real PostgreSQL as the runtime credential, on a database of its own. Only the client is
 * faked (an {@link OutputStream} that can vanish); the service, the repository, the SQL, the cursor, the transaction and the
 * constraints are the production ones. {@link ExportProbe} reads what the driver was actually asked to do.
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricsExportServiceIT {

    private static final YearMonth MONTH = YearMonth.now(ZoneOffset.UTC);
    private static final String D = MONTH.atDay(2).toString();
    private static final Instant T0 = Instant.parse(D + "T10:00:00Z");

    private static MetricsDb db;

    @BeforeAll
    static void start() {
        db = MetricsDb.fresh("export_service");
    }

    /** The service under test, over a probed pool and a fresh budget. */
    private static final class Rig {
        final ExportProbe probe = new ExportProbe(db.runtime());
        final MetricsDbBudget budget = new MetricsDbBudget(4);
        final JdbcMetricsExportRepository repository = new JdbcMetricsExportRepository(
                new NamedParameterJdbcTemplate(probe.dataSource()), new DataSourceTransactionManager(probe.dataSource()));
        final ExportProperties properties;
        final MetricsExportServiceImpl service;

        Rig() {
            this(ExportProperties.defaults());
        }

        Rig(ExportProperties properties) {
            this.properties = properties;
            this.service = new MetricsExportServiceImpl(repository, budget, properties);
        }
    }

    private static ExportCommand command(String metric, Instant from, Instant to, UUID... instances) {
        return new ExportCommand(metric, List.of(instances), from, to, UUID.randomUUID());
    }

    /** A site with one gauge series named {@code metric}, one raw row every 5 s from {@link #T0}. */
    private static MetricsDb.Placement site(UUID tenant, String metric, int rows) {
        MetricsDb.Placement p = db.placement(tenant);
        long series = db.series(p, metric, "gauge");
        for (int i = 0; i < rows; i++) {
            db.raw(p, series, T0.plusSeconds(5L * i).toString(), 100 + i, null);
        }
        return p;
    }

    private static List<String> lines(ByteArrayOutputStream out) {
        String csv = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        return csv.isEmpty() ? List.of() : List.of(csv.split("\r\n"));
    }

    @Test
    void theStreamIsTheBoundRowsOfTheRangeInSeriesAndTimeOrderHalfOpen() throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement a = site(tenant, "cache.size", 4);   // 10:00:00 .. 10:00:15, values 100..103
        MetricsDb.Placement b = site(tenant, "cache.size", 4);
        Rig rig = new Rig();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // [10:00:05, 10:00:15): the 10:00:15 row is excluded, the 10:00:05 row included.
        long n = rig.service.stream(tenant, command("cache.size", T0.plusSeconds(5), T0.plusSeconds(15), a.instance(), b.instance()), out);

        assertEquals(4, n);
        List<String> csv = lines(out);
        assertEquals("instance_id,placement_id,timestamp,value", csv.get(0));
        assertEquals(5, csv.size());
        // Each series is contiguous and in time order; the CSV carries the placement as well as the instance.
        String firstSeries = a.instance() + "," + a.placement() + ",";
        String secondSeries = b.instance() + "," + b.placement() + ",";
        List<String> mine = csv.subList(1, 5);
        assertEquals(2, mine.stream().filter(l -> l.startsWith(firstSeries)).count());
        assertEquals(2, mine.stream().filter(l -> l.startsWith(secondSeries)).count());
        for (String key : List.of(firstSeries, secondSeries)) {
            List<String> rows = mine.stream().filter(l -> l.startsWith(key)).toList();
            assertTrue(rows.get(0).endsWith(Instant.parse(D + "T10:00:05Z") + ",101.0"), rows.get(0));
            assertTrue(rows.get(1).endsWith(Instant.parse(D + "T10:00:10Z") + ",102.0"), rows.get(1));
        }
        int firstOfB = mine.indexOf(mine.stream().filter(l -> l.startsWith(secondSeries)).findFirst().orElseThrow());
        int firstOfA = mine.indexOf(mine.stream().filter(l -> l.startsWith(firstSeries)).findFirst().orElseThrow());
        assertEquals(2, Math.abs(firstOfB - firstOfA), "one series is written whole before the next begins");
    }

    @Test
    void anotherMetricOfTheSameInstanceIsNotExported() throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 2);
        long other = db.series(p, "cache.hits", "gauge");
        db.raw(p, other, T0.toString(), 999, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Rig().service.stream(tenant, command("cache.size", T0, T0.plusSeconds(60), p.instance()), out);
        assertEquals(3, lines(out).size());
        assertFalse(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("999"));
    }

    @Test
    void anExportNeverReadsAnotherTenant() throws IOException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        MetricsDb.Placement a = site(mine, "cache.size", 3);
        MetricsDb.Placement b = site(theirs, "cache.size", 3);
        Rig rig = new Rig();

        // A hostile (or merely stale) list that names the other tenant's instance: the tenant is bound from the
        // argument, never from the list, so the foreign instance matches nothing.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = rig.service.stream(mine, command("cache.size", T0, T0.plusSeconds(60), a.instance(), b.instance()), out);
        assertEquals(3, n);
        String csv = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains(a.instance().toString()));
        assertFalse(csv.contains(b.instance().toString()), "the other tenant's instance never appears");
        assertFalse(csv.contains(b.placement().toString()));

        // And the plan counts only the caller's own series.
        ExportPlan onlyTheirs = rig.service.plan(mine, command("cache.size", T0, T0.plusSeconds(60), b.instance()));
        assertEquals(0, onlyTheirs.projectedRows());
    }

    @Test
    void everyValueIsBoundNoTextIsInterpolated() throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 3);
        Rig rig = new Rig();
        rig.service.stream(tenant, command("cache.size", T0, T0.plusSeconds(60), p.instance()), new ByteArrayOutputStream());

        for (String fragment : List.of("sm_metrics.metrics_raw", "sm_metrics.series_segment")) {
            List<ExportProbe.Executed> ran = rig.probe.executedContaining(fragment);
            assertFalse(ran.isEmpty(), fragment + " was queried");
            for (ExportProbe.Executed e : ran) {
                assertFalse(e.sql().contains("cache.size"), "the metric name is a bound value, not text: " + e.sql());
                assertFalse(e.sql().contains(tenant.toString()), "the tenant id is bound");
                assertFalse(e.sql().contains(p.instance().toString()), "the instance id is bound");
                assertFalse(e.sql().contains(D), "the range is bound");
            }
        }
        // W3-12: a series with no declared interval (what site() makes) is also COUNTED before the stream: a second constant
        // statement, every value bound
        List<ExportProbe.Executed> counted = rig.probe.executedContaining("sm_metrics.metrics_raw").stream()
                .filter(e -> e.sql().startsWith("SELECT count(*)")).toList();
        assertEquals(1, counted.size(), "one bounded count");
        assertEquals(JdbcMetricsExportRepository.COUNT_SQL, counted.get(0).sql(), "the count's text is a constant");
        assertEquals(5, counted.get(0).sql().chars().filter(c -> c == '?').count(), "tenant, series, from, to and the limit");
        List<ExportProbe.Executed> stream = rig.probe.executedContaining("sm_metrics.metrics_raw").stream()
                .filter(e -> !e.sql().startsWith("SELECT count(*)")).toList();
        assertEquals(1, stream.size(), "one streaming statement");
        assertEquals(JdbcMetricsExportRepository.STREAM_SQL, stream.get(0).sql(), "the statement text is a constant");
        assertEquals(5, stream.get(0).sql().chars().filter(c -> c == '?').count(),
                "tenant, metric, instances, from and to are the five bound values");
        assertTrue(stream.get(0).sql().contains("r.tenant_id = ?"), "the tenant is bound into the statement");
    }

    @Test
    void theStreamingStatementCarriesANonZeroFetchSize() throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 3);
        Rig rig = new Rig();
        rig.service.stream(tenant, command("cache.size", T0, T0.plusSeconds(60), p.instance()), new ByteArrayOutputStream());

        ExportProbe.Executed e = rig.probe.executedContaining("sm_metrics.metrics_raw").stream()
                .filter(x -> x.sql().equals(JdbcMetricsExportRepository.STREAM_SQL)).findFirst().orElseThrow();
        assertTrue(e.fetchSize() > 0, "PgJDBC reads from a cursor only with a fetch size above 0; 0 caches every row: " + e.fetchSize());
        assertEquals(rig.properties.fetchSize(), e.fetchSize());
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, e.resultSetType());
        assertFalse(e.autoCommit(), "a cursor needs autocommit off, which is what a transaction gives");
        assertTrue(e.readOnly(), "the export is a read-only transaction");
    }

    @Test
    void theExportTimeoutIsThirtyMinutesAndTheTransactionIsReadOnly() throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 3);
        Rig rig = new Rig();
        List<String> seen = new ArrayList<>();
        AtomicBoolean first = new AtomicBoolean(true);
        ExportRows probing = new ExportRows() {
            @Override
            public List<SeriesSpan> spans(UUID t, String metric, List<UUID> ids) {
                return rig.repository.spans(t, metric, ids);
            }

            @Override
            public long countRaw(UUID t, List<Long> seriesIds, Instant from, Instant to, long atMost, Duration timeout) {
                return rig.repository.countRaw(t, seriesIds, from, to, atMost, timeout);
            }

            @Override
            public long stream(UUID t, String metric, List<UUID> ids, Instant from, Instant to, Duration timeout, int fetchSize,
                    Sink sink) throws IOException {
                return rig.repository.stream(t, metric, ids, from, to, timeout, fetchSize, (i, pl, ts, v) -> {
                    if (first.getAndSet(false)) {
                        // On the transaction's own connection (same DataSource): what the database is enforcing right now.
                        JdbcTemplate inside = new JdbcTemplate(rig.probe.dataSource());
                        seen.add(inside.queryForObject("SHOW statement_timeout", String.class));
                        seen.add(inside.queryForObject("SHOW transaction_read_only", String.class));
                    }
                    sink.row(i, pl, ts, v);
                });
            }
        };
        new MetricsExportServiceImpl(probing, rig.budget, rig.properties)
                .stream(tenant, command("cache.size", T0, T0.plusSeconds(60), p.instance()), new ByteArrayOutputStream());
        assertEquals(List.of("30min", "on"), seen);

        // SET LOCAL semantics: the setting does not outlive the export on a pooled connection.
        assertEquals("0", new JdbcTemplate(db.runtime()).queryForObject("SHOW statement_timeout", String.class));
    }

    @Test
    void aSlowStatementIsCutByTheTimeoutTheServiceSets() {
        // A 1 ms budget against a real range scan: the database, not the application, ends it.
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 3);
        long series = db.superuserJdbc().queryForObject("SELECT series_id FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        db.superuserJdbc().update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, received_at)"
                        + " SELECT :t, :s, :start::timestamptz + (g || ' seconds')::interval, g, :start::timestamptz"
                        + " FROM generate_series(1000, 400000) g",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", series).addValue("start", T0.toString()));
        Rig rig = new Rig();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> rig.repository.stream(tenant, "cache.size",
                List.of(p.instance()), T0, T0.plusSeconds(500_000), Duration.ofMillis(1), 10_000, (i, pl, ts, v) -> { }));
    }

    @Test
    void aClientDisconnectReleasesTheConnectionAndThePermit() throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 1);
        long series = db.superuserJdbc().queryForObject("SELECT series_id FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        db.superuserJdbc().update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, received_at)"
                        + " SELECT :t, :s, :start::timestamptz + (g || ' seconds')::interval, g, :start::timestamptz"
                        + " FROM generate_series(1, 20000) g",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", series).addValue("start", T0.toString()));
        // A small fetch size, so rows remain on the server's cursor when the client dies.
        Rig rig = new Rig(new ExportProperties(20_000_000, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(5), 100, 1));
        OutputStream vanishing = new OutputStream() {
            private int written;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > 200_000) {
                    throw new IOException("Broken pipe");
                }
            }
        };
        assertThrows(IOException.class, () -> rig.service.stream(tenant,
                command("cache.size", T0, T0.plusSeconds(100_000), p.instance()), vanishing));

        assertEquals(4, rig.budget.available(), "the permit is back");
        assertEquals(0, rig.probe.openConnections(), "the connection was returned");
        Integer stuck = db.superuserJdbc().queryForObject("SELECT count(*)::int FROM pg_stat_activity WHERE usename = 'sm_dp_runtime'"
                + " AND datname = current_database() AND state = 'idle in transaction'", new MapSqlParameterSource(), Integer.class);
        assertEquals(0, stuck, "no transaction was left open on the server");
        // The organization's slot is free: the same organization may export again at once.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(20_001, rig.service.stream(tenant, command("cache.size", T0, T0.plusSeconds(100_000), p.instance()), out));
    }

    @Test
    void thePlanReadsTheSegmentsInForceFromTheDatabase() {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 1);
        long series = db.superuserJdbc().queryForObject("SELECT series_id FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        Instant hour0 = Instant.parse(D + "T00:00:00Z");
        for (Object[] seg : new Object[][] {{hour0.minusSeconds(86_400), 30}, {hour0.plusSeconds(1800), 10}}) {
            db.superuserJdbc().update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from, package_version,"
                            + " interval_seconds) VALUES (:t, :s, :f, '2.4', :i)",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("s", series)
                            .addValue("f", OffsetDateTime.ofInstant((Instant) seg[0], ZoneOffset.UTC)).addValue("i", seg[1]));
        }
        Rig rig = new Rig();
        ExportPlan plan = rig.service.plan(tenant, command("cache.size", hour0, hour0.plusSeconds(3600), p.instance()));
        assertEquals(60 + 180, plan.projectedRows(), "30 min at 30 s, then 30 min at 10 s");
    }

    @Test
    void theLimitRefusesOnTheRealProjectionBeforeTheCursorOpens() {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 1);
        // a declared 1 s interval: the projection is the interval's, and not one raw row is read to get it
        db.superuserJdbc().update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from, package_version,"
                        + " interval_seconds) SELECT tenant_id, series_id, :f, '2.4', 1 FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant).addValue("f", OffsetDateTime.ofInstant(T0.minusSeconds(60), ZoneOffset.UTC)));
        Rig rig = new Rig(new ExportProperties(10, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(5), 10_000, 1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportTooLargeException refused = assertThrows(ExportTooLargeException.class,
                () -> rig.service.stream(tenant, command("cache.size", T0, T0.plusSeconds(3600), p.instance()), out));
        assertEquals(3600, refused.projectedRows(), "the declared 1 s interval over an hour");
        assertEquals(10, refused.limit());
        assertEquals(0, out.size());
        assertTrue(rig.probe.executedContaining("sm_metrics.metrics_raw").isEmpty(), "no raw row was ever selected");
        assertNotEquals(0, rig.probe.executedContaining("sm_metrics.series").size());
    }

    /** W3-12: a push series has no interval, so its projection is what it actually holds. */
    @Test
    void aPushSeriesIsProjectedFromACountOfItsRowsAndTheCountIsBoundedAndTenantScoped() {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "filemesh.heartbeats", 12);    // one row every 5 s: 12 in a minute, 720 in an hour
        MetricsDb.Placement other = site(UUID.randomUUID(), "filemesh.heartbeats", 50);
        Rig rig = new Rig();
        ExportPlan plan = rig.service.plan(tenant, command("filemesh.heartbeats", T0, T0.plusSeconds(3600), p.instance()));
        assertEquals(12, plan.projectedRows(), "what is held, not 3600 at a guessed 1 s (or 720 at 5 s)");

        long series = new JdbcTemplate(db.runtime()).queryForObject(
                "SELECT series_id FROM sm_metrics.series WHERE tenant_id = ? AND instance_id = ?", Long.class, tenant, p.instance());
        assertEquals(5, rig.repository.countRaw(tenant, List.of(series), T0, T0.plusSeconds(3600), 5, Duration.ofSeconds(30)),
                "stopped at the limit it was given");
        assertEquals(0, rig.repository.countRaw(other.tenant(), List.of(series), T0, T0.plusSeconds(3600), 100, Duration.ofSeconds(30)),
                "another tenant's id for this series counts nothing (I5)");

        Rig tight = new Rig(new ExportProperties(10, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(5), 10_000, 1));
        ExportTooLargeException refused = assertThrows(ExportTooLargeException.class,
                () -> tight.service.plan(tenant, command("filemesh.heartbeats", T0, T0.plusSeconds(3600), p.instance())));
        assertEquals(11, refused.projectedRows(), "counted only to limit + 1");
    }

    @Test
    void aStreamOutsideATransactionIsRefusedNotSilentlyBuffered() {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = site(tenant, "cache.size", 3);
        // The template and the transaction manager over two DataSources: the statement would run in autocommit, where
        // PgJDBC caches every row whatever the fetch size says.
        JdbcMetricsExportRepository split = new JdbcMetricsExportRepository(new NamedParameterJdbcTemplate(db.runtime()),
                new DataSourceTransactionManager(db.runtime()));
        assertThrows(IllegalStateException.class, () -> split.stream(tenant, "cache.size", List.of(p.instance()), T0,
                T0.plusSeconds(60), Duration.ofMinutes(30), 10_000, (i, pl, ts, v) -> { }));
    }
}
