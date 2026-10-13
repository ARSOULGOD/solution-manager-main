package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.metrics.collect.JsonPointerExtractor;
import com.solace.sm.dp.metrics.collect.fetch.DirectHttpFetcher;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.FetchResult;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher;
import com.solace.sm.dp.metrics.collect.fetch.SshMetricFetcher;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 011 P11-4.4: the live background collection, end to end, on a host (S-G, S-F). It declares one or two Caches
 * through the real REST declare (a package whose metrics are read on the host port the declare maps), lets the collector
 * run in the production boot with nobody driving it, and then reads the database: each instance's {@code cache.hit_count}
 * series holds samples, in order, and none is a stored zero for a gap.
 *
 * <h2>It READS by default</h2>
 * Everything before {@link #restartingOneCacheIsExactlyOneResetOfItsOwnKind} writes nothing to the host: the declare
 * registers rows in this run's throwaway database, the collector issues {@code GET}s (directly, or as a {@code curl} to the
 * host's own loopback over SSH) and {@code docker inspect}. No configuration file is written, no container is touched.
 *
 * <h2>The disruptive part is OPT-IN</h2>
 * The plan's Step 2 also asks for a restart (exactly one {@code series_reset}) and a stop (a gap and {@code scrape_failed},
 * never zeros). Those scenarios RESTART one Cache container and STOP one, and start it again, so they run only when
 * {@code sm.walkthrough.metrics.disruptive=yes}. Without it each is skipped with that reason recorded. A container this run
 * stopped is started again in {@link #tearDown()} whatever else failed, and the evidence says so.
 *
 * <p>The scenarios are this class's and are run against a local stub by {@code MetricsCollectionRehearsalIT} (always, so
 * the assertions have been executed before they ever meet a host); the live IT supplies the host.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.MINUTES)
abstract class AbstractMetricsCollectionWalkthrough {

    /** One declared Cache of the run. */
    record Run(MetricsLiveTarget.Cache cache, UUID instance, UUID placement) {
        String mode() {
            return cache.mode();
        }
    }

    EvidenceLog evidence;
    E2eStack stack;
    E2eStack.App app;
    MetricsLiveTarget live;
    UUID solutionId;
    final List<Run> runs = new ArrayList<>();
    /** What this run stopped and must start again. */
    private final List<Run> stopped = new ArrayList<>();

    abstract E2eStack createStack(EvidenceLog evidence);

    abstract MetricsLiveTarget liveTarget();

    abstract String evidencePrefix();

    /** What this run adds to the application's own settings (a faster cadence for the local rehearsal). */
    Map<String, String> bootOverrides() {
        return Map.of();
    }

    /**
     * What the application boots with: this run's overrides, plus the health switch an SSH Cache needs. The test
     * database turns the health poller OFF in every IT, and the SSH session pool the collector's SSH fetcher shares
     * exists only with it, so a boot that reads a {@code via-ssh} Cache with it off counts every document
     * {@code scrape_failed} and reads nothing (the live run of 2026-10-06: zero samples, {@code scrape_failed} +2 per
     * run). A direct-http run never needs the pool and keeps the default.
     */
    static Map<String, String> bootProperties(MetricsLiveTarget live, Map<String, String> overrides) {
        Map<String, String> boot = new java.util.HashMap<>(overrides);
        if (live.caches().stream().anyMatch(c -> c.mode().equals("via-ssh"))) {
            boot.put("sm.dp.health.enabled", "true");
        }
        return boot;
    }

    /** Whether the data plane can reach a direct-http Cache at all; false records the skip and declares nothing. */
    abstract boolean directReachable(MetricsLiveTarget.Cache cache);

    /** RESTARTS the Cache's container. Only ever called when {@code disruptive} is on. */
    abstract void restart(Run run);

    /** STOPS the Cache's container. Only ever called when {@code disruptive} is on. */
    abstract void stop(Run run);

    /** Starts a container this run stopped. Only ever called for one it stopped. */
    abstract void start(Run run);

    /** The cadence the collector reads at, for the bounds below. */
    abstract Duration interval();

    /**
     * How long the stopped Cache is left down before the gap is judged: four intervals plus a margin for the collector's
     * per-host backoff on a host that stops answering (1x, 2x, 4x...), which is what makes the live wait long.
     */
    Duration downWindow() {
        return interval().multipliedBy(4).plusSeconds(30);
    }

