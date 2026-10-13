package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Plan 011 P11-8.1: the storage gate run end to end against a LOCAL throwaway {@code postgres:14} at a tiny scale, in the
 * order the integrator will run it on the host: {@code prepare}, Step 0b, the rest. It proves the gate works and produces its
 * evidence lines; it proves nothing about S-A (every line says {@code SCALED-DOWN}). It uses a container of its own, not the
 * suite's shared one, because the real flow starts from an EMPTY cluster and runs Phase 0 itself.
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricsStorageBenchmarkLocalIT extends MetricsStorageBenchmarkBase {

    static final String RUNTIME_PASSWORD = "bench-runtime-" + UUID.randomUUID();
    static final String MIGRATOR_PASSWORD = "bench-migrator-" + UUID.randomUUID();

    static PostgreSQLContainer<?> postgres;
    static Path out;
    static Map<String, String> props;
    static Map<String, String> env;

    @Override
    protected BenchSettings settings() {
        try {
            out = Files.createTempDirectory("storage-gate-local");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14"));
        postgres.start();
        props = new HashMap<>();
        props.put("sm.bench.url", "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName());
        props.put("sm.bench.admin.user", postgres.getUsername());
        props.put("sm.bench.steps", "prepare,0b,1,2,2b,5,6a");
        props.put("sm.bench.instances", "4");
        props.put("sm.bench.metrics", "5");
        props.put("sm.bench.interval-seconds", "60");
        props.put("sm.bench.days", "3");
        props.put("sm.bench.scaled.series", "10");
        props.put("sm.bench.scaled.interval-seconds", "30");
        props.put("sm.bench.ingest.ticks", "3");
        props.put("sm.bench.free-disk-bytes", String.valueOf(20L * 1024 * 1024 * 1024));
        props.put("sm.bench.out", out.toString());
        env = Map.of("SM_BENCH_ADMIN_PASSWORD", postgres.getPassword(), "SM_BENCH_RUNTIME_PASSWORD", RUNTIME_PASSWORD,
                "SM_BENCH_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD);
        return BenchSettings.from(props::get, env::get);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** The same database and scale with some settings changed, and a results directory of its own unless one is given. */
    private static StorageBenchmark another(Map<String, String> changes) throws IOException {
        Map<String, String> changed = new HashMap<>(props);
        if (!changes.containsKey("sm.bench.out")) {
            changed.put("sm.bench.out", Files.createTempDirectory("storage-gate-local-more").toString());
        }
        changed.putAll(changes);
        return new StorageBenchmark(BenchSettings.from(changed::get, env::get), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    @Order(100)
    void everyStepLeftItsEvidenceAndNoPasswordWasPrinted() throws IOException {
        BenchResults r = bench.results();
        long perMonth = settings.rowsPerMonth();
        assertEquals(4L * 5 * 1440 * 3, perMonth);
        assertEquals(String.valueOf(perMonth), r.get("1.rowsTotal").orElseThrow(), "the whole scaled month is loaded");
        assertEquals(String.valueOf(4L * 5 * 1440), r.get("0b.rows").orElseThrow(), "one day of the scaled shape");
        assertEquals("28800", r.get("1.rowsAlreadyLoaded").orElseThrow(), "Step 1 reused the day Step 0b loaded");
        assertEquals("57600", r.get("1.rowsLoadedThisRun").orElseThrow(), "and loaded the other two");
        assertTrue(r.getDouble("0b.bytesPerRow").orElseThrow() > 30, "a row with its indexes is more than 30 bytes");
        assertEquals(r.getLong("0b.dayBytesWithIndexes").getAsLong() / 28800.0, r.getDouble("0b.bytesPerRow").orElseThrow(), 0.01,
                "bytes/row is the day's size delta over its rows");
        assertTrue(r.getDouble("1.bytesPerRow").orElseThrow() > 30);
        assertTrue(r.getDouble("1.walBytesPerRow").orElseThrow() > 0, "WAL was written by the load");
        assertEquals(GateMath.monthProjection(r.getLong("0b.dayBytes").getAsLong(), r.getLong("0b.dayWalBytes").getAsLong(), 3,
                settings.floorBytes()), r.getLong("0b.monthProjectionBytes").getAsLong());
        assertEquals("true", r.get("0b.fits").orElseThrow());
        assertEquals("60", r.get("2.ingest.samplesCommitted").orElseThrow(), "3 ticks x 4 instances x 5 metrics, every sample committed");
        assertEquals("{Committed=12}", r.get("2.ingest.batchOutcomes").orElseThrow(), "through the real IngestService, none refused or held");
        assertEquals("28800", r.get("2.serverSide.rows").orElseThrow());
        assertEquals("1440", r.get("2b.backfill.hourlyRowsWritten").orElseThrow(), "20 series x 72 hours");
        assertEquals("[3600, 3600, 3600]", r.get("2b.steady.defaultCadence.rawRowsReadEachRun").orElseThrow(),
                "the late window is three hours of 20 series at 60 s");
        assertEquals("[3600, 3600, 3600]", r.get("2b.steady.tenSecondCadence.rawRowsReadEachRun").orElseThrow());
        assertEquals("300", r.get("2b.steady.defaultCadence.intervalSeconds").orElseThrow());
        assertEquals("10", r.get("2b.steady.tenSecondCadence.intervalSeconds").orElseThrow());
        assertEquals("10", r.get("5.series").orElseThrow());
        assertEquals("28800", r.get("5.rows").orElseThrow(), "one day at 30 s for 10 series is 28 800 rows");
        assertEquals("false", r.get("6a.fits").orElseThrow(), "105 M rows do not fit 20 GiB: the expected answer");
        String log = Files.readString(out.resolve(Evidence.LOG));
        assertTrue(log.contains("SCALED-DOWN, NOT S-A"), "a tiny run says so");
        // The live run printed "432 M rows/month x 6" beside a figure computed from 86.4 M rows/month x 6 (518.4 M rows): the label was wrong.
        assertTrue(log.contains("S-A's 86.4 M rows/month x 6 (518.4 M rows)"), "the six-month line names the rows it multiplied");
        assertFalse(log.contains("432 M"), "S-A's month is 86.4 M rows, not 432 M");
        assertFalse(log.contains(RUNTIME_PASSWORD) || log.contains(MIGRATOR_PASSWORD), "no password reaches the evidence");
        assertFalse(Files.readString(out.resolve(BenchResults.FILE)).contains(RUNTIME_PASSWORD));
    }

    @Test
    @Order(101)
    void theLoadIsTimeMajorSoTheIndexGrowsTheWayIngestGrowsIt() {
        UUID tenant = UUID.fromString(bench.results().get("site.main.tenant").orElseThrow());
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(props.get("sm.bench.url"), postgres.getUsername(), postgres.getPassword()));
        List<String> firstTwenty = admin.queryForList("SELECT ts::text FROM sm_metrics.metrics_raw WHERE tenant_id = ? ORDER BY ctid LIMIT 20",
                String.class, tenant);
        assertEquals(20, firstTwenty.size());
        assertEquals(1, firstTwenty.stream().distinct().count(),
                "the first rows on disk are every series at ONE tick (series-major order would show 20 different timestamps)");
    }

    @Test
    @Order(102)
    void aSecondRunOfStep1LoadsNothingAndAFinishedPrepareIsHarmless() {
        bench.step1();
        assertEquals("0", bench.results().get("1.rowsLoadedThisRun").orElseThrow(), "every hour was already there: a stopped load resumes");
        assertEquals("86400", bench.results().get("1.rowsTotal").orElseThrow());
        bench.prepare();
        bench.step1();
    }

    @Test
    @Order(103)
    void aMonthThatDoesNotFitStopsStep1AndOffersTheScaledOption() throws IOException {
        Path more = Files.createTempDirectory("storage-gate-local-nofit");
        try (StorageBenchmark small = another(Map.of("sm.bench.out", more.toString(), "sm.bench.free-disk-bytes", "3230000000"))) {
            small.step0b();
            assertTrue(small.results().getDouble("0b.bytesPerRow").orElseThrow() < 400,
                    "the day's bytes are the size DELTA, not the partition's whole size: it loaded into a partition already holding a month");
            assertEquals("false", small.results().get("0b.fits").orElseThrow());
            assertEquals("0", small.results().get("0b.largestFittingDays").orElseThrow());
            IllegalStateException refused = assertThrows(IllegalStateException.class, small::step1);
            assertTrue(refused.getMessage().contains("scaled option"), refused.getMessage());
            assertTrue(Files.readString(more.resolve(Evidence.LOG)).contains("DOES NOT FIT"), "the evidence says so loudly");
        }
    }

    @Test
    @Order(104)
    void theLoadStopsAtTheDiskCeilingInsteadOfFillingTheHost() throws IOException {
        try (StorageBenchmark capped = another(Map.of("sm.bench.max-db-bytes", "1"))) {
            IllegalStateException stopped = assertThrows(IllegalStateException.class, capped::step0b);
            assertTrue(stopped.getMessage().contains("disk ceiling"), stopped.getMessage());
        }
    }

    @Test
    @Order(106)
    void theHostCpuIsSampledWhileTheRollupRunsWhenTheOperatorGivesACommand() throws IOException {
        Path fake = Files.writeString(out.resolve("fake-vmstat.sh"), """
                echo ' r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st'
                echo ' 1  0      0 123456  12345 234567    0    0     1     2   10   20  5  3 90  1  1'
                echo ' 2  0      0 123456  12345 234567    0    0     1     2   10   20 40 10 40  5  5'
                sleep 60
                """);
        try (StorageBenchmark sampled = another(Map.of("sm.bench.out", out.toString(), "sm.bench.cpu-command", "bash " + fake,
                "sm.bench.cpu-baseline-percent", "1"))) {
            sampled.step2b();
            BenchResults r = sampled.results();
            assertEquals("1", r.get("2b.steady.defaultCadence.hostCpuSamples").orElseThrow());
            assertEquals("50.00", r.get("2b.steady.defaultCadence.hostCpuBusyPercentMean").orElseThrow());
            assertTrue(r.get("2b.steady.defaultCadence.sustainedBusyPercentEstimate").isPresent());
        }
    }

    @Test
    @Order(105)
    void theStreamFitsWhenTheOperatorSaysThereIsRoom() {
        try (StorageBenchmark roomy = another(Map.of("sm.bench.out", out.toString(), "sm.bench.free-disk-after-drop-bytes", "1000000000000"))) {
            roomy.step6a();
            assertEquals("true", roomy.results().get("6a.fits").orElseThrow());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
