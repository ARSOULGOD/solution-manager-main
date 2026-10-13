package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.export.ExportProperties;
import com.solace.sm.dp.metrics.export.MetricsExportServiceImpl;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-6.2 Step 0, MF5 (the local half): does PgJDBC 42.7.11 stream a large export through the app's pool with a
 * flat heap? Generates 5 M and then 20 M rows server-side (200 series, so the statement is the real one), streams each
 * through the REAL service and repository into a counting sink, and samples the LIVE heap (a full collection first, so the
 * figure is what is still reachable, not what has not yet been collected) every 100 k rows.
 *
 * <p>The assertion is that the high-water mark does not grow with the row count. A buffering implementation holds
 * every row until the end, so its live heap at 20 M rows is several times its live heap at 5 M. If this fails the stack is
 * NOT streaming, and M-Q18 (a) needs another look before anything ships. The numbers are printed as {@code MEASURED} lines,
 * for the plan's Results.
 */
@Testcontainers(disabledWithoutDocker = true)
class ExportStreamingMeasurementIT {

    private static final int SERIES = 200;
    private static final long SAMPLE_EVERY = 100_000;
    /** The live-heap growth the 20 M run may show over the 5 M run's high-water mark: noise, not a row-proportional trend. */
    private static final long TOLERANCE_BYTES = 64L * 1024 * 1024;
    /** A streamed export may hold one batch and a buffer: nowhere near this over the pre-stream live heap. */
    private static final long ABSOLUTE_BYTES = 192L * 1024 * 1024;

    private static final String DATABASE = "export_streaming";
    private static MetricsDb db;

    @BeforeAll
    static void start() {
        db = MetricsDb.fresh(DATABASE);
    }

    @AfterAll
    static void drop() {
        // The 20 M-row database is hundreds of megabytes; the shared container outlives this class.
        SqlScript.execute(SmDataPostgres.superuser(), "DROP DATABASE IF EXISTS sm_met_" + DATABASE + " WITH (FORCE)");
    }

    private record Run(long rows, long bytes, long baselineLive, long maxLive, double seconds, long samples) {
    }

    /** Generates {@code SERIES x perSeries} rows (one per second) for a tenant of its own and streams them. */
    private static Run measure(long perSeries) throws IOException {
        UUID tenant = UUID.randomUUID();
        MetricsDb.Placement p = db.placement(tenant);
        NamedParameterJdbcTemplate su = db.superuserJdbc();
        Instant start = Instant.parse(YearMonth.now(ZoneOffset.UTC).atDay(2) + "T00:00:00Z");
        MapSqlParameterSource args = new MapSqlParameterSource().addValue("t", tenant).addValue("i", p.instance())
                .addValue("p", p.placement()).addValue("start", start.toString()).addValue("n", perSeries)
                .addValue("series", SERIES);
        su.update("INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name, metric_type, unit,"
                + " source_discriminator, labels_fingerprint, created_at) SELECT :t, :i, :p, 'export.load', 'gauge', 'count',"
                + " 'dp-poll', 'sha256:' || md5(g::text) || md5(g::text || 'x'), now() FROM generate_series(1, :series) g", args);
        su.update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from, package_version, interval_seconds)"
                + " SELECT tenant_id, series_id, :start::timestamptz - interval '1 day', '1', 1 FROM sm_metrics.series"
                + " WHERE tenant_id = :t", args);
        long t0 = System.nanoTime();
        su.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, received_at)"
                + " SELECT s.tenant_id, s.series_id, :start::timestamptz + g * interval '1 second', g, :start::timestamptz"
                + " FROM sm_metrics.series s CROSS JOIN generate_series(0, :n - 1) g WHERE s.tenant_id = :t", args);
        System.out.printf("MEASURED generated %,d rows in %.1f s%n", SERIES * perSeries, (System.nanoTime() - t0) / 1e9);
        su.getJdbcTemplate().execute("ANALYZE sm_metrics.metrics_raw");

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long baseline = liveHeap(memory);
        long[] max = {baseline};
        long[] samples = {0};
        long[] rows = {0};
        long[] bytes = {0};
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int off, int len) {
                bytes[0] += len;
                long before = rows[0] / SAMPLE_EVERY;
                for (int i = off; i < off + len; i++) {
                    if (buffer[i] == '\n') {
                        rows[0]++;
                    }
                }
                if (rows[0] / SAMPLE_EVERY != before) {
                    samples[0]++;
                    max[0] = Math.max(max[0], liveHeap(memory));
                }
            }
        };
        // ONE DataSource for the template and the transaction manager, as production has (smDataJdbc and
        // smDataTransactionManager share smDataDataSource): two would run the cursor outside the transaction.
        javax.sql.DataSource pool = db.runtime();
        MetricsExportServiceImpl service = new MetricsExportServiceImpl(
                new JdbcMetricsExportRepository(new NamedParameterJdbcTemplate(pool), new DataSourceTransactionManager(pool)),
                new MetricsDbBudget(4), ExportProperties.defaults());
        long s0 = System.nanoTime();
        long written = service.stream(tenant, new ExportCommand("export.load", List.of(p.instance()), start,
                start.plusSeconds(perSeries), UUID.randomUUID()), sink);
        double seconds = (System.nanoTime() - s0) / 1e9;
        assertEquals(SERIES * perSeries, written, "every generated row was exported");
        assertEquals(written + 1, rows[0], "a header and one record per row reached the sink");
        return new Run(written, bytes[0], baseline, max[0], seconds, samples[0]);
    }

    /** Live heap after a full collection: what is still reachable, which is what a buffered result would grow. */
    private static long liveHeap(MemoryMXBean memory) {
        memory.gc();
        return memory.getHeapMemoryUsage().getUsed();
    }

    @Test
    void theHeapDoesNotGrowWithTheRowCountAtFiveAndTwentyMillionRows() throws IOException {
        Run five = measure(25_000);                // 200 x 25 000 = 5 M rows
        report("5 M", five);
        long grew5 = five.maxLive() - five.baselineLive();
        assertTrue(five.samples() >= 40, "the 5 M run was sampled every 100 k rows: " + five.samples());
        // Fail here, before the 20 M run: a buffering stack would spend minutes and gigabytes proving the same thing.
        assertTrue(grew5 < ABSOLUTE_BYTES, "5 M rows grew the live heap by " + mb(grew5) + " MB: not streaming");

        Run twenty = measure(100_000);             // 200 x 100 000 = 20 M rows
        report("20 M", twenty);
        long grew20 = twenty.maxLive() - twenty.baselineLive();
        assertTrue(twenty.samples() >= 190, "the 20 M run was sampled every 100 k rows: " + twenty.samples());
        assertTrue(grew20 < ABSOLUTE_BYTES, "20 M rows grew the live heap by " + mb(grew20) + " MB: not streaming");
        assertTrue(twenty.maxLive() <= five.maxLive() + TOLERANCE_BYTES,
                "four times the rows must not raise the live-heap high-water mark: 5 M peaked at " + mb(five.maxLive())
                        + " MB, 20 M at " + mb(twenty.maxLive()) + " MB");
    }

    private static void report(String label, Run r) {
        System.out.printf("MEASURED %s rows=%,d bytes=%,d seconds=%.1f rows/s=%,.0f baselineLiveMB=%d maxLiveMB=%d growthMB=%d samples=%d%n",
                label, r.rows(), r.bytes(), r.seconds(), r.rows() / r.seconds(), mb(r.baselineLive()), mb(r.maxLive()),
                mb(r.maxLive() - r.baselineLive()), r.samples());
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