    /** The reset signal a restart of this Cache is detected by: {@code docker} over SSH, {@code declared} directly. */
    String expectedResetSignal(Run run) {
        return run.mode().equals("via-ssh") ? "docker" : "declared";
    }

    @BeforeAll
    void bringUp() throws Exception {
        evidence = new EvidenceLog(evidencePrefix());
        live = liveTarget();
        stack = createStack(evidence);
        evidence.record("Setup", "mode: " + stack.mode + "; target: " + stack.target.describeForEvidence());
        evidence.record("Setup", "caches: " + live.caches().stream().map(c -> c.container() + " (" + c.mode()
                + ", host port " + c.hostPort() + ")").toList() + "; window " + live.window() + "; disruptive: "
                + live.disruptive() + (live.disruptive() ? " (restarts one container and stops one; starts it again)"
                        : " (READ-ONLY: no container is touched, no file is written)"));
        evidence.record("Setup", "SM mesh events go to a LOCAL broker started by this run, database: postgres:14 "
                + "(Testcontainers); the collector reads the Cache(s) only through GETs");
        app = stack.boot(bootProperties(live, bootOverrides()));
        if (live.caches().stream().anyMatch(c -> c.mode().equals("via-ssh"))) {
            assertTrue(app.context.getBeanProvider(SshMetricFetcher.class).getIfAvailable() != null,
                    "a via-ssh Cache is declared, so the boot must carry the SSH fetcher (sm.dp.health.enabled=true)");
        }
        UUID targetId = stack.registerTarget();
        evidence.record("Setup", "target " + targetId + " registered through the operator commands (envelope root "
                + stack.target.allowedConfigRoot() + ", container prefix " + stack.target.allowedContainerPrefix() + ")");
    }

    @AfterAll
    void tearDown() {
        try {
            for (Run run : new ArrayList<>(stopped)) {
                try {
                    start(run);
                    stopped.remove(run);
                    evidence.record("Restore", "container " + run.cache().container() + " was started again");
                } catch (RuntimeException e) {
                    evidence.record("Restore", "MANUAL: this run stopped container " + run.cache().container()
                            + " and could not start it again (" + e.getClass().getSimpleName() + "): run `docker start "
                            + run.cache().container() + "` on the host");
                }
            }
        } finally {
            try {
                if (evidence != null && stack != null) {
                    evidence.writeTo(Path.of(System.getProperty("sm.walkthrough.evidence.dir",
                            "target/walkthrough-evidence")), evidencePrefix());
                }
            } finally {
                if (stack != null) {
                    stack.close();
                }
            }
        }
        if (!stopped.isEmpty()) {
            throw new AssertionError("THIS RUN LEFT A CONTAINER STOPPED: " + stopped.stream()
                    .map(r -> r.cache().container()).toList() + " (the evidence file has the instruction)");
        }
    }

    // ============================================================================================ the scenarios

    @Test
    @Order(1)
    void theAdminLoadsThePackage() throws Exception {
        JsonNode loaded = app.loadPackageAsAdmin();
        solutionId = UUID.fromString(loaded.get("solutionId").asText());
        evidence.record("1. Package", "the cache package is loaded (solution " + solutionId + ")");
    }

