package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.metrics.collect.CollectionPlanBuilder;
import com.solace.sm.dp.metrics.collect.CollectorCounts;
import com.solace.sm.dp.metrics.collect.CollectorProperties;
import com.solace.sm.dp.metrics.collect.MetricsBackgroundLoop;
import com.solace.sm.dp.metrics.collect.MetricsCollector;
import com.solace.sm.dp.metrics.collect.SourceScheduler;
import com.solace.sm.dp.metrics.collect.fetch.DirectHttpFetcher;
import com.solace.sm.dp.metrics.collect.fetch.FetchProperties;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.FetchResult;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher;
import com.solace.sm.dp.metrics.ingest.IngestService;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import com.solace.sm.dp.metrics.rollup.RollupRows;
import com.solace.sm.dp.metrics.rollup.RollupService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-4.3: the background loop end to end over a real {@code sm_data} and {@code sm_metrics}, a real ingest
 * service and the production plan builder and scheduler. Only the fetchers' wire is scripted (the SSH one has no server
 * in this module; the direct one is real in the re-target test, over a local JDK HTTP server).
 *
 * <p>Time is the test's: a mutable clock the loop, the scheduler and the fetchers all read. Every test carries a
 * {@code @Timeout}, so a collector that hangs fails the build rather than holding it.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class MetricsCollectorIT {

    private static IngestFixture ingest;
    private static CollectFixture fx;
    private static IngestService service;
    private static TenantEnumerationRepository tenantList;

    @BeforeAll
    static void database() {
        ingest = IngestFixture.create("p43_collector");
        fx = new CollectFixture(ingest);
        service = ingest.service(IngestFixture.fast(20, 500));
        tenantList = new TenantEnumerationRepository(ingest.jdbc);
    }

    @AfterAll
    static void close() {
        ingest.close();
    }

    // ---- scripted time and wire ------------------------------------------------------------------------------

    static final class MutableClock extends Clock {
        private volatile Instant now = IngestFixture.base();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    record Call(UUID tenant, UUID placement, UUID target, int hostPort, String path, Instant at) {
    }

    /** A fetcher whose answers the test scripts and whose every call it can read back. */
    static final class Scripted implements MetricFetcher {
        final MutableClock clock;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<RestartRequest> tokenReads = new CopyOnWriteArrayList<>();
        volatile Function<FetchRequest, FetchResult> answer;
        volatile Function<RestartRequest, RestartRead> token = r -> new RestartRead.NotDeclared();

        Scripted(MutableClock clock) {
            this.clock = clock;
            this.answer = r -> healthy(r, 0.5);
        }

        FetchResult healthy(FetchRequest r, double hitRate) {
            return ok(r, hitRate, 1000);
        }

        FetchResult ok(FetchRequest r, double hitRate, long hitCount) {
            String body = r.path().equals("/api/cache/size") ? "42" : "{\"hitRate\":" + hitRate + ",\"hitCount\":"
                    + hitCount + ",\"missCount\":3}";
            return new FetchResult.Ok(body.getBytes(StandardCharsets.UTF_8), clock.instant(), clock.instant());
        }

        @Override
        public FetchResult fetch(FetchRequest request) {
            calls.add(new Call(request.placement().tenant().value(), request.placement().placement().value(),
                    request.targetId(), request.hostPort(), request.path(), clock.instant()));
            return answer.apply(request);
        }

        @Override
        public RestartRead restartToken(RestartRequest request) {
            tokenReads.add(request);
            return token.apply(request);
        }

        List<Call> callsOf(UUID tenant) {
            return calls.stream().filter(c -> c.tenant().equals(tenant)).toList();
        }
    }

    static final class RecordingRollups implements RollupRows {
        final List<UUID> tenants = new CopyOnWriteArrayList<>();

        @Override
        public int rollUpHours(UUID tenantId, Instant from, Instant to, Instant computedAt) {
            tenants.add(tenantId);
            return 0;
        }

        @Override
        public int rollUpDays(UUID tenantId, Instant from, Instant to, Instant computedAt) {
            return 0;
        }
    }

    /** The production loop over the production plan builder, scheduler and collector. */
    static final class Rig implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final Scripted direct = new Scripted(clock);
        final Scripted ssh = new Scripted(clock);
        final Map<UUID, Long> restartObserved = new HashMap<>();
        final RecordingRollups rollups = new RecordingRollups();
        final SourceScheduler scheduler;
        final MetricsCollector collector;
        final MetricsBackgroundLoop loop;
        final ExecutorService pool;

        Rig(int cap, ExecutorService pool, Function<Clock, MetricFetcher> directFetcher) {
            this.pool = pool;
            this.scheduler = new SourceScheduler(cap, 0.1, 8, () -> 0.5);
            CollectionPlanBuilder builder = new CollectionPlanBuilder(fx.instances, fx.placements,
                    new MetricDefinitionCollectionRows(fx.definitions), (tenant, instance) -> {
                        Long g = restartObserved.get(instance.value());
                        return g == null ? OptionalLong.empty() : OptionalLong.of(g);
                    }, 30);
            CollectorCounts counts = new CollectorCounts(new JdbcIngestCounterRepository(ingest.jdbc), clock);
            this.collector = new MetricsCollector(scheduler, directFetcher == null ? direct : directFetcher.apply(clock), ssh,
                    service, counts, pool == null ? Runnable::run : pool, clock);
            this.loop = new MetricsBackgroundLoop(tenantList, builder, scheduler, collector,
                    new RollupService(rollups, RollupProperties.defaults()), CollectorProperties.defaults(), clock);
        }

        Rig() {
            this(4, null, null);
        }

        /** One tick of the loop, one dispatch of what is due, and the counters flushed: what 10 s of production does. */
        void cycle() {
            loop.tick();
            collector.dispatch();
            collector.flushCounts();
        }

        void advance(int seconds) {
            clock.advance(Duration.ofSeconds(seconds));
        }

        @Override
        public void close() {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    private final List<Rig> rigs = new ArrayList<>();

    private Rig rig() {
        Rig r = new Rig();
        rigs.add(r);
        return r;
    }

    @AfterEach
    void closeRigs() {
        rigs.forEach(Rig::close);
        rigs.clear();
    }

    private static long points(CollectFixture.Cache cache, String metric) {
        return ingest.points(cache.site(), metric).size();
    }

    private static long counted(CollectFixture.Cache cache, String reason) {
        return ingest.counted(cache.site(), reason);
    }

    // ---- no session, no apply: the idle tenant (OR-49) -----------------------------------------------------------

    @Test
    void anIdleTenantsInstancesAreCollectedWithNoSession() {
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);   // no apply request exists
        Rig rig = rig();

        rig.cycle();

        assertEquals(1, points(cache, "cache.hit_rate"), "collected with no user logged in and no apply in flight");
        assertEquals(0.5, ingest.points(cache.site(), "cache.hit_rate").get(0).value());
        assertEquals(1000.0, ingest.points(cache.site(), "cache.hit_count").get(0).value());
        assertEquals(42.0, ingest.points(cache.site(), "cache.size").get(0).value(), "the bare-number body (MF9)");
        assertEquals(2, rig.ssh.callsOf(cache.instance().tenant()).size(), "four metrics, two URLs, two fetches");
    }

    // ---- S-G: two placements on one target, each on its own host port ------------------------------------------

    @Test
    void twoPlacementsOnOneTargetAreEachScrapedOnTheirOwnHostPort() {
        for (String mode : List.of("direct-http", "via-ssh")) {
            Rig rig = rig();
            CollectFixture.Cache first = fx.cache(mode, "vm-1.corp.example", false);
            CollectFixture.Cache second = fx.anotherCacheOn(first, 28081);
            Scripted fetcher = mode.equals("via-ssh") ? rig.ssh : rig.direct;
            fetcher.answer = r -> fetcher.ok(r, r.hostPort() / 100000.0, r.hostPort());

            rig.cycle();

            assertEquals(0.18081, ingest.points(first.site(), "cache.hit_rate").get(0).value(), mode);
            assertEquals(0.28081, ingest.points(second.site(), "cache.hit_rate").get(0).value(), mode);
            assertEquals(18081.0, ingest.points(first.site(), "cache.hit_count").get(0).value(), mode);
            assertEquals(28081.0, ingest.points(second.site(), "cache.hit_count").get(0).value(), mode);
            for (Call call : fetcher.callsOf(first.instance().tenant())) {
                assertEquals(call.placement().equals(first.placement()) ? 18081 : 28081, call.hostPort(), mode);
            }
        }
    }

    // ---- M-Q2: the mode is the instance's, with no fallback ----------------------------------------------------

    @Test
    void aDirectModeInstanceIsNeverScrapedOverSshAndViceVersa() {
        Rig rig = rig();
        CollectFixture.Cache direct = fx.cache("direct-http", "vm-1.corp.example", false);
        CollectFixture.Cache viaSsh = fx.cache("via-ssh", "vm-2.corp.example", false);
        rig.direct.answer = r -> new FetchResult.Transport("connect.failed");       // direct is DOWN; ssh would answer
        rig.ssh.answer = r -> new FetchResult.Transport("ssh.unreachable");         // ssh is DOWN; direct would answer

        rig.cycle();

        UUID directTenant = direct.instance().tenant();
        UUID sshTenant = viaSsh.instance().tenant();
        assertTrue(rig.ssh.callsOf(directTenant).isEmpty(), "a direct instance never touches the SSH fetcher");
        assertTrue(rig.direct.callsOf(sshTenant).isEmpty(), "an SSH instance never touches the direct fetcher");
        assertEquals(0, points(direct, "cache.hit_rate"), "no fallback made it succeed");
        assertEquals(0, points(viaSsh, "cache.hit_rate"));
        assertTrue(counted(direct, "scrape_failed") > 0, "a gap and a count");
        assertTrue(counted(viaSsh, "scrape_failed") > 0);
    }

    // ---- OR-82: a re-target is polled at the new target's address, nothing carried forward ----------------------

    @Test
    void aReTargetedDirectInstanceIsPolledAtTheNewTargetsAddress() throws Exception {
        List<String> hostHeaders = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = (exchange.getRequestURI().getPath().equals("/api/cache/size") ? "42"
                    : "{\"hitRate\":0.5,\"hitCount\":9,\"missCount\":3}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            UUID tenant = fx.tenant();
            UUID solution = UUID.randomUUID();
            fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(), List.of());
            CollectFixture.Instance instance = fx.instance(tenant, solution, "2.4", "direct-http", "active");
            UUID oldTarget = fx.target(tenant, "localhost");
            UUID oldPlacement = fx.placement(instance, oldTarget, Map.of(8081, port));
            fx.activeList(instance, 0, fx.cacheMetricNames().toArray(String[]::new));
            Rig rig = new Rig(4, null, clock -> new DirectHttpFetcher(new JdbcTargetConnectionRepository(ingest.jdbc),
                    FetchProperties.defaults(), clock, new PermissiveTargetAddressPolicy()));
            rigs.add(rig);

            rig.cycle();
            assertEquals(2, hostHeaders.stream().filter(h -> h.equals("localhost:" + port)).count(),
                    "first the registered address of the OLD target: " + hostHeaders);

            fx.retire(tenant, oldPlacement);
            UUID newTarget = fx.target(tenant, "127.0.0.1");
            fx.placement(instance, newTarget, Map.of(8081, port));
            hostHeaders.clear();
            rig.advance(31);
            rig.cycle();

            assertFalse(hostHeaders.isEmpty(), "the new placement is read");
            assertTrue(hostHeaders.stream().allMatch(h -> h.equals("127.0.0.1:" + port)),
                    "every read now goes to the NEW target's registered address: " + hostHeaders);
        } finally {
            server.stop(0);
        }
    }

    // ---- BE-D32, S-K: what is never collected, and what stays --------------------------------------------------

    @Test
    void aPendingInstanceIsNeverCollected() {
        Rig rig = rig();
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(), List.of());
        CollectFixture.Instance pending = fx.instance(tenant, solution, "2.4", "via-ssh", "pending");
        fx.placement(pending, fx.target(tenant, "vm-1.corp.example"), Map.of(8081, 18081));
        fx.activeList(pending, 0, fx.cacheMetricNames().toArray(String[]::new));

        rig.cycle();

        assertTrue(rig.ssh.callsOf(tenant).isEmpty());
    }

    @Test
    void aRetiredPlacementAndAnAbandonedInstanceAreNeverCollectedAndTheirHistoryStaysQueryable() {
        Rig rig = rig();
        CollectFixture.Cache retired = fx.cache("via-ssh", "vm-1.corp.example", false);
        rig.cycle();
        assertEquals(1, points(retired, "cache.hit_rate"));
        int callsBefore = rig.ssh.callsOf(retired.instance().tenant()).size();

        fx.retire(retired.instance().tenant(), retired.placement());
        rig.advance(35);
        rig.cycle();

        assertEquals(callsBefore, rig.ssh.callsOf(retired.instance().tenant()).size(), "no read after retirement");
        assertEquals(1, points(retired, "cache.hit_rate"), "the history is still there and still queryable");
    }

    @Test
    void aDeactivatedMetricStopsButItsHistoryRemains() {
        Rig rig = rig();
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);
        rig.cycle();
        assertEquals(1, points(cache, "cache.size"));
        fx.activeList(cache.instance(), 2, "cache.hit_rate");
        rig.restartObserved.put(cache.instance().instance(), 2L);        // generation 2 reached restart-observed

        rig.advance(35);
        rig.cycle();

        assertEquals(2, points(cache, "cache.hit_rate"), "still collected");
        assertEquals(1, points(cache, "cache.size"), "deactivated: no new point");
        assertEquals(42.0, ingest.points(cache.site(), "cache.size").get(0).value(), "and its history stays");
    }

    @Test
    void histogramsAreNeverFetched() {
        Rig rig = rig();
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "1.0", List.of(
                CollectFixture.http("ok.gauge", "gauge", "svc", "/g", 8081, "/v", Map.of(), null),
                CollectFixture.http("bad.histogram", "histogram", "svc", "/histogram", 8081, "/v", Map.of(), null)),
                List.of());
        CollectFixture.Instance instance = fx.instance(tenant, solution, "1.0", "via-ssh", "active");
        fx.placement(instance, fx.target(tenant, "vm-1.corp.example"), Map.of(8081, 18081));
        fx.activeList(instance, 0, "ok.gauge", "bad.histogram");
        rig.ssh.answer = r -> new FetchResult.Ok("7".getBytes(StandardCharsets.UTF_8), rig.clock.instant(),
                rig.clock.instant());

        rig.cycle();

        assertEquals(List.of("/g"), rig.ssh.callsOf(tenant).stream().map(Call::path).toList());
    }

    // ---- deadline, cap, backoff -----------------------------------------------------------------------------------

    @Test
    void aMissedDeadlineIsAGapAndACounterNeverAZero() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Rig rig = new Rig(4, pool, null);
        rigs.add(rig);
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);
        CountDownLatch release = new CountDownLatch(1);
        rig.ssh.answer = r -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return rig.ssh.ok(r, 0.5, 1000);
        };

        rig.cycle();                                   // the run starts and hangs
        rig.advance(31);                               // its next due time passes with the run still going
        rig.cycle();
        assertEquals(1, counted(cache, "deadline_missed"), "a gap, counted once");
        assertEquals(0, points(cache, "cache.hit_rate"), "and not a zero: nothing was stored for the gap");

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (points(cache, "cache.hit_rate") < 1 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, points(cache, "cache.hit_rate"));
        assertTrue(ingest.points(cache.site(), "cache.hit_rate").stream().noneMatch(p -> p.value() == 0.0));
    }

    @Test
    void thePerTargetCapIsNeverExceeded() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        Rig rig = new Rig(4, pool, null);
        rigs.add(rig);
        CollectFixture.Cache first = fx.cache("via-ssh", "vm-1.corp.example", false);
        List<CollectFixture.Cache> twelve = new ArrayList<>(List.of(first));
        for (int i = 1; i < 12; i++) {
            twelve.add(fx.anotherCacheOn(first, 20000 + i));
        }
        Semaphore gate = new Semaphore(0);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        AtomicInteger entered = new AtomicInteger();
        rig.ssh.answer = r -> {
            if (!r.targetId().equals(first.target())) {
                return rig.ssh.ok(r, 0.5, 1000);        // another test's tenant in the same database: not under test
            }
            int now = inside.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            entered.incrementAndGet();
            try {
                gate.tryAcquire(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inside.decrementAndGet();
            }
            return rig.ssh.ok(r, 0.5, 1000);
        };

        rig.cycle();
        Thread.sleep(400);                              // long enough for a fifth run to show itself, if it were allowed
        assertEquals(4, entered.get(), "twelve placements are due; only four may be in flight on one target");
        assertEquals(4, max.get());

        gate.release(10_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (twelve.stream().anyMatch(c -> points(c, "cache.hit_rate") == 0) && System.nanoTime() < deadline) {
            rig.collector.dispatch();
            Thread.sleep(30);
        }
        assertTrue(twelve.stream().allMatch(c -> points(c, "cache.hit_rate") == 1), "all twelve were read in the end");
        assertEquals(4, max.get(), "and never more than four at once");
    }

    @Test
    void aFailingHostBacksOffAndRecoversOnSuccess() {
        Rig rig = rig();
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);
        UUID tenant = cache.instance().tenant();
        AtomicInteger failuresLeft = new AtomicInteger(2);
        rig.ssh.answer = r -> failuresLeft.get() > 0 ? new FetchResult.Transport("connect.failed")
                : rig.ssh.ok(r, 0.5, 1000);
        List<Integer> startedAt = new ArrayList<>();
        int previousCalls = 0;
        for (int t = 0; t <= 150; t += 10) {
            if (t > 0) {
                rig.advance(10);
            }
            if (t == 60) {
                failuresLeft.set(0);                     // the host is back before the third read (the 4x wait is over at 90)
            }
            rig.cycle();
            // fetches are two per read: the first fetch of a read marks its time
            int calls = rig.ssh.callsOf(tenant).size();
            if (calls > previousCalls) {
                startedAt.add(t);
            }
            previousCalls = calls;
        }

        // t=0 fails (rest 1x -> 30), t=30 fails (rest 2x -> 90), t=90 succeeds and the plain cadence returns (120)
        assertEquals(List.of(0, 30, 90, 120, 150), startedAt);
        assertTrue(counted(cache, "scrape_failed") >= 2);
        assertTrue(points(cache, "cache.hit_rate") >= 1, "recovered");
    }

    // ---- S-F: a host whose MI is in a restart loop yields gaps and scrape_failed, never zeros ----------------

    @Test
    void s_fAHostWhoseMiIsInARestartLoopYieldsGapsAndScrapeFailedNotZeros() {
        Rig rig = rig();
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);
        AtomicInteger phase = new AtomicInteger();
        String[] token = {"a".repeat(64) + " 2026-10-06T10:00:00Z"};
        rig.ssh.token = r -> phase.get() == 1 ? new MetricFetcher.RestartRead.Failed("docker.inspect.failed")
                : new MetricFetcher.RestartRead.Token(token[0]);
        rig.ssh.answer = r -> phase.get() == 1 ? new FetchResult.Transport("connect.failed")
                : rig.ssh.ok(r, 0.5, phase.get() == 0 ? 100 : 5);

        rig.cycle();                                   // t=0: the Cache answers, hit_count 100
        phase.set(1);
        rig.advance(30);
        rig.cycle();                                   // t=30: restarting, nothing answers
        rig.advance(30);
        rig.cycle();                                   // t=60: still restarting (backed off one step: not even read)
        rig.advance(60);
        phase.set(2);
        token[0] = "b".repeat(64) + " 2026-10-06T10:02:00Z";
        rig.cycle();                                   // t=120: back, a new container start time, hit_count 5

        List<Double> values = ingest.points(cache.site(), "cache.hit_count").stream().map(p -> p.value()).toList();
        assertEquals(List.of(100.0, 5.0), values, "two samples: the gap holds nothing, not a zero");
        assertTrue(counted(cache, "scrape_failed") >= 1);
        assertEquals(1, ingest.resets(cache.site(), "cache.hit_count"), "exactly one reset, from the container's start time");
        assertEquals(5.0, ingest.points(cache.site(), "cache.hit_count").get(1).increment(),
                "counted from the restart: the new value, never a negative delta");
    }

    // ---- OQ-6 (a): the rollups ride the same enumeration ---------------------------------------------------------

    @Test
    void rollupRunsPerTenantFromTheSameEnumeration() {
        Rig rig = rig();
        CollectFixture.Cache a = fx.cache("via-ssh", "vm-1.corp.example", false);
        CollectFixture.Cache b = fx.cache("direct-http", "vm-2.corp.example", false);
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(), List.of());
        CollectFixture.Instance idle = fx.instance(tenant, solution, "2.4", "via-ssh", "pending");   // no active instance

        rig.loop.tick();

        assertTrue(rig.rollups.tenants.contains(a.instance().tenant()));
        assertTrue(rig.rollups.tenants.contains(b.instance().tenant()));
        assertFalse(rig.rollups.tenants.contains(idle.tenant()), "a tenant with no active instance is not in the list");
        int rolled = rig.rollups.tenants.size();
        rig.loop.tick();
        assertEquals(rolled, rig.rollups.tenants.size(), "the rollup keeps its own cadence, not the tick's");
    }

    // ---- OR-49 / OR-72: the one list -----------------------------------------------------------------------------

    @Test
    void theLoopReadsNoTenantOutsideTheRuledLists() throws IOException {
        for (Class<?> type : List.of(MetricsBackgroundLoop.class, MetricsCollector.class, CollectionPlanBuilder.class,
                SourceScheduler.class)) {
            String constants = constantPoolOf(type);
            assertFalse(constants.contains("tenantsWithNonTerminalWork"), type + ": OR-44's list is the apply worker's");
            assertFalse(constants.contains("DISTINCT"), type + ": no SQL here");
            assertFalse(constants.contains("tenant_id"), type + ": no SQL here");
        }
        assertTrue(constantPoolOf(MetricsBackgroundLoop.class).contains("tenantsWithActiveInstances"),
                "the loop reads OR-49's list by name, once per tick");
        for (Class<?> type : List.of(MetricsCollector.class, CollectionPlanBuilder.class, SourceScheduler.class)) {
            assertFalse(constantPoolOf(type).contains("tenantsWithActiveInstances"),
                    type + " is handed one tenant at a time and never enumerates");
        }
    }

    private static String constantPoolOf(Class<?> type) throws IOException {
        try (java.io.InputStream in = type.getClassLoader()
                .getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
