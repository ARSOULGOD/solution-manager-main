package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-8.1 Step 3's harness against a STUB of the query route (the real route is plan 011 P11-7.2's): what it sends,
 * what it times, what it leaves out of a timing, and what it does with a refusal. The stub proves the harness; it proves
 * nothing about the server.
 */
class GateRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile IntSupplier status = () -> 200;
    private volatile long latencyMillis = 0;
    private volatile String okBody = null;

    @BeforeEach
    void stub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/console/v1/query/range", exchange -> {
            requests.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.add(Map.of("cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")), "origin",
                    String.valueOf(exchange.getRequestHeaders().getFirst("Origin")), "csrf",
                    String.valueOf(exchange.getRequestHeaders().getFirst("X-SM-CSRF"))));
            try {
                Thread.sleep(latencyMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int code = status.getAsInt();
            String json = code == 200 && okBody != null ? okBody : code == 200
                    ? "{\"resolution\":{\"requested\":\"auto\",\"served\":\"hourly\",\"bucketSeconds\":3600},"
                    + "\"series\":[{\"points\":[[\"2026-10-01T00:00:00Z\",1,1,1],[\"2026-10-01T01:00:00Z\",2,1,1]]},{\"points\":[[\"2026-10-01T00:00:00Z\",3,1,1]]}]}"
                    : "{\"code\":\"X\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private GateRunner runner(int runs, long spacing) {
        GateConfig config = new GateConfig(runs, spacing, 30, List.of(1), "cache-engine", "bench.gauge_1", "bench-operator");
        return new GateRunner("http://127.0.0.1:" + server.getAddress().getPort(), "SESSION-TOKEN", "CSRF-TOKEN", "https://bench.test", config);
    }

    private static List<UUID> ids(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(UUID.randomUUID());
        }
        return out;
    }

    @Test
    void theRequestIsTheContractsShapeWithTheSessionTheCsrfTokenAndTheOrigin() throws Exception {
        List<UUID> two = ids(2);
        runner(1, 0).run(new GateRunner.GateCase("30d-forced-raw", Duration.ofDays(30), "raw", "raw"), two);
        JsonNode body = MAPPER.readTree(bodies.get(0));
        assertEquals(1, body.get("queryVersion").asInt());
        assertEquals("bench.gauge_1", body.get("metric").asText());
        assertEquals("cache-engine", body.at("/selector/entityType").asText());
        assertEquals(2, body.at("/selector/instanceIds").size());
        assertEquals(two.get(0).toString(), body.at("/selector/instanceIds/0").asText());
        assertTrue(body.at("/selector/labels").isObject() && body.at("/selector/labels").isEmpty(), "the label allowlist is empty in v1");
        assertEquals("raw", body.get("resolution").asText());
        assertEquals("instance", body.get("seriesBy").asText());
        assertEquals("avg", body.at("/aggregation/acrossInstances").asText(), "many instances are aggregated, or the series cap refuses");
        assertEquals(java.time.Instant.parse(body.at("/range/to").asText()).minus(Duration.ofDays(30)),
                java.time.Instant.parse(body.at("/range/from").asText()));
        assertFalse(body.has("groupBy"), "a groupBy is 400 in v1");
        Map<String, String> h = headers.get(0);
        assertEquals("__Host-SM_SESSION=SESSION-TOKEN", h.get("cookie"));
        assertEquals("CSRF-TOKEN", h.get("csrf"));
        assertEquals("https://bench.test", h.get("origin"));
    }

    @Test
    void oneInstanceIsNotAggregated() throws Exception {
        runner(1, 0).run(new GateRunner.GateCase("24h-raw", Duration.ofHours(24), "auto", "raw"), ids(1));
        assertEquals("none", MAPPER.readTree(bodies.get(0)).at("/aggregation/acrossInstances").asText());
    }

    @Test
    void oneColdRequestThenTheTimedRunsAndTheTimingIsTheRequestsOwn() throws Exception {
        latencyMillis = 40;
        GateRunner.CaseResult r = runner(5, 0).run(new GateRunner.GateCase("7d-raw", Duration.ofDays(7), "auto", "raw"), ids(3));
        assertEquals(6, requests.get(), "one cold request and five timed");
        assertEquals(5, r.millis().size());
        assertTrue(r.coldMillis() >= 40, "the cold one is reported apart: " + r.coldMillis());
        assertTrue(GateRunner.p50(r) >= 40 && GateRunner.p50(r) < 2000, "p50 is the request's latency: " + GateRunner.p50(r));
        assertEquals(Map.of(200, 6), r.statuses());
        assertEquals(3, r.instances());
        assertEquals(2, r.series());
        assertEquals(3, r.points());
        assertEquals("hourly", r.served());
    }

    @Test
    void theSpacingBetweenRequestsIsNeverPartOfATiming() throws Exception {
        latencyMillis = 0;
        GateRunner.CaseResult r = runner(3, 250).run(new GateRunner.GateCase("24h-raw", Duration.ofHours(24), "auto", "raw"), ids(1));
        assertTrue(GateRunner.p95(r) < 200, "a 250 ms pause between requests is not in a 0 ms request's timing: " + GateRunner.p95(r));
    }

    @Test
    void aRateLimitedRequestIsWaitedOutAndCountedNotTimedAsAResult() throws Exception {
        AtomicInteger n = new AtomicInteger();
        status = () -> n.incrementAndGet() <= 2 ? 429 : 200;
        GateRunner.CaseResult r = runner(2, 0).run(new GateRunner.GateCase("24h-raw", Duration.ofHours(24), "auto", "raw"), ids(1));
        assertEquals(2, r.rateLimited());
        assertEquals(2, r.millis().size(), "the sample is still the two timed 200s");
        assertEquals(Map.of(200, 3), r.statuses(), "a 429 that was retried is not a result");
    }

    @Test
    void aRefusalIsRecordedAsItsStatusAndNoTimingIsInventedForIt() throws Exception {
        status = () -> 503;
        GateRunner.CaseResult r = runner(2, 0).run(new GateRunner.GateCase("30d-hourly", Duration.ofDays(30), "auto", "hourly"), ids(1));
        assertFalse(r.answered());
        assertEquals(Map.of(503, 3), r.statuses());
        assertTrue(r.millis().isEmpty());
        assertEquals(-1, r.coldMillis());
    }

    @Test
    void anAnswerWithNoSeriesOrNoPointsIsAnEmptyQueryAndNotATimingToReport() throws Exception {
        GateRunner.GateCase shape = new GateRunner.GateCase("30d-hourly", Duration.ofDays(30), "auto", "hourly");
        okBody = "{\"resolution\":{\"requested\":\"auto\",\"served\":\"hourly\",\"bucketSeconds\":3600},\"series\":[]}";
        GateRunner.CaseResult noSeries = runner(2, 0).run(shape, ids(1));
        assertTrue(noSeries.answered(), "the route answered 200");
        assertEquals(GateRunner.Evidence.EMPTY, noSeries.evidence(),
                "W4-11: sub-millisecond timings of an empty answer are not REQ-007 evidence");
        okBody = "{\"resolution\":{\"served\":\"hourly\"},\"series\":[{\"points\":[]}]}";
        assertEquals(GateRunner.Evidence.EMPTY, runner(2, 0).run(shape, ids(1)).evidence(), "a series with no points is empty too");
        okBody = null;
        assertEquals(GateRunner.Evidence.TIMED, runner(2, 0).run(shape, ids(1)).evidence());
        status = () -> 503;
        assertEquals(GateRunner.Evidence.REFUSED, runner(2, 0).run(shape, ids(1)).evidence());
    }

    @Test
    void aRouteThatIsNotThereIsSaidToBeNotThere() throws Exception {
        status = () -> 404;
        GateRunner.CaseResult r = runner(1, 0).run(new GateRunner.GateCase("24h-raw", Duration.ofHours(24), "auto", "raw"), ids(1));
        assertTrue(r.allNotFound());
    }

    @Test
    void theFourPlanShapesAreThePlansShapes() {
        List<GateRunner.GateCase> cases = GateRunner.planCases();
        assertEquals(List.of("24h-raw", "7d-raw", "30d-hourly", "30d-forced-raw"), cases.stream().map(GateRunner.GateCase::name).toList());
        assertEquals(List.of(Duration.ofHours(24), Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(30)),
                cases.stream().map(GateRunner.GateCase::range).toList());
        assertEquals(List.of("auto", "auto", "auto", "raw"), cases.stream().map(GateRunner.GateCase::resolution).toList());
        assertEquals(List.of("raw", "raw", "hourly", "raw"), cases.stream().map(GateRunner.GateCase::expectedServed).toList());
    }

    @Test
    void theDefaultsAreThePlans() {
        GateConfig c = GateConfig.from(name -> null);
        assertEquals(List.of(1, 10, 200), c.instanceCounts());
        assertEquals(20, c.runs());
        assertTrue(c.spacingMillis() > 100, "10 requests per second per session is the limit: spaced wider than 100 ms");
        GateConfig custom = GateConfig.from(Map.of("sm.bench.gate.instance-counts", "2, 3", "sm.bench.gate.runs", "4")::get);
        assertEquals(List.of(2, 3), custom.instanceCounts());
        assertEquals(4, custom.runs());
    }
}