    @Test
    @Order(2)
    void eachCacheIsDeclaredWithItsOwnMetricsHostPortAndTheDataPlaneRecordsIt() throws Exception {
        int index = 0;
        for (MetricsLiveTarget.Cache cache : live.caches()) {
            index++;
            if (cache.mode().equals("direct-http") && !directReachable(cache)) {
                evidence.skip("declare " + cache.container() + " in direct-http mode", "the data plane cannot reach the "
                        + "target's registered address at host port " + cache.hostPort() + " from this machine; the "
                        + "target is never edited to make it pass (plan 011 P11-4.4 Step 1, OR-82)");
                continue;
            }
            ObjectNode body = app.declareBody(solutionId, "e2e-metrics-" + index, cache.configDirectory());
            ObjectNode placement = (ObjectNode) body.get("placement");
            placement.put("containerName", cache.container());
            placement.put("healthPort", cache.hostPort());
            ObjectNode ports = placement.putObject("metricsPorts");
            cache.metricsPorts().forEach(ports::put);
            ((ObjectNode) body.get("acquisition")).put("mode", cache.mode());
            HttpResponse<String> response = app.declareRaw(DeclareFixtures.OPERATOR_ONE, body);
            assertEquals(201, response.statusCode(), "declare " + cache.container() + ": " + response.body());
            JsonNode declared = DeclareFixtures.MAPPER.readTree(response.body());
            Run run = new Run(cache, UUID.fromString(declared.get("instanceId").asText()),
                    UUID.fromString(declared.get("placements").get(0).get("placementId").asText()));
            runs.add(run);

            Map<Integer, Integer> endpoints = new java.util.TreeMap<>();
            stack.db.ownerJdbc.query("SELECT source_port, host_port FROM sm_data.placement_metrics_endpoint"
                    + " WHERE tenant_id = :t AND placement_id = :p", Map.of("t", stack.organization(), "p", run.placement()),
                    rs -> {
                        endpoints.put(rs.getInt(1), rs.getInt(2));
                    });
            assertEquals(Map.of(8081, cache.hostPort()), endpoints, "the host port the declare mapped is the one recorded");
            long generationZero = stack.count("SELECT count(*) FROM sm_data.generation_active_metrics WHERE tenant_id = :t"
                    + " AND instance_id = :i AND generation = 0", Map.of("t", stack.organization(), "i", run.instance()));
            assertEquals(1, generationZero, "generation 0's active list was written with the declare");
            evidence.record("2. Declare", cache.container() + " (" + cache.mode() + "): instance " + run.instance()
                    + ", placement " + run.placement() + ", metrics host port 8081 -> " + cache.hostPort()
                    + " (201; endpoint row and generation-0 active list present)");
        }
        assertFalse(runs.isEmpty(), "at least one Cache is declared, or there is nothing to collect");
    }

    @Test
    @Order(3)
    void theCollectorRunsAloneForTheWindowAndEveryInstanceGathersItsOwnSamplesInOrderWithNoZeroForAGap()
            throws Exception {
        Instant start = Instant.now();
        Instant end = start.plus(live.window());
        evidence.record("3. Collection", "started " + start + "; letting the production loop collect until " + end
                + " (" + live.window() + ", interval " + interval() + "); nothing drives it and nobody is logged in");
        while (Instant.now().isBefore(end)) {
            Thread.sleep(Math.min(60_000, Math.max(1_000, Duration.between(Instant.now(), end).toMillis())));
            StringBuilder line = new StringBuilder("at " + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + ":");
            for (Run run : runs) {
                line.append(' ').append(run.cache().container()).append(" hit_count samples ").append(points(run).size())
                        .append(", scrape_failed ").append(counter(run, "scrape_failed"));
            }
            evidence.record("3. Collection", line.toString());
        }
        // WHY a document failed, when one did: the collector logs classifiers only (never a host, path or message), so the
        // distinct lines go into the evidence verbatim, ahead of the assertions below that may fail on them
        List<String> collectorWarnings = app.logs.messages(ch.qos.logback.classic.Level.WARN).stream()
                .filter(m -> m.startsWith("metrics collection:")).distinct().limit(10).toList();
        evidence.record("3. Collection", collectorWarnings.isEmpty() ? "the collector logged no read failure"
                : "the collector's distinct failure lines (classifiers only): " + collectorWarnings);
        long expected = Math.max(3, live.window().toSeconds() / interval().toSeconds() / 2);
        for (Run run : runs) {
            List<Point> points = points(run);
            assertTrue(points.size() >= expected, run.cache().container() + ": " + points.size()
                    + " cache.hit_count samples in " + live.window() + " at " + interval() + " (at least " + expected
                    + " expected)");
            for (int i = 1; i < points.size(); i++) {
                assertTrue(points.get(i).ts().isAfter(points.get(i - 1).ts()), "samples are in strict time order");
                assertTrue(points.get(i).value() >= points.get(i - 1).value(), "a counter never decreases without a "
                        + "restart, and no restart has happened: " + run.cache().container());
            }
            assertEquals(0, resets(run), run.cache().container() + ": no restart has happened, so no series_reset");
            evidence.record("3. Collection", run.cache().container() + ": " + points.size() + " samples from "
                    + points.get(0).ts() + " to " + points.get(points.size() - 1).ts() + "; first value "
                    + points.get(0).value() + ", last " + points.get(points.size() - 1).value() + "; scrape_failed "
                    + counter(run, "scrape_failed") + ", parse_failed " + counter(run, "parse_failed")
                    + ", deadline_missed " + counter(run, "deadline_missed") + ", series_reset rows 0");
        }
    }

