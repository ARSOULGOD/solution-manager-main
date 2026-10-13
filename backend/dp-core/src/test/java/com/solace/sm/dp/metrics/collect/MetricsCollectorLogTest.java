package com.solace.sm.dp.metrics.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Metric;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Source;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Unit;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.FetchResult;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Plan 011 P11-4.3 (live defect, 2026-10-06): a document the collector could not read is explained in the log by its
 * CLASSIFIER, so a live run shows why; and the log never carries a host, a path or a message. Also pins the live
 * defect's own shape: an SSH instance with no SSH fetcher wired is a counted, logged failure and is never read through
 * the direct fetcher.
 */
class MetricsCollectorLogTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");
    private static final String SECRET_HOST = "reference-host.internal.example";

    private final Logger logger = (Logger) LoggerFactory.getLogger(MetricsCollector.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Map<CollectorReason, Long> counted = new java.util.EnumMap<>(CollectorReason.class);

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static Unit unit(AcquisitionMode mode) {
        PlacementRef ref = new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        UUID target = UUID.randomUUID();
        Source stats = new Source(new FetchRequest(ref, target, 8081, "/api/cache/stats", Map.of()),
                List.of(new Metric("cache.hit_count", "cache", "/hitCount")));
        Source size = new Source(new FetchRequest(ref, target, 8081, "/api/cache/size", Map.of("filterByNamespace", "false")),
                List.of(new Metric("cache.size", "cache", null)));
        return new Unit(ref, target, mode, 30, List.of(stats, size), Map.of());
    }

    private void run(Unit unit, MetricFetcher direct, MetricFetcher viaSsh) {
        SourceScheduler scheduler = new SourceScheduler(4, 0.1, 8, () -> 0.5);
        scheduler.replace(TENANT, List.of(unit), T0);
        CollectorCounts counts = new CollectorCounts((tenant, placement, reason, day, count) ->
                counted.merge(reason, count, Long::sum), Clock.fixed(T0, ZoneOffset.UTC));
        TelemetryIngestPort ingest = batch -> new IngestResult.Rejected("unused");
        new MetricsCollector(scheduler, direct, viaSsh, ingest, counts, Runnable::run, Clock.fixed(T0, ZoneOffset.UTC))
                .dispatch();
        counts.flush();
    }

    private static MetricFetcher failing(String classifier) {
        return new MetricFetcher() {
            @Override
            public FetchResult fetch(FetchRequest request) {
                return new FetchResult.Transport(classifier);
            }

            @Override
            public RestartRead restartToken(RestartRequest request) {
                return new RestartRead.Failed(classifier);
            }
        };
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aFailedDocumentIsLoggedByItsClassifierAndNeverByAHostAPathOrAMessage() {
        run(unit(AcquisitionMode.VIA_SSH), failing(SECRET_HOST), failing("ssh.connection.failed"));

        assertEquals(2L, counted.get(CollectorReason.SCRAPE_FAILED));
        String line = String.join("\n", warnings());
        assertTrue(line.contains("ssh.connection.failed"), "the classifier explains the failure: " + line);
        assertFalse(line.contains("/api/cache"), "no path: " + line);
        assertFalse(line.contains("8081"), "no port: " + line);
        assertFalse(line.contains(SECRET_HOST), "no host: " + line);
        assertTrue(line.contains("2 of 2"), "how many: " + line);
    }

    @Test
    void anHttpAnswerThatIsNotASuccessIsLoggedByItsStatus() {
        MetricFetcher unavailable = new MetricFetcher() {
            @Override
            public FetchResult fetch(FetchRequest request) {
                return new FetchResult.Http(503);
            }

            @Override
            public RestartRead restartToken(RestartRequest request) {
                return new RestartRead.NotDeclared();
            }
        };
        run(unit(AcquisitionMode.DIRECT_HTTP), unavailable, null);

        assertTrue(String.join("\n", warnings()).contains("http.503"), String.join("\n", warnings()));
    }

    @Test
    void anSshInstanceWithNoSshFetcherIsCountedAndLoggedAndNeverReadDirectly() {
        run(unit(AcquisitionMode.VIA_SSH), failing("direct.must.not.be.used"), null);

        assertEquals(2L, counted.get(CollectorReason.SCRAPE_FAILED));
        String line = String.join("\n", warnings());
        assertTrue(line.contains("fetcher.unavailable"), line);
        assertTrue(line.contains("via-ssh"), line);
        assertFalse(line.contains("direct.must.not.be.used"), line);
    }

    @Test
    void aDeferredDocumentIsAMissedDeadlineNeverAFailedScrapeAndNeverBacksTheHostOff() {
        // W3-02: the pool had no slot; the host was never asked
        MetricFetcher busy = new MetricFetcher() {
            @Override
            public FetchResult fetch(FetchRequest request) {
                return new FetchResult.Deferred("ssh.pool.busy");
            }

            @Override
            public RestartRead restartToken(RestartRequest request) {
                return new RestartRead.Failed("ssh.pool.busy");
            }
        };
        Unit unit = unit(AcquisitionMode.VIA_SSH);
        SourceScheduler scheduler = new SourceScheduler(4, 0.0, 8, () -> 0.5);
        scheduler.replace(TENANT, List.of(unit), T0);
        CollectorCounts counts = new CollectorCounts((tenant, placement, reason, day, count) ->
                counted.merge(reason, count, Long::sum), Clock.fixed(T0, ZoneOffset.UTC));
        TelemetryIngestPort ingest = batch -> new IngestResult.Rejected("unused");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(T0);
        Clock moving = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        MetricsCollector collector = new MetricsCollector(scheduler, failing("direct.unused"), busy, ingest, counts,
                Runnable::run, moving);
        collector.dispatch();                       // run 1 at T0
        now.set(T0.plusSeconds(30));
        collector.dispatch();                       // run 2 one interval later
        counts.flush();

        assertEquals(4L, counted.get(CollectorReason.DEADLINE_MISSED));
        assertFalse(counted.containsKey(CollectorReason.SCRAPE_FAILED), counted.toString());
        // a host failure twice would rest the target for 2x (60 s) after run 2: it is due again at 60 s only if not backed off
        assertEquals(1, scheduler.due(T0.plusSeconds(60)).start().size(), "a deferral is not a host failure");
    }

    @Test
    void theNoFetcherExplanationIsLoggedOncePerModeNotOncePerRunAndEveryDocumentIsStillCounted() {
        // W3-10: 200 placements would otherwise write one WARN per placement per interval
        Unit unit = unit(AcquisitionMode.VIA_SSH);
        SourceScheduler scheduler = new SourceScheduler(4, 0.0, 8, () -> 0.5);
        scheduler.replace(TENANT, List.of(unit), T0);
        CollectorCounts counts = new CollectorCounts((tenant, placement, reason, day, count) ->
                counted.merge(reason, count, Long::sum), Clock.fixed(T0, ZoneOffset.UTC));
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(T0);
        Clock moving = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        MetricsCollector collector = new MetricsCollector(scheduler, failing("direct.unused"), null,
                batch -> new IngestResult.Rejected("unused"), counts, Runnable::run, moving);
        for (int run = 0; run < 5; run++) {
            now.set(T0.plusSeconds(300L * run));   // past the longest back-off (8 x 30 s)
            collector.dispatch();
        }
        counts.flush();

        assertEquals(10L, counted.get(CollectorReason.SCRAPE_FAILED), "two documents, five runs: all counted");
        assertEquals(1, warnings().stream().filter(w -> w.contains("fetcher.unavailable")).count(), String.join("\n", warnings()));
    }

    @Test
    void flushingTheCountsLetsTheSshFetcherReleaseItsIdleSessions() {
        int[] evicted = {0};
        MetricFetcher ssh = new MetricFetcher() {
            @Override
            public FetchResult fetch(FetchRequest request) {
                return new FetchResult.Transport("unused");
            }

            @Override
            public RestartRead restartToken(RestartRequest request) {
                return new RestartRead.NotDeclared();
            }

            @Override
            public void housekeeping() {
                evicted[0]++;
            }
        };
        SourceScheduler scheduler = new SourceScheduler(4, 0.0, 8, () -> 0.5);
        CollectorCounts counts = new CollectorCounts((tenant, placement, reason, day, count) -> { },
                Clock.fixed(T0, ZoneOffset.UTC));
        new MetricsCollector(scheduler, failing("x"), ssh, batch -> new IngestResult.Rejected("unused"), counts,
                Runnable::run, Clock.fixed(T0, ZoneOffset.UTC)).flushCounts();
        assertEquals(1, evicted[0], "with health off nothing else closes an idle session (W3-10)");
    }
}
