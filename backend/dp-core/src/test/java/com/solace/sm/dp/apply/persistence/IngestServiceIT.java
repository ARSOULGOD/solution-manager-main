package com.solace.sm.dp.apply.persistence;

import static com.solace.sm.dp.apply.persistence.IngestFixture.base;
import static com.solace.sm.dp.apply.persistence.IngestFixture.batch;
import static com.solace.sm.dp.apply.persistence.IngestFixture.metric;
import static com.solace.sm.dp.apply.persistence.IngestFixture.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.GapMarker;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.apply.persistence.IngestFixture.Point;
import com.solace.sm.dp.apply.persistence.IngestFixture.Site;
import com.solace.sm.dp.metrics.ingest.IngestService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-3.1: the telemetry ingest port over real PostgreSQL as the runtime credential: SM-held identity (I1),
 * the ruled counter semantics (M-Q9 d', Review Focus 2), caps, duplicates, and degradation (Review Focus 5). Each test
 * takes a tenant of its own, so one database serves them all; the tests that change partitions or take the database
 * away say so.
 *
 * <p>Interval 180 s is the S-D scenario's (300 s between samples is a delta, 600 s is a gap).
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestServiceIT {

    private static IngestFixture fx;
    private static IngestService service;

    private static final List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> DEFS = List.of(
            metric("cache.requests", "counter", "count", "cache", 180),
            metric("cache.errors", "counter", "count", "cache", 180),
            metric("cache.size", "gauge", "entries", "cache", 180),
            metric("cache.latency", "histogram", "ms", "cache", 180));

    @BeforeAll
    static void start() {
        fx = IngestFixture.create("ingest_service");
        service = fx.service(IngestFixture.fast(20, 500));
    }

    @AfterAll
    static void stop() {
        fx.close();
    }

    private static Site viaSsh() {
        return fx.site("via-ssh", "2.4", DEFS, List.of());
    }

    private static Site directDeclared() {
        return fx.site("direct-http", "2.4", DEFS, List.of("cache"));
    }

    private static Site directUndeclared() {
        return fx.site("direct-http", "2.4", DEFS, List.of());
    }

    private static Instant at(int minutes) {
        return base().plusSeconds(60L * minutes);
    }

    private static IngestResult.Committed committed(IngestResult r) {
        return assertInstanceOf(IngestResult.Committed.class, r, String.valueOf(r));
    }

    // ---- I1: identity is SM-held ----------------------------------------------------------------------------------

    @Test
    void typeAndUnitComeFromTheDefinitionNeverTheProducer() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.size", at(0), 5, "T1"), sample("cache.requests", at(0), 10, "T1"))));
        List<Map<String, Object>> series = fx.superuser.queryForList("SELECT metric_name, metric_type, unit,"
                        + " source_discriminator, labels_fingerprint, instance_id FROM sm_metrics.series WHERE tenant_id = :t"
                        + " ORDER BY metric_name", new MapSqlParameterSource("t", site.tenant()));
        assertEquals(2, series.size());
        assertEquals("counter", series.get(0).get("metric_type"));
        assertEquals("count", series.get(0).get("unit"));
        assertEquals("gauge", series.get(1).get("metric_type"));
        assertEquals("entries", series.get(1).get("unit"));
        assertEquals("dp-poll", series.get(1).get("source_discriminator"));
        assertEquals(IngestService.EMPTY_LABELS_FINGERPRINT, series.get(1).get("labels_fingerprint"));
    }

    @Test
    void theInstanceComesFromThePlacementRowNeverTheBatch() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.size", at(0), 5, null))));
        UUID stored = fx.superuser.queryForObject("SELECT instance_id FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), UUID.class);
        assertEquals(site.instance(), stored);
    }

    @Test
    void identityPartsAreAllSmHeld() {
        Site owner = viaSsh();
        Site other = viaSsh();
        // The other tenant's batch NAMES the owner's placement.
        IngestResult result = service.ingest(IngestBatch.polled(other.tenant(), owner.placement(),
                List.of(sample("cache.size", at(0), 5, null))));
        assertEquals(new IngestResult.Rejected("placement.unknown"), result);
        assertEquals(0, fx.seriesCount(owner));
        assertEquals(0, fx.seriesCount(other));
        assertEquals(0, fx.points(owner, "cache.size").size(), "nothing was written under either tenant");
        // and an unknown placement is refused the same way
        assertEquals(new IngestResult.Rejected("placement.unknown"), service.ingest(IngestBatch.polled(owner.tenant(),
                UUID.randomUUID(), List.of(sample("cache.size", at(0), 5, null)))));
    }

    @Test
    void aRetiredPlacementIsRefused() {
        Site site = viaSsh();
        fx.superuser.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", site.placement()));
        assertEquals(new IngestResult.Rejected("placement.unknown"),
                service.ingest(batch(site, sample("cache.size", at(0), 5, null))));
    }

    @Test
    void aRetypeOrUnitChangeUnderANewPackageVersionMintsANewSeries() {
        // S-H: 2.4 declares cache.size gauge/entries.
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.size", at(0), 5, null))));
        long first = fx.superuser.queryForObject("SELECT series_id FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), Long.class);

        // 2.5 declares it as a counter; 2.6 as a gauge in bytes; 2.7 keeps type and unit.
        fx.declare(site.tenant(), site.solution(), "2.5", List.of(metric("cache.size", "counter", "entries", "cache", 180)), List.of());
        fx.declare(site.tenant(), site.solution(), "2.6", List.of(metric("cache.size", "gauge", "bytes", "cache", 180)), List.of());
        fx.declare(site.tenant(), site.solution(), "2.7", List.of(metric("cache.size", "gauge", "bytes", "cache", 180)), List.of());

        fx.moveToVersion(site, "2.5");
        committed(service.ingest(batch(site, sample("cache.size", at(5), 6, null))));
        fx.moveToVersion(site, "2.6");
        committed(service.ingest(batch(site, sample("cache.size", at(10), 7, null))));
        fx.moveToVersion(site, "2.7");
        committed(service.ingest(batch(site, sample("cache.size", at(15), 8, null))));

        List<Map<String, Object>> series = fx.superuser.queryForList("SELECT series_id, metric_type, unit FROM sm_metrics.series"
                + " WHERE tenant_id = :t ORDER BY series_id", new MapSqlParameterSource("t", site.tenant()));
        assertEquals(3, series.size(), "gauge/entries, counter/entries, gauge/bytes: a retype and a unit change each mint one");
        assertEquals(first, ((Number) series.get(0).get("series_id")).longValue());
        assertEquals(1, fx.points(site, "cache.size").stream().filter(p -> p.value() == 5).count(),
                "the 2.4 series kept its rows");
        // a plain version change with type and unit unchanged keeps the SAME series and adds a segment only
        long bytesSeries = ((Number) series.get(2).get("series_id")).longValue();
        List<String> segmentVersions = fx.superuser.queryForList("SELECT package_version FROM sm_metrics.series_segment"
                        + " WHERE tenant_id = :t AND series_id = :s ORDER BY effective_from",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("s", bytesSeries), String.class);
        assertEquals(List.of("2.6", "2.7"), segmentVersions, "the same series, a new segment for 2.7");
    }

    // ---- semantics (M-Q9 d') ------------------------------------------------------------------------------------

    @Test
    void sdRestartDuringAnOutageGivesTheRuledIncrease() {
        Site site = viaSsh(); // DOCKER detection; the source declares 180 s
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 10000, "T1"))));
        committed(service.ingest(batch(site, sample("cache.requests", at(5), 10050, "T1"))));
        committed(service.ingest(batch(site, sample("cache.requests", at(15), 300, "T2"))));
        List<Point> points = fx.points(site, "cache.requests");
        assertEquals(3, points.size());
        assertNull(points.get(0).increment(), "a series' first sample has no increment");
        assertEquals(50.0, points.get(1).increment());
        assertEquals(300.0, points.get(2).increment(), "counted from the restart, across the 600 s outage");
        assertEquals(50.0 + 300.0, points.stream().mapToDouble(p -> p.increment() == null ? 0 : p.increment()).sum());
        assertEquals(1, fx.resets(site, "cache.requests"));
        assertEquals("docker", fx.superuser.queryForObject("SELECT signal FROM sm_metrics.series_reset WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), String.class));
    }

    @Test
    void aDecreaseWithoutASignalChangeIsCountedNotReset() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 10000, "T1"))));
        committed(service.ingest(batch(site, sample("cache.requests", at(5), 10050, "T1"))));
        IngestResult.Committed result = committed(service.ingest(batch(site, sample("cache.requests", at(15), 300, "T1"))));
        List<Point> points = fx.points(site, "cache.requests");
        assertNull(points.get(2).increment());
        assertEquals(0, fx.resets(site, "cache.requests"), "never a reset");
        assertEquals(1, fx.counted(site, "decrease_without_restart"));
        assertEquals(1, result.dropped().get(CollectorReason.DECREASE_WITHOUT_RESTART));
    }

    @Test
    void withNoSignalADecreaseIsAnInferredReset() {
        Site site = directUndeclared();
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 10000, null))));
        committed(service.ingest(batch(site, sample("cache.requests", at(5), 10050, null))));
        committed(service.ingest(batch(site, sample("cache.requests", at(15), 300, null))));
        assertEquals(300.0, fx.points(site, "cache.requests").get(2).increment());
        assertEquals(1, fx.resets(site, "cache.requests"));
        assertEquals("inferred", fx.superuser.queryForObject("SELECT signal FROM sm_metrics.series_reset WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), String.class));
        assertEquals(0, fx.counted(site, "decrease_without_restart"));
    }

    @Test
    void aDeclaredSignalThatFailsToFetchNeverBecomesAnInferredReset() {
        for (Site site : List.of(directDeclared(), viaSsh())) { // DECLARED, then DOCKER (the inspect timing out)
            committed(service.ingest(batch(site, sample("cache.requests", at(0), 10000, "T1"))));
            IngestResult.Committed result = committed(service.ingest(batch(site, sample("cache.requests", at(1), 9000, null))));
            List<Point> points = fx.points(site, "cache.requests");
            assertNull(points.get(1).increment(), site.toString());
            assertEquals(1, fx.counted(site, "scrape_failed"));
            assertEquals(1, result.dropped().get(CollectorReason.SCRAPE_FAILED));
            assertEquals(0, fx.resets(site, "cache.requests"), "no series_reset row");
            // the series' previous token is still T1: the same token with a lower value is now a counted decrease
            committed(service.ingest(batch(site, sample("cache.requests", at(2), 8000, "T1"))));
            assertEquals(1, fx.counted(site, "decrease_without_restart"));
        }
    }

    // ---- W2-10: a timestamp no partition will ever hold must not stall its placement ---------------------------

    @Test
    void aSampleWhoseTimestampLiesOutsideEveryPartitionIsDroppedAndNeverHoldsTheQueue() {
        Site site = viaSsh();
        Instant farFuture = base().plus(java.time.Duration.ofDays(400));
        Instant epoch = Instant.EPOCH;
        long seriesBefore = fx.seriesCount(site);

        IngestResult.Committed future = committed(service.ingest(batch(site, sample("cache.size", at(0), 1, null),
                sample("cache.size", farFuture, 2, null))));
        IngestResult.Committed ancient = committed(service.ingest(batch(site, sample("cache.size", at(1), 3, null),
                sample("cache.size", epoch, 4, null))));

        assertEquals(1, future.dropped().get(CollectorReason.PARSE_FAILED), "beyond the skew: a corrupt or fast clock");
        assertEquals(1, ancient.dropped().get(CollectorReason.OUT_OF_ORDER), "before every attached partition");
        assertEquals(List.of(1.0, 3.0), fx.points(site, "cache.size").stream().map(Point::value).toList(),
                "the good samples of both batches were stored, the bad ones were not");
        assertFalse(service.runwayDegraded(), "a bad timestamp is not a runway problem");
        assertEquals(0, service.heldBatches(), "nothing is retried forever");
        assertEquals(0, fx.counted(site, "runway_degraded"));
        assertEquals(seriesBefore + 1, fx.seriesCount(site), "one series, minted by the good sample only");
        // the placement is not stuck behind them: the next batch commits at once
        committed(service.ingest(batch(site, sample("cache.size", at(2), 5, null))));
        assertEquals(3, fx.points(site, "cache.size").size());
    }

    /**
     * Fable MON-03: the case above never reaches the partition catalog's refusal for the epoch sample (the series' last
     * point already outranks it, so the earlier lastTs rule drops it). Here the out-of-partition sample is the FIRST sample of
     * a fresh series, so the INSERT really fails with no partition. PostgreSQL aborts the transaction on that failure; the
     * rest of the batch must still commit instead of failing with 25P02 and holding the placement's queue forever.
     */
    @Test
    void anOutOfPartitionFirstSampleOfAFreshSeriesDropsOnlyThatSample() {
        Site site = viaSsh();

        IngestResult.Committed result = committed(service.ingest(batch(site,
                sample("cache.requests", Instant.EPOCH, 7, null), sample("cache.size", at(0), 2, null))));

        assertEquals(1, result.dropped().get(CollectorReason.OUT_OF_ORDER), "no partition will ever hold the epoch");
        assertEquals(1, result.accepted());
        assertEquals(List.of(2.0), fx.points(site, "cache.size").stream().map(Point::value).toList(),
                "the good sample of the same batch was stored");
        assertEquals(List.of(), fx.points(site, "cache.requests"));
        assertEquals(0, service.heldBatches(), "the placement's queue is not held");
        assertFalse(service.runwayDegraded());
        committed(service.ingest(batch(site, sample("cache.size", at(1), 3, null))));
        assertEquals(2, fx.points(site, "cache.size").size(), "and the next batch commits at once");
    }

    @Test
    void theFutureSkewIsFiveMinutes() {
        Site site = viaSsh();
        Instant now = IngestFixture.base().plus(java.time.Duration.ofHours(1)); // the fixture clock starts here
        committed(service.ingest(batch(site, sample("cache.size", now.plus(java.time.Duration.ofMinutes(4)), 1, null))));
        IngestResult.Committed over = committed(service.ingest(batch(site,
                sample("cache.size", now.plus(java.time.Duration.ofMinutes(30)), 2, null))));

        assertEquals(1, over.dropped().get(CollectorReason.PARSE_FAILED));
        assertEquals(1, fx.points(site, "cache.size").size());
    }

    // ---- W2-11: the cap counts live series of the current version, not every series the instance ever had ------

    @Test
    void theCapDoesNotCountSeriesOfARetiredPlacementOrOfARetypedVersion() {
        IngestService capped = fx.service(new com.solace.sm.dp.metrics.ingest.IngestProperties(20, 3, 500, 30,
                Duration.ofMillis(250), Duration.ofSeconds(20), Duration.ofMillis(40), Duration.ofMinutes(5), Duration.ofMillis(250)));
        List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> gauges = List.of(
                metric("m.a", "gauge", "count", "cache", 30), metric("m.b", "gauge", "count", "cache", 30),
                metric("m.c", "gauge", "count", "cache", 30));
        Site site = fx.site("via-ssh", "2.4", gauges, List.of());
        assertEquals(3, committed(capped.ingest(batch(site, sample("m.a", at(0), 1, null),
                sample("m.b", at(0), 2, null), sample("m.c", at(0), 3, null)))).accepted());

        // 1. re-targeted: the retired placement's three series are history; the new placement may mint three of its own
        Site moved = fx.retarget(site);
        IngestResult.Committed afterRetarget = committed(capped.ingest(batch(moved, sample("m.a", at(1), 1, null),
                sample("m.b", at(1), 2, null), sample("m.c", at(1), 3, null))));
        assertEquals(3, afterRetarget.accepted(), "the old placement's series no longer count");
        assertNull(afterRetarget.dropped().get(CollectorReason.CAP_REJECTED));

        // 2. the cap still binds what is live: a fourth live series is refused
        List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> four = new ArrayList<>(gauges);
        four.add(metric("m.d", "gauge", "count", "cache", 30));
        fx.declare(moved.tenant(), moved.solution(), "2.5", four, List.of());
        fx.moveToVersion(moved, "2.5");
        IngestResult.Committed atCap = committed(capped.ingest(batch(moved, sample("m.a", at(2), 1, null),
                sample("m.b", at(2), 2, null), sample("m.c", at(2), 3, null), sample("m.d", at(2), 4, null))));
        assertEquals(3, atCap.accepted());
        assertEquals(1, atCap.dropped().get(CollectorReason.CAP_REJECTED), "three live series is the cap");

        // 3. a version that retypes every metric mints new series; the three old ones (latest segment under 2.5) are
        //    history once the instance moves to 2.6
        List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> retyped = List.of(
                metric("m.a", "counter", "count", "cache", 30), metric("m.b", "counter", "count", "cache", 30),
                metric("m.c", "counter", "count", "cache", 30));
        fx.declare(moved.tenant(), moved.solution(), "2.6", retyped, List.of());
        fx.moveToVersion(moved, "2.6");
        IngestResult.Committed afterUpgrade = committed(capped.ingest(batch(moved, sample("m.a", at(3), 10, "T1"),
                sample("m.b", at(3), 20, "T1"), sample("m.c", at(3), 30, "T1"))));
        assertEquals(3, afterUpgrade.accepted(), "the retyped metrics' new series are not locked out by the old ones");
    }

    @Test
    void aRestartSignalWithNoPortNeverYieldsDeclared() {
        // W2-05: the schema requires the port, but a row from an older copy may lack it. A signal that cannot be fetched
        // must not switch inference off, or every counter of the component would get no delta, forever.
        Site site = directDeclared();
        fx.superuser.update("UPDATE sm_data.metric_restart_signal SET source = source - 'port' WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()));
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 100, null))));
        IngestResult.Committed result = committed(service.ingest(batch(site, sample("cache.requests", at(1), 5, null))));

        assertEquals(1, fx.resets(site, "cache.requests"), "inferred, as with no signal at all");
        assertEquals(5.0, fx.points(site, "cache.requests").get(1).increment());
        assertEquals(0, fx.counted(site, "scrape_failed"));
        assertNull(result.dropped().get(CollectorReason.SCRAPE_FAILED));
    }

    @Test
    void theDetectionIsDerivedFromDpRowsNeverFromTheBatch() {
        // A batch has no field for it. The same samples (a drop, token unchanged) read differently by the SITE's rows:
        // via-ssh -> DOCKER and direct+declared -> DECLARED both COUNT it; direct with none -> INFERRED resets.
        for (Site site : List.of(viaSsh(), directDeclared())) {
            committed(service.ingest(batch(site, sample("cache.requests", at(0), 100, "T1"))));
            committed(service.ingest(batch(site, sample("cache.requests", at(1), 5, "T1"))));
            assertEquals(1, fx.counted(site, "decrease_without_restart"), site.toString());
            assertEquals(0, fx.resets(site, "cache.requests"));
        }
        Site inferred = directUndeclared();
        committed(service.ingest(batch(inferred, sample("cache.requests", at(0), 100, "T1"))));
        committed(service.ingest(batch(inferred, sample("cache.requests", at(1), 5, "T1"))));
        assertEquals(1, fx.resets(inferred, "cache.requests"));
    }

    @Test
    void oneOfTwoCounterUrlsFailingDuringTheRestartCycleStillSeesTheReset() {
        for (boolean cycleKDropsTheSample : List.of(false, true)) {
            Site site = viaSsh();
            // U1 = cache.requests, U2 = cache.errors, both under token T1 in cycle k-1.
            committed(service.ingest(batch(site, sample("cache.requests", at(0), 1000, "T1"),
                    sample("cache.errors", at(0), 500, "T1"))));
            // Cycle k: the token becomes T2. U2's fetch fails (no sample), or its sample is dropped parse_failed.
            if (cycleKDropsTheSample) {
                committed(service.ingest(batch(site, sample("cache.requests", at(1), 5, "T2"),
                        sample("cache.errors", at(1), Double.NaN, "T2"))));
                assertEquals(1, fx.counted(site, "parse_failed"));
            } else {
                committed(service.ingest(batch(site, sample("cache.requests", at(1), 5, "T2"))));
            }
            assertEquals("T2", fx.markerToken(site), "the placement marker records the latest observation");
            assertEquals(1, fx.resets(site, "cache.requests"));
            // Cycle k+1: U2 reads lower than its k-1 value, under T2. ITS previous token is T1: a RESET.
            committed(service.ingest(batch(site, sample("cache.requests", at(2), 9, "T2"),
                    sample("cache.errors", at(2), 20, "T2"))));
            assertEquals(1, fx.resets(site, "cache.errors"), "U2 saw the restart (cycleKDropsTheSample=" + cycleKDropsTheSample + ")");
            assertEquals(20.0, fx.points(site, "cache.errors").get(1).increment());
            assertEquals(0, fx.counted(site, "decrease_without_restart"), "never misread as a decrease without a restart");
        }
    }

    @Test
    void anSmRestartLosesTheBufferAsAGapNeverZeros() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 1000, "T1"))));
        // The database goes away: batches are held, not written.
        IngestService first = fx.service(IngestFixture.fast(20, 500));
        fx.outage(true);
        try {
            assertInstanceOf(IngestResult.RetryAfter.class,
                    first.ingest(batch(site, sample("cache.requests", at(5), 1050, "T1"))));
            assertInstanceOf(IngestResult.RetryAfter.class,
                    first.ingest(batch(site, sample("cache.requests", at(10), 1100, "T1"))));
            assertEquals(2, first.heldBatches());
        } finally {
            first.close();          // the process dies with the batches in memory
            fx.outage(false);
        }
        IngestService second = fx.service(IngestFixture.fast(20, 500));
        // The lost interval has no rows and no zeros; after the restart the next sample (900 s after 12:00 and well
        // over 2 x 180 s) has NO increment.
        committed(second.ingest(batch(site, sample("cache.requests", at(15), 1200, "T1"))));
        List<Point> points = fx.points(site, "cache.requests");
        assertEquals(2, points.size(), "the held batches were never written");
        assertNull(points.get(1).increment(), "a gap: no delta across it, never zero-filled");
        assertEquals(0.0, points.stream().filter(p -> p.increment() != null).mapToDouble(Point::increment).sum());
    }

    @Test
    void aNonFiniteNumberIsParseFailedNotRunwayDegraded() {
        Site site = viaSsh();
        for (double bad : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Double.parseDouble("1e400")}) {
            IngestResult result = service.ingest(batch(site, sample("cache.size", at(0), bad, null)));
            IngestResult.Committed c = committed(result);
            assertEquals(0, c.accepted());
            assertEquals(1, c.dropped().get(CollectorReason.PARSE_FAILED));
        }
        assertEquals(4, fx.counted(site, "parse_failed"));
        assertEquals(0, fx.counted(site, "runway_degraded"));
        assertTrue(!service.runwayDegraded());
    }

    @Test
    void noDeltaIsAttributedAcrossAGapWithoutAReset() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 100, "T1"))));
        committed(service.ingest(batch(site, sample("cache.requests", at(10), 160, "T1")))); // 600 s > 360 s
        committed(service.ingest(batch(site, sample("cache.requests", at(12), 170, "T1")))); // 120 s
        List<Point> points = fx.points(site, "cache.requests");
        assertNull(points.get(1).increment());
        assertEquals(10.0, points.get(2).increment());
        assertEquals(0, fx.resets(site, "cache.requests"));
    }

    @Test
    void aGapIsNeverZeroFilled() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.requests", at(0), 100, "T1"))));
        committed(service.ingest(batch(site, sample("cache.requests", at(30), 100, "T1"))));
        List<Point> points = fx.points(site, "cache.requests");
        assertEquals(2, points.size(), "no synthetic points in the gap");
        assertNull(points.get(1).increment(), "the value did not change but the delta is unknown, not zero");
    }

    @Test
    void aGaugeHasNoIncrement() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.size", at(0), 5, "T1"), sample("cache.size", at(1), 9, "T1"))));
        assertTrue(fx.points(site, "cache.size").stream().allMatch(p -> p.increment() == null));
    }

    // ---- caps, drops, duplicates ------------------------------------------------------------------------------------

    @Test
    void anUndeclaredNameIsDroppedAndCountedAndAHistogramIsNeither() {
        Site site = viaSsh();
        IngestResult.Committed c = committed(service.ingest(batch(site, sample("cache.mystery", at(0), 1, null),
                sample("cache.latency", at(0), 3, null), sample("cache.size", at(0), 5, null))));
        assertEquals(1, c.accepted());
        assertEquals(Map.of(CollectorReason.UNDECLARED_DROPPED, 1), c.dropped(), "the histogram is dropped, not counted");
        assertEquals(1, fx.counted(site, "undeclared_dropped"));
        assertEquals(1, fx.seriesCount(site));
    }

    @Test
    void theFiveHundredAndFirstSeriesIsCapRejected() {
        List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> many = new ArrayList<>();
        for (int i = 0; i < 502; i++) {
            many.add(metric("m.s" + i, "gauge", "count", "cache", 180));
        }
        Site site = fx.site("via-ssh", "2.4", many, List.of());
        List<com.solace.sm.dp.api.metrics.ingest.IngestSample> samples = new ArrayList<>();
        for (int i = 0; i < 502; i++) {
            samples.add(sample("m.s" + i, at(0), i, null));
        }
        IngestResult.Committed c = committed(service.ingest(IngestBatch.polled(site.tenant(), site.placement(), samples)));
        assertEquals(500, c.accepted());
        assertEquals(2, c.dropped().get(CollectorReason.CAP_REJECTED));
        assertEquals(500, fx.seriesCount(site));
        assertEquals(2, fx.counted(site, "cap_rejected"));
        // an existing series is still served at the cap
        assertEquals(1, committed(service.ingest(batch(site, sample("m.s7", at(1), 1, null)))).accepted());
    }

    @Test
    void anExactDuplicateIsIgnored() {
        Site site = viaSsh();
        IngestBatch b = batch(site, sample("cache.requests", at(0), 10, "T1"), sample("cache.requests", at(5), 20, "T1"));
        assertEquals(2, committed(service.ingest(b)).accepted());
        IngestResult.Committed again = committed(service.ingest(b));
        assertEquals(0, again.accepted());
        assertEquals(Map.of(), again.dropped(), "an exact replay is neither accepted nor counted");
        assertEquals(2, fx.points(site, "cache.requests").size());
        assertEquals(0, fx.counted(site, "out_of_order"));
    }

    @Test
    void aSampleOlderThanItsSeriesLastIsDroppedAndCounted() {
        Site site = viaSsh();
        committed(service.ingest(batch(site, sample("cache.requests", at(10), 100, "T1"))));
        IngestResult.Committed c = committed(service.ingest(batch(site, sample("cache.requests", at(5), 50, "T1"))));
        assertEquals(1, c.dropped().get(CollectorReason.OUT_OF_ORDER));
        assertEquals(1, fx.counted(site, "out_of_order"));
        assertEquals(1, fx.points(site, "cache.requests").size(), "never stored out of order");
    }

    @Test
    void gapMarkersFromDpPollAreRefused() {
        Site site = viaSsh();
        IngestBatch withMarker = new IngestBatch(site.tenant(), site.placement(), site.placement(),
                site.placement().toString(), SourceDiscriminator.DP_POLL, List.of(sample("cache.size", at(0), 1, null)),
                List.of(new GapMarker(at(0), at(5), "ssh unreachable")));
        assertEquals(new IngestResult.Rejected("gap-markers.refused"), service.ingest(withMarker));
        assertEquals(0, fx.seriesCount(site), "nothing written");
    }

    @Test
    void aDpPollBatchMustUseTheDpRemoteIdentityMapping() {
        Site site = viaSsh();
        IngestBatch wrong = new IngestBatch(site.tenant(), UUID.randomUUID(), site.placement(), "something-else",
                SourceDiscriminator.DP_POLL, List.of(sample("cache.size", at(0), 1, null)), List.of());
        assertEquals(new IngestResult.Rejected("identity.mismatch"), service.ingest(wrong));
    }

    /**
     * W3-14: the push path's one worker never uses the commit result and must keep draining its bounded receive queue, so a
     * BROKER_PUSH batch is handed over and answered RetryAfter after the short push wait while the database is slow. The
     * batch is held, not lost, and is written when the commit completes; a polled batch still waits its own commit out.
     */
    @Test
    void aPushBatchIsNotWaitedOutThroughASlowCommitAndIsStillWritten() throws Exception {
        Site site = viaSsh();
        IngestService svc = fx.service(IngestFixture.fast(20, 500));
        committed(svc.ingest(batch(site, sample("cache.size", at(0), 0, null))));
        fx.dataSource.slow(1500);
        try {
            long t0 = System.nanoTime();
            IngestResult pushed = svc.ingest(new com.solace.sm.dp.api.metrics.ingest.IngestBatch(site.tenant(), UUID.randomUUID(),
                    site.placement(), site.placement().toString(),
                    com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator.BROKER_PUSH,
                    List.of(sample("cache.size", at(1), 1, null)), List.of()));
            long pushedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            assertInstanceOf(IngestResult.RetryAfter.class, pushed, "handed over, held: " + pushed);
            assertTrue(pushedMs < 1000, "answered after the short push wait, not the commit: " + pushedMs + " ms");

            long t1 = System.nanoTime();
            committed(svc.ingest(batch(site, sample("cache.size", at(2), 2, null))));
            assertTrue(Duration.ofNanos(System.nanoTime() - t1).toMillis() >= 1000, "a polled batch waits for its own commit");
        } finally {
            fx.dataSource.slow(0);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && fx.points(site, "cache.size").size() < 3) {
            Thread.sleep(50);
        }
        assertEquals(List.of(0.0, 1.0, 2.0), fx.points(site, "cache.size").stream().map(Point::value).toList(),
                "the pushed batch was held and written");
    }

    @Test
    void aBatchOverTheSizeBoundIsRefusedWhole() {
        IngestService small = fx.service(new com.solace.sm.dp.metrics.ingest.IngestProperties(20, 500, 3, 30,
                Duration.ofMillis(250), Duration.ofSeconds(20), Duration.ofMillis(40), Duration.ofMinutes(5), Duration.ofMillis(250)));
        Site site = viaSsh();
        assertEquals(new IngestResult.Rejected("batch.too-large"), small.ingest(batch(site, sample("cache.size", at(0), 1, null),
                sample("cache.size", at(1), 1, null), sample("cache.size", at(2), 1, null), sample("cache.size", at(3), 1, null))));
    }

    @Test
    void pendingIsNeverReturnedInV1() {
        Site site = viaSsh();
        for (IngestResult r : List.of(service.ingest(batch(site, sample("cache.size", at(0), 1, null))),
                service.ingest(batch(site, sample("nope", at(0), 1, null))),
                service.ingest(IngestBatch.polled(site.tenant(), UUID.randomUUID(), List.of())))) {
            assertTrue(!(r instanceof IngestResult.Pending), String.valueOf(r));
        }
    }

    @Test
    void everyCollectorReasonIsStoredAndTheCheckListIsTheEnumNothingMore() {
        Site site = viaSsh();
        JdbcIngestCounterRepository counters = new JdbcIngestCounterRepository(fx.jdbc);
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        for (CollectorReason reason : CollectorReason.values()) {
            counters.add(site.tenant(), site.placement(), reason, today, 2);
            counters.add(site.tenant(), site.placement(), reason, today, 3);
            assertEquals(5, fx.counted(site, reason.wire()), "an upsert that ADDS: " + reason);
        }
        String check = fx.superuser.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'ingest_counter_reason'", new MapSqlParameterSource(), String.class);
        for (CollectorReason reason : CollectorReason.values()) {
            assertTrue(check.contains("'" + reason.wire() + "'"), reason + " in " + check);
        }
        assertEquals(CollectorReason.values().length, check.chars().filter(c -> c == '\'').count() / 2,
                "the CHECK names exactly the enum's reasons: " + check);
    }

    @Test
    void aKeeperFailureCountsRunwayDegradedForEveryPlacementThatIsIngesting() throws Exception {
        IngestService svc = fx.service(IngestFixture.fast(20, 500));
        Site one = viaSsh();
        Site two = viaSsh();
        Site silent = viaSsh();
        committed(svc.ingest(batch(one, sample("cache.size", at(0), 1, null))));
        committed(svc.ingest(batch(two, sample("cache.size", at(0), 1, null))));
        svc.runwayCouldNotBeExtended();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && (fx.counted(one, "runway_degraded") < 1 || fx.counted(two, "runway_degraded") < 1)) {
            Thread.sleep(25);
        }
        assertEquals(1, fx.counted(one, "runway_degraded"));
        assertEquals(1, fx.counted(two, "runway_degraded"));
        assertEquals(0, fx.counted(silent, "runway_degraded"), "a placement that never sent a batch is not counted");
        assertTrue(svc.runwayDegraded());
    }

    // ---- degradation (Review Focus 5) -------------------------------------------------------------------------------

    @Test
    void aDatabaseOutageBuffersThenDropsOldestAndCounts() throws Exception {
        Site site = viaSsh();
        IngestService svc = fx.service(IngestFixture.fast(3, 500));
        committed(svc.ingest(batch(site, sample("cache.size", at(0), 0, null))));
        fx.outage(true);
        try {
            for (int i = 1; i <= 5; i++) {
                IngestResult r = svc.ingest(batch(site, sample("cache.size", at(i), i, null)));
                assertInstanceOf(IngestResult.RetryAfter.class, r, "held, never an exception to the caller: " + i);
                assertTrue(((IngestResult.RetryAfter) r).millis() > 0);
            }
            assertEquals(3, svc.heldBatches(), "the bound holds: 5 offered, the 2 oldest dropped");
        } finally {
            fx.outage(false);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && fx.points(site, "cache.size").size() < 4) {
            Thread.sleep(50);
        }
        List<Point> points = fx.points(site, "cache.size");
        assertEquals(List.of(0.0, 3.0, 4.0, 5.0), points.stream().map(Point::value).toList(),
                "the OLDEST two held batches (1 and 2) were dropped; the newest three were written once the database returned");
        while (System.nanoTime() < deadline && fx.counted(site, "buffer_dropped") < 2) {
            Thread.sleep(50);
        }
        assertEquals(2, fx.counted(site, "buffer_dropped"), "counted in memory during the outage, written after it");
        assertEquals(0, svc.heldBatches());
    }

    @Test
    void anOutageNeverBlocksACallerForLong() {
        Site site = viaSsh();
        IngestService svc = fx.service(IngestFixture.fast(20, 500));
        committed(svc.ingest(batch(site, sample("cache.size", at(0), 0, null))));
        fx.outage(true);
        try {
            long t0 = System.nanoTime();
            for (int i = 1; i <= 20; i++) {
                assertInstanceOf(IngestResult.RetryAfter.class, svc.ingest(batch(site, sample("cache.size", at(i), i, null))));
            }
            long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            assertTrue(ms < 5000, "twenty calls during an outage answered in " + ms + " ms, not a connection timeout each");
        } finally {
            fx.outage(false);
        }
    }

    @Test
    void theBufferNeverExceedsItsBoundUnderLoad() {
        Site site = viaSsh();
        IngestService svc = fx.service(IngestFixture.fast(4, 500));
        committed(svc.ingest(batch(site, sample("cache.size", at(0), 0, null))));
        fx.outage(true);
        try {
            for (int i = 1; i <= 30; i++) {
                svc.ingest(batch(site, sample("cache.size", at(i), i, null)));
                assertTrue(svc.heldBatches() <= 4, "after offer " + i + ": " + svc.heldBatches());
            }
        } finally {
            fx.outage(false);
        }
    }
}