    @Test
    @Order(4)
    void eachSeriesIsTheOnePortsOwnNotAnotherInstancesProvedByAnIndependentRead() throws Exception {
        boolean trafficRan = false;
        if (!live.traffic()) {
            evidence.skip("send miss traffic to the second Cache so attribution can be told by value", "this run sends "
                    + "no requests of its own: it does so only with sm.walkthrough.metrics.traffic=yes "
                    + "(SM_E2E_ATTRIBUTION_TRAFFIC=yes in the script)");
        } else if (runs.size() < 2) {
            evidence.skip("send miss traffic to the second Cache so attribution can be told by value", "fewer than two "
                    + "Caches are declared (the traffic goes to the second one only)");
        } else {
            trafficRan = sendMissTraffic(runs.get(1));
        }
        Map<String, Map<String, Double>> lastByContainer = new java.util.LinkedHashMap<>();
        for (Run run : runs) {
            Map<String, Double> fresh = freshCounters(run);
            Map<String, Double> lasts = new java.util.LinkedHashMap<>();
            for (String counter : AttributionCheck.COUNTERS.keySet()) {
                List<Point> points = points(run, counter);
                assertFalse(points.isEmpty(), run.cache().container() + " has " + counter + " samples");
                double last = points.get(points.size() - 1).value();
                // after the traffic the miss counter is quiet: the sample taken since is exactly the Cache's own number
                double slack = trafficRan && counter.equals("cache.miss_count") ? 0
                        : AttributionCheck.slack(points.stream().map(Point::value).toList());
                Optional<String> violation = AttributionCheck.violation(run.cache().container(), counter, last,
                        fresh.get(counter), slack);
                assertTrue(violation.isEmpty(), violation.orElse(""));
                lasts.put(counter, last);
                evidence.record("4. Attribution", run.cache().container() + ": " + counter + " last stored " + last
                        + ", a fresh independent GET of its own host port " + run.cache().hostPort() + " now reads "
                        + fresh.get(counter));
            }
            lastByContainer.put(run.cache().container(), lasts);
        }
        if (runs.size() > 1) {
            List<String> differing = AttributionCheck.differing(lastByContainer);
            evidence.record("4. Attribution", differing.isEmpty()
                    ? "attribution not distinguishable by value: every declared counter is EQUAL across the Caches, so "
                            + "equality cannot tell a mix-up from none; it rests on the per-port requests the unit tests "
                            + "prove" + (live.traffic() ? "" : " (this run generated no traffic to tell them apart)")
                    : "the counters that DIFFER between the Caches, so a series holding another port's numbers would show: "
                            + differing);
            if (trafficRan) {
                assertTrue(differing.contains("cache.miss_count"), "after " + live.trafficCount() + " misses on "
                        + runs.get(1).cache().container() + " alone, cache.miss_count must differ between the Caches: "
                        + lastByContainer);
            }
        }
        for (Run run : runs) {
            assertEquals(1, stack.count("SELECT count(DISTINCT series_id) FROM sm_metrics.series WHERE tenant_id = :t"
                    + " AND placement_id = :p AND metric_name = 'cache.hit_count'", Map.of("t", stack.organization(),
                    "p", run.placement())), "one cache.hit_count series per placement");
        }
    }

    /**
     * {@code live.trafficCount()} GETs of {@code /api/cache?key=sm-attribution-<n>} to the SECOND Cache's own host port, through
     * the production fetcher for its mode (so over SSH the command is {@link HostMetricProbe}'s closed-grammar curl). An absent
     * key answers 404 and only counts a miss; nothing is written. Then two collection intervals, and a stored sample newer than
     * the traffic, so the stored miss counter has had the chance to follow.
     */
    private boolean sendMissTraffic(Run target) throws InterruptedException {
        MetricFetcher fetcher = fetcherFor(target);
        PlacementRef ref = stack.placementRef(target.instance(), target.placement());
        for (int n = 1; n <= live.trafficCount(); n++) {
            FetchResult result = fetcher.fetch(new FetchRequest(ref, stack.registeredTargetId(), target.cache().hostPort(),
                    AttributionCheck.TRAFFIC_PATH, AttributionCheck.trafficParams(n)));
            assertTrue(result instanceof FetchResult.Http http && http.status() == 404, "request " + n + " to "
                    + target.cache().container() + " (an absent key) answered 404, a miss: " + result);
        }
        Instant sentAll = Instant.now();
        evidence.record("4. Attribution", "sent " + live.trafficCount() + " x GET " + AttributionCheck.TRAFFIC_PATH
                + "?key=sm-attribution-<n> (absent keys: 404, a miss, nothing written) to " + target.cache().container()
                + " only, host port " + target.cache().hostPort() + "; waiting two collection intervals ("
                + interval().multipliedBy(2) + ")");
        Thread.sleep(interval().multipliedBy(2).toMillis());
        for (Run run : runs) {
            awaitTrue(() -> points(run, "cache.miss_count").stream().anyMatch(p -> p.ts().isAfter(sentAll)),
                    interval().multipliedBy(4).plusSeconds(60), "a cache.miss_count sample of " + run.cache().container()
                            + " after the traffic");
        }
        return true;
    }

