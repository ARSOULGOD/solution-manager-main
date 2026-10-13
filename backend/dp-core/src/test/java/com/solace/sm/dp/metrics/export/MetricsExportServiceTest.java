package com.solace.sm.dp.metrics.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.ExportPlan;
import com.solace.sm.dp.api.metrics.export.ExportTooLargeException;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-6.2: the export service's own rules (the row limit, the concurrency caps, the one shared budget, the
 * release on every path) over a fake row source. The SQL, the cursor and the transaction are
 * {@code MetricsExportServiceIT}'s.
 */
class MetricsExportServiceTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID ORG_C = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-10-06T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-06T01:00:00Z");

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private static ExportCommand command() {
        return new ExportCommand("cache.size", List.of(UUID.randomUUID()), FROM, TO, UUID.randomUUID());
    }

    private static ExportProperties props(long rowLimit, int perOrg, int global, Duration wait) {
        return new ExportProperties(rowLimit, perOrg, global, Duration.ofMinutes(30), wait, 10_000, 1);
    }

    /** A row source: the given series spans for the projection, and {@code rows} rows pushed to the sink. */
    private static final class FakeRows implements ExportRows {
        final List<SeriesSpan> spans;
        final long rows;
        final AtomicInteger streams = new AtomicInteger();
        final AtomicInteger spanReads = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release;
        volatile RuntimeException failAfterRows;

        FakeRows(List<SeriesSpan> spans, long rows) {
            this.spans = spans;
            this.rows = rows;
        }

        @Override
        public List<SeriesSpan> spans(UUID tenantId, String metric, List<UUID> instanceIds) {
            spanReads.incrementAndGet();
            return spans;
        }

        /** What a series with no declared interval actually holds in the range (W3-12). */
        volatile long observed;
        final List<Long> countedAtMost = new ArrayList<>();
        final List<List<Long>> countedSeries = new ArrayList<>();

        @Override
        public long countRaw(UUID tenantId, List<Long> seriesIds, Instant from, Instant to, long atMost,
                Duration statementTimeout) {
            countedAtMost.add(atMost);
            countedSeries.add(seriesIds);
            return Math.min(observed, atMost);
        }

        @Override
        public long stream(UUID tenantId, String metric, List<UUID> instanceIds, Instant from, Instant to,
                Duration statementTimeout, int fetchSize, Sink sink) throws IOException {
            streams.incrementAndGet();
            entered.countDown();
            if (release != null) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            UUID instance = UUID.randomUUID();
            UUID placement = UUID.randomUUID();
            for (long i = 0; i < rows; i++) {
                sink.row(instance, placement, FROM.plusSeconds(i), i);
                if (failAfterRows != null && i == 1) {
                    throw failAfterRows;
                }
            }
            return rows;
        }
    }

    private static ExportRows.SeriesSpan series(Integer intervalSeconds) {
        return new ExportRows.SeriesSpan(1, List.of(new ExportRows.Segment(Instant.parse("2026-01-01T00:00:00Z"), intervalSeconds)));
    }

    private static MetricsExportServiceImpl service(FakeRows rows, MetricsDbBudget budget, ExportProperties props) {
        return new MetricsExportServiceImpl(rows, budget, props);
    }

    // ---- the plan -----------------------------------------------------------------------------------------------

    @Test
    void thePlanIsRangeOverTheIntervalInForceSummedOverSeries() {
        // One hour: a 30 s series is 120 rows, a 60 s series 60, a 7 s series ceil(3600 / 7) = 515.
        FakeRows rows = new FakeRows(List.of(series(30), series(60), series(7)), 0);
        ExportPlan plan = service(rows, new MetricsDbBudget(4), props(1_000, 1, 2, Duration.ofSeconds(1))).plan(ORG_A, command());
        assertEquals(120 + 60 + 515, plan.projectedRows());
        assertEquals(1_000, plan.limit());
    }

    @Test
    void aSegmentChangeSplitsTheRangeAtTheBoundaryAndEachSideUsesItsOwnInterval() {
        // 00:00-00:30 at 30 s (60 rows), then from 00:30 at 10 s (180 rows).
        ExportRows.SeriesSpan span = new ExportRows.SeriesSpan(1, List.of(
                new ExportRows.Segment(Instant.parse("2026-01-01T00:00:00Z"), 30),
                new ExportRows.Segment(Instant.parse("2026-10-06T00:30:00Z"), 10)));
        FakeRows rows = new FakeRows(List.of(span), 0);
        ExportPlan plan = service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1))).plan(ORG_A, command());
        assertEquals(60 + 180, plan.projectedRows());
    }

    @Test
    void aSeriesBornInsideTheRangeProjectsOnlyFromItsFirstSegment() {
        ExportRows.SeriesSpan span = new ExportRows.SeriesSpan(1, List.of(new ExportRows.Segment(Instant.parse("2026-10-06T00:30:00Z"), 10)));
        FakeRows rows = new FakeRows(List.of(span), 0);
        assertEquals(180, service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1))).plan(ORG_A, command()).projectedRows());
    }

    @Test
    void aPushSeriesWithNoIntervalIsProjectedFromWhatItActuallyHoldsNotFromAGuess() {
        // W3-12: File Mesh heartbeats arrive every 5 s and nothing declares it; a fallback of 1 s over an hour would project 3600
        FakeRows rows = new FakeRows(List.of(new ExportRows.SeriesSpan(7, List.of())), 0);
        rows.observed = 720;
        ExportPlan plan = service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1))).plan(ORG_A, command());
        assertEquals(720, plan.projectedRows());
        assertEquals(List.of(List.of(7L)), rows.countedSeries, "only the series with no interval are counted");
    }

    @Test
    void aPushSeriesIsCountedOnlyUpToTheRoomLeftUnderTheLimitPlusOne() {
        // 1,000 limit, one declared 60 s series (60 rows): 940 of room, so the count stops at 941
        FakeRows rows = new FakeRows(List.of(series(60), new ExportRows.SeriesSpan(8, List.of())), 0);
        rows.observed = 5_000_000;
        MetricsExportServiceImpl service = service(rows, new MetricsDbBudget(4), props(1_000, 1, 2, Duration.ofSeconds(1)));
        ExportTooLargeException refused = assertThrows(ExportTooLargeException.class, () -> service.plan(ORG_A, command()));
        assertEquals(List.of(941L), rows.countedAtMost, "bounded work, whatever the series holds");
        assertEquals(60 + 941, refused.projectedRows());
        assertEquals(1_000, refused.limit());
    }

    @Test
    void aSeriesWithOnlyDeclaredIntervalsIsNeverCounted() {
        FakeRows rows = new FakeRows(List.of(series(30)), 0);
        service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1))).plan(ORG_A, command());
        assertEquals(List.of(), rows.countedAtMost, "no raw read for a projection an interval can answer");
    }

    @Test
    void thePlanRefusesPastTheRowLimitBeforeAnyByte() {
        FakeRows rows = new FakeRows(List.of(series(1)), 5);   // 3600 projected
        MetricsExportServiceImpl service = service(rows, new MetricsDbBudget(4), props(3_599, 1, 2, Duration.ofSeconds(1)));
        ExportTooLargeException refused = assertThrows(ExportTooLargeException.class, () -> service.plan(ORG_A, command()));
        assertEquals(3_600, refused.projectedRows());
        assertEquals(3_599, refused.limit());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(ExportTooLargeException.class, () -> service.stream(ORG_A, command(), out));
        assertEquals(0, out.size(), "not one byte, not even the header, before the refusal");
        assertEquals(0, rows.streams.get(), "the cursor was never opened");
    }

    @Test
    void theLimitItselfIsAllowed() {
        FakeRows rows = new FakeRows(List.of(series(1)), 0);
        assertEquals(3_600, service(rows, new MetricsDbBudget(4), props(3_600, 1, 2, Duration.ofSeconds(1))).plan(ORG_A, command()).projectedRows());
    }

    @Test
    void aStreamThatOutrunsItsProjectionIsStoppedAtTheLimitNeverDeliveredPastIt() {
        // Projected 3600 (within a 3600 limit), but the source has 3601 rows: the 3601st would pass the limit.
        FakeRows rows = new FakeRows(List.of(series(1)), 3_601);
        MetricsExportServiceImpl service = service(rows, new MetricsDbBudget(4), props(3_600, 1, 2, Duration.ofSeconds(1)));
        ExportTooLargeException refused = assertThrows(ExportTooLargeException.class,
                () -> service.stream(ORG_A, command(), new ByteArrayOutputStream()));
        assertEquals(3_600, refused.limit());
    }

    // ---- the stream ---------------------------------------------------------------------------------------------

    @Test
    void theStreamIsAHeaderAndOneRecordPerRowAndReturnsTheRowCount() throws IOException {
        FakeRows rows = new FakeRows(List.of(series(1)), 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1))).stream(ORG_A, command(), out);
        assertEquals(3, n);
        String csv = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(4, csv.split("\r\n").length, "a header and three rows: " + csv);
        assertTrue(csv.startsWith("instance_id,placement_id,timestamp,value\r\n"));
    }

    // ---- the concurrency caps -----------------------------------------------------------------------------------

    /** Starts an export that holds its slot and permit until {@code release} is counted down. */
    private Future<Long> holding(MetricsExportServiceImpl service, FakeRows rows, UUID org) throws InterruptedException {
        rows.entered = new CountDownLatch(1);
        Future<Long> held = pool.submit(() -> service.stream(org, command(), new ByteArrayOutputStream()));
        assertTrue(rows.entered.await(5, TimeUnit.SECONDS), "the export is running and holds its slot");
        return held;
    }

    @Test
    void aSecondExportInOneOrganizationIsBusy() throws Exception {
        FakeRows rows = new FakeRows(List.of(series(1)), 1);
        rows.release = new CountDownLatch(1);
        MetricsExportServiceImpl service = service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1)));
        Future<Long> first = holding(service, rows, ORG_A);

        ExportBusyException busy = assertThrows(ExportBusyException.class,
                () -> service.stream(ORG_A, command(), new ByteArrayOutputStream()));
        assertEquals(ExportBusyException.Reason.ORGANIZATION, busy.reason());

        // Another organization is not blocked by it, and the refused call took no slot of its own.
        rows.release.countDown();
        assertEquals(1, first.get(5, TimeUnit.SECONDS));
        assertEquals(1, service.stream(ORG_A, command(), new ByteArrayOutputStream()), "the slot is free again");
    }

    @Test
    void aThirdGlobalExportIsBusy() throws Exception {
        FakeRows rows = new FakeRows(List.of(series(1)), 1);
        rows.release = new CountDownLatch(1);
        MetricsExportServiceImpl service = service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1)));
        Future<Long> a = holding(service, rows, ORG_A);
        Future<Long> b = holding(service, rows, ORG_B);

        ExportBusyException busy = assertThrows(ExportBusyException.class,
                () -> service.stream(ORG_C, command(), new ByteArrayOutputStream()));
        assertEquals(ExportBusyException.Reason.INSTALL, busy.reason());

        rows.release.countDown();
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertEquals(1, service.stream(ORG_C, command(), new ByteArrayOutputStream()), "both slots came back");
    }

    @Test
    void aRefusedExportWritesNothingAndOpensNoCursor() throws Exception {
        FakeRows rows = new FakeRows(List.of(series(1)), 1);
        rows.release = new CountDownLatch(1);
        MetricsExportServiceImpl service = service(rows, new MetricsDbBudget(4), props(10_000, 1, 2, Duration.ofSeconds(1)));
        Future<Long> first = holding(service, rows, ORG_A);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(ExportBusyException.class, () -> service.stream(ORG_A, command(), out));
        assertEquals(0, out.size());
        assertEquals(1, rows.streams.get(), "only the first export opened a cursor");
        rows.release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    // ---- the one budget -----------------------------------------------------------------------------------------

    @Test
    void queriesAndExportsShareOneBudgetOfFour() throws Exception {
        MetricsDbBudget budget = new MetricsDbBudget(4);
        // Three QUERIES hold three permits (the query task takes its permit from this same bean).
        List<MetricsDbBudget.Permit> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queries.add(budget.acquire(Duration.ofMillis(50)));
        }
        FakeRows rows = new FakeRows(List.of(series(1)), 1);
        rows.release = new CountDownLatch(1);
        MetricsExportServiceImpl service = service(rows, budget, props(10_000, 1, 2, Duration.ofMillis(300)));

        // Two EXPORTS start together: exactly one gets the fourth permit; the other (a different organization,
        // so no cap refuses it) waits for the budget and is refused when the wait is spent.
        Future<Long> holder = holding(service, rows, ORG_A);
        assertEquals(0, budget.available(), "four hold a permit: three queries and one export");
        ExportBusyException busy = assertThrows(ExportBusyException.class,
                () -> service.stream(ORG_B, command(), new ByteArrayOutputStream()));
        assertEquals(ExportBusyException.Reason.DATABASE_BUDGET, busy.reason());
        assertEquals(1, rows.streams.get(), "the fifth never ran");

        // When a query lets go, the waiting kind (an export) gets the permit: whichever kind it is.
        queries.get(0).close();
        rows.release.countDown();
        assertEquals(1, service.stream(ORG_B, command(), new ByteArrayOutputStream()));
        holder.get(5, TimeUnit.SECONDS);
        queries.forEach(MetricsDbBudget.Permit::close);
        assertEquals(4, budget.available());
    }

    @Test
    void theBudgetIsTheInjectedOneNeverARebuiltOne() {
        MetricsDbBudget budget = new MetricsDbBudget(1);
        FakeRows rows = new FakeRows(List.of(series(1)), 1);
        MetricsExportServiceImpl service = service(rows, budget, props(10_000, 1, 2, Duration.ofMillis(100)));
        try (MetricsDbBudget.Permit query = budget.acquire(Duration.ofMillis(50))) {
            assertThrows(ExportBusyException.class, () -> service.stream(ORG_A, command(), new ByteArrayOutputStream()),
                    "the only permit is held by a query: an export of a service holding its own budget would not notice");
        }
    }

    @Test
    void thePlanReadTakesAPermitToo() {
        MetricsDbBudget budget = new MetricsDbBudget(1);
        FakeRows rows = new FakeRows(List.of(series(1)), 0);
        MetricsExportServiceImpl service = service(rows, budget, props(10_000, 1, 2, Duration.ofMillis(100)));
        try (MetricsDbBudget.Permit query = budget.acquire(Duration.ofMillis(50))) {
            ExportBusyException busy = assertThrows(ExportBusyException.class, () -> service.plan(ORG_A, command()));
            assertEquals(ExportBusyException.Reason.DATABASE_BUDGET, busy.reason());
        }
        assertEquals(0, rows.spanReads.get(), "no database work was done without a permit");
        assertEquals(1, budget.available());
    }

    // ---- release on every path ----------------------------------------------------------------------------------

    @Test
    void aClientThatVanishesMidStreamReleasesThePermitAndTheSlot() {
        MetricsDbBudget budget = new MetricsDbBudget(4);
        FakeRows rows = new FakeRows(List.of(series(1)), 100);
        MetricsExportServiceImpl service = service(rows, budget, props(10_000, 1, 2, Duration.ofSeconds(1)));
        OutputStream gone = new OutputStream() {
            private int calls;

            @Override
            public void write(int b) throws IOException {
                throw new IOException("client went away");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (++calls > 0) {
                    throw new IOException("client went away");
                }
            }
        };
        assertThrows(IOException.class, () -> service.stream(ORG_A, command(), gone));
        assertEquals(4, budget.available(), "the permit came back");
        assertDoesNotThrowBusy(service);
    }

    @Test
    void aFailingSourceReleasesThePermitAndTheSlotToo() {
        MetricsDbBudget budget = new MetricsDbBudget(4);
        FakeRows rows = new FakeRows(List.of(series(1)), 100);
        rows.failAfterRows = new IllegalStateException("the database went away");
        MetricsExportServiceImpl service = service(rows, budget, props(10_000, 1, 2, Duration.ofSeconds(1)));
        assertThrows(IllegalStateException.class, () -> service.stream(ORG_A, command(), new ByteArrayOutputStream()));
        assertEquals(4, budget.available());
        rows.failAfterRows = null;
        assertDoesNotThrowBusy(service);
    }

    private void assertDoesNotThrowBusy(MetricsExportServiceImpl service) {
        try {
            service.stream(ORG_A, command(), new ByteArrayOutputStream());
        } catch (ExportBusyException e) {
            throw new AssertionError("the organization's slot was not released: " + e.reason(), e);
        } catch (Exception expected) {
            // the fake may still fail or the client may still be gone; only a busy refusal is the defect here
        }
    }

    // ---- the properties -----------------------------------------------------------------------------------------

    @Test
    void theDefaultsAreTheRuledOnes() {
        ExportProperties d = ExportProperties.defaults();
        assertEquals(20_000_000L, d.rowLimit(), "M-Q22 (4)");
        assertEquals(1, d.perOrganization(), "M-Q22 (3)");
        assertEquals(2, d.global(), "M-Q22 (3)");
        assertEquals(Duration.ofMinutes(30), d.statementTimeout(), "M-Q22 (2)");
        assertTrue(d.fetchSize() > 0, "a zero fetch size would buffer the whole result");
    }

    @Test
    void anInvalidSettingIsRefusedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(0, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(1), 10_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(1, 0, 2, Duration.ofMinutes(30), Duration.ofSeconds(1), 10_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(1, 1, 0, Duration.ofMinutes(30), Duration.ofSeconds(1), 10_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(1, 1, 2, Duration.ZERO, Duration.ofSeconds(1), 10_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(1, 1, 2, Duration.ofMinutes(30), Duration.ZERO, 10_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(1, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportProperties(1, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(1), 10_000, 0));
    }

    @Test
    void aCommandIsValidatedAndCopied() {
        UUID id = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>(List.of(id));
        ExportCommand c = new ExportCommand("m", ids, FROM, TO, UUID.randomUUID());
        ids.clear();
        assertEquals(List.of(id), c.instanceIds(), "the list is copied");
        assertThrows(IllegalArgumentException.class, () -> new ExportCommand(" ", List.of(), FROM, TO, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new ExportCommand("m", List.of(), TO, FROM, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new ExportCommand("m", List.of(), FROM, FROM, UUID.randomUUID()));
        assertFalse(c.instanceIds().isEmpty());
    }
}