    @Test
    @Order(5)
    void restartingOneCacheIsExactlyOneResetOfItsOwnKind() throws Exception {
        if (!live.disruptive()) {
            evidence.skip("restart one Cache and see exactly one series_reset", "this run is READ-ONLY: it restarts a "
                    + "container only with sm.walkthrough.metrics.disruptive=yes (SM_E2E_CONFIRM_DISRUPT=yes in the script)");
            return;
        }
        Run run = runs.get(0);
        int before = points(run).size();
        Instant at = Instant.now();
        evidence.record("5. Restart", "restarting container " + run.cache().container() + " at " + at);
        restart(run);
        awaitTrue(() -> resets(run) >= 1, interval().multipliedBy(8).plusSeconds(120),
                "a series_reset for the restarted Cache");
        Thread.sleep(interval().multipliedBy(2).toMillis());
        assertEquals(1, resets(run), "exactly one series_reset, not one per metric or per cycle");
        assertEquals(expectedResetSignal(run), resetSignal(run), "the reset's evidence is the container's start time");
        List<Point> after = points(run);
        assertTrue(after.size() > before, "collection went on across the restart");
        Point first = after.stream().filter(p -> p.ts().isAfter(at)).findFirst().orElseThrow();
        evidence.record("5. Restart", "series_reset rows: 1, signal " + resetSignal(run) + "; samples " + before + " -> "
                + after.size() + "; the first sample after the restart: value " + first.value() + ", increment "
                + first.increment() + " (counted from the restart, never a negative delta)");
    }

    @Test
    @Order(6)
    void stoppingOneCacheIsAGapAndScrapeFailedNeverZeros() throws Exception {
        if (!live.disruptive()) {
            evidence.skip("stop one Cache and see scrape_failed and a gap, never zeros (S-F)", "this run is READ-ONLY: it "
                    + "stops a container only with sm.walkthrough.metrics.disruptive=yes (SM_E2E_CONFIRM_DISRUPT=yes in "
                    + "the script)");
            return;
        }
        Run run = runs.get(runs.size() - 1);
        long failedBefore = counter(run, "scrape_failed");
        int resetsBefore = resets(run);
        Instant stoppedAt = Instant.now();
        evidence.record("6. Stop", "stopping container " + run.cache().container() + " at " + stoppedAt);
        stop(run);
        stopped.add(run);
        Duration gap = downWindow();
        Thread.sleep(gap.toMillis());
        long failedDuring = counter(run, "scrape_failed");
        assertTrue(failedDuring > failedBefore, "scrape_failed counted while the Cache was down (" + failedBefore + " -> "
                + failedDuring + ")");
        Instant window = Instant.now();
        List<Point> during = points(run).stream().filter(p -> p.ts().isAfter(stoppedAt.plus(interval()))
                && p.ts().isBefore(window)).toList();
        assertTrue(during.isEmpty(), "no sample is stored for the time the Cache was down: a gap, never zeros ("
                + during.size() + " found)");
        assertTrue(points(run).stream().noneMatch(p -> p.ts().isAfter(stoppedAt.plus(interval())) && p.value() == 0.0
                && p.ts().isBefore(window)), "and no zero in particular");
        evidence.record("6. Stop", "scrape_failed " + failedBefore + " -> " + failedDuring + " over " + gap + "; samples "
                + "stored in the down window (after one interval of grace): " + during.size());

        start(run);
        stopped.remove(run);
        Instant startedAt = Instant.now();
        evidence.record("6. Stop", "container " + run.cache().container() + " started again at " + startedAt);
        awaitTrue(() -> points(run).stream().anyMatch(p -> p.ts().isAfter(startedAt)),
                interval().multipliedBy(10).plusSeconds(180), "a sample after the Cache came back");
        evidence.record("6. Stop", "collection resumed: series_reset rows " + resetsBefore + " -> " + resets(run)
                + " (a stop then start changes the container's start time, so a further reset is expected)");
    }

    @Test
    @Order(7)
    void theSummary() {
        for (Run run : runs) {
            evidence.record("7. Summary", run.cache().container() + " (" + run.mode() + "): hit_count samples "
                    + points(run).size() + ", series_reset " + resets(run) + ", scrape_failed "
                    + counter(run, "scrape_failed") + ", parse_failed " + counter(run, "parse_failed")
                    + ", deadline_missed " + counter(run, "deadline_missed"));
        }
    }

    // ===================================================================================================== reads

    record Point(Instant ts, double value, Double increment) {
    }

    List<Point> points(Run run) {
        return points(run, "cache.hit_count");
    }

    List<Point> points(Run run, String metric) {
        return stack.db.ownerJdbc.query("SELECT r.ts, r.value, r.increment FROM sm_metrics.metrics_raw r"
                        + " JOIN sm_metrics.series s ON s.tenant_id = r.tenant_id AND s.series_id = r.series_id"
                        + " WHERE s.tenant_id = :t AND s.placement_id = :p AND s.metric_name = :m"
                        + " ORDER BY r.ts", Map.of("t", stack.organization(), "p", run.placement(), "m", metric), (rs, i) -> {
                    double inc = rs.getDouble("increment");
                    Double increment = rs.wasNull() ? null : inc;
                    return new Point(rs.getObject("ts", java.time.OffsetDateTime.class).toInstant(),
                            rs.getDouble("value"), increment);
                });
    }

    int resets(Run run) {
        Integer n = stack.db.ownerJdbc.queryForObject("SELECT count(*)::int FROM sm_metrics.series_reset x"
                + " JOIN sm_metrics.series s ON s.tenant_id = x.tenant_id AND s.series_id = x.series_id"
                + " WHERE s.tenant_id = :t AND s.placement_id = :p AND s.metric_name = 'cache.hit_count'",
                Map.of("t", stack.organization(), "p", run.placement()), Integer.class);
        return n == null ? 0 : n;
    }

    String resetSignal(Run run) {
        return stack.db.ownerJdbc.queryForObject("SELECT x.signal FROM sm_metrics.series_reset x"
                + " JOIN sm_metrics.series s ON s.tenant_id = x.tenant_id AND s.series_id = x.series_id"
                + " WHERE s.tenant_id = :t AND s.placement_id = :p AND s.metric_name = 'cache.hit_count'"
                + " ORDER BY x.at DESC LIMIT 1", Map.of("t", stack.organization(), "p", run.placement()), String.class);
    }

    long counter(Run run, String reason) {
        return stack.count("SELECT coalesce(sum(count), 0) FROM sm_metrics.ingest_counter WHERE tenant_id = :t"
                + " AND placement_id = :p AND reason = :r", Map.of("t", stack.organization(), "p", run.placement(),
                "r", reason));
    }

    MetricFetcher fetcherFor(Run run) {
        return run.mode().equals("via-ssh") ? app.bean(SshMetricFetcher.class) : app.bean(DirectHttpFetcher.class);
    }

    /**
     * A fresh, read-only fetch of the Cache's own {@code /api/cache/stats} through the production fetcher for its mode, at
     * THIS Cache's host port, with every declared counter read from the one body.
     */
    Map<String, Double> freshCounters(Run run) {
        PlacementRef ref = stack.placementRef(run.instance(), run.placement());
        FetchResult result = fetcherFor(run).fetch(new FetchRequest(ref, stack.registeredTargetId(), run.cache().hostPort(),
                "/api/cache/stats", Map.of()));
        assertTrue(result instanceof FetchResult.Ok, "a fresh read of " + run.cache().container() + " answered: " + result);
        FetchResult.Ok ok = (FetchResult.Ok) result;
        Map<String, Double> counters = new java.util.LinkedHashMap<>();
        AttributionCheck.COUNTERS.forEach((metric, pointer) -> {
            JsonPointerExtractor.Extraction value = JsonPointerExtractor.number(ok.body(), pointer);
            assertTrue(value instanceof JsonPointerExtractor.Extraction.Value, "the answer carries " + pointer);
            counters.put(metric, ((JsonPointerExtractor.Extraction.Value) value).value());
        });
        return counters;
    }

    static void awaitTrue(BooleanSupplier condition, Duration timeout, String what) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError("never saw " + what + " within " + timeout);
    }
}
