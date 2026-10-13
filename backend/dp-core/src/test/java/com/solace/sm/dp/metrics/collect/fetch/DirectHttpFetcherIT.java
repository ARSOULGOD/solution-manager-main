package com.solace.sm.dp.metrics.collect.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRead;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.2a (M-Q2, OR-82, MF10): the direct fetcher against a local stub server (the JDK's own
 * {@code HttpServer}, no dependency). The address is the target's REGISTERED host, read fresh each round; the port is
 * the placement's mapped host port.
 */
class DirectHttpFetcherIT {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID TARGET = UUID.randomUUID();
    private static final PlacementRef PLACEMENT = new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()),
            new PlacementId(UUID.randomUUID()));

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private volatile Handler handler;

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        handler = ex -> reply(ex, 200, "7");
        server.createContext("/", ex -> {
            requested.add(ex.getRequestURI().toString());
            handler.handle(ex);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    // ---- the registered-address double -------------------------------------------------------------------

    private static final class Rows implements TargetConnectionRows {
        volatile String host = "127.0.0.1";
        volatile Instant retiredAt;
        volatile boolean absent;
        final AtomicInteger finds = new AtomicInteger();

        @Override
        public void insert(TargetConnectionRecord row) {
            throw new AssertionError();
        }

        @Override
        public Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId) {
            finds.incrementAndGet();
            if (absent || !tenantId.equals(TENANT.value()) || !targetId.equals(TARGET)) {
                return Optional.empty();
            }
            return Optional.of(new TargetConnectionRecord(tenantId, targetId, host, 22, "demo",
                    "SHA256:AAAAAAAAAAAAAAAAAAAAAA", UUID.randomUUID(), "/srv/config", "cache", UUID.randomUUID(),
                    Instant.EPOCH, retiredAt));
        }

        @Override
        public void addHostKeys(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
            throw new AssertionError();
        }

        @Override
        public Set<String> hostKeys(UUID tenantId, UUID targetId) {
            throw new AssertionError();
        }

        @Override
        public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef) {
            throw new AssertionError();
        }
    }

    private static final class SteppingClock extends Clock {
        private final List<Instant> instants;
        private int next;

        SteppingClock(Instant... instants) {
            this.instants = List.of(instants);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return instants.get(Math.min(next++, instants.size() - 1));
        }
    }

    private final Rows rows = new Rows();

    private DirectHttpFetcher fetcher() {
        return new DirectHttpFetcher(rows, FetchProperties.defaults(), Clock.systemUTC(), new PermissiveTargetAddressPolicy());
    }

    private FetchRequest request(String path, Map<String, String> query) {
        return new FetchRequest(PLACEMENT, TARGET, port(), path, query);
    }

    // ---- tests ----------------------------------------------------------------------------------------------

    @Test
    void theDirectFetcherUsesTheTargetsRegisteredAddressAndTheMappedPort() {
        handler = ex -> reply(ex, 200, "{\"hitCount\":3}");

        FetchResult result = fetcher().fetch(request("/api/cache/stats", Map.of()));

        FetchResult.Ok ok = assertInstanceOf(FetchResult.Ok.class, result);
        assertEquals("{\"hitCount\":3}", new String(ok.body(), StandardCharsets.UTF_8));
        assertEquals(List.of("/api/cache/stats"), requested);
        assertEquals(1, rows.finds.get(), "the registered address is read from the row");
    }

    @Test
    void theAddressIsReadFreshOnEveryRoundSoAReTargetFollowsTheNewRecord() {
        DirectHttpFetcher fetcher = fetcher();
        fetcher.fetch(request("/x", Map.of()));
        rows.host = "192.0.2.1"; // TEST-NET-1: a re-target to a host nothing answers on; nothing was carried forward
        FetchProperties fast = new FetchProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), 65536);
        FetchResult second = new DirectHttpFetcher(rows, fast, Clock.systemUTC(), new PermissiveTargetAddressPolicy()).fetch(request("/x", Map.of()));

        assertEquals(1, requested.size(), "the second round did not go to the first round's host");
        assertInstanceOf(FetchResult.Transport.class, second);
        assertEquals(2, rows.finds.get());
    }

    @Test
    void aTargetWithNoLiveRecordIsATransportFailureNeverAFallbackAndNothingIsSent() {
        rows.absent = true;
        FetchResult absent = fetcher().fetch(request("/x", Map.of()));
        rows.absent = false;
        rows.retiredAt = Instant.EPOCH;
        FetchResult retired = fetcher().fetch(request("/x", Map.of()));

        assertEquals(new FetchResult.Transport("target.unavailable"), absent);
        assertEquals(new FetchResult.Transport("target.unavailable"), retired);
        assertTrue(requested.isEmpty());
    }

    @Test
    void aHostOutsideTheRegisteredGrammarNeverBuildsAUri() {
        for (String host : new String[] {"a@evil", "h/x", "h:80", "[::1]", "", "-x", "x-", "a b", "a\nb", "a?b",
                "a#b", "é", "evil.com@" + "127.0.0.1", "127.0.0.1:" + port(), "a".repeat(255)}) {
            rows.host = host;
            FetchResult result = fetcher().fetch(request("/x", Map.of()));
            assertEquals(new FetchResult.Transport("target.host.refused"), result, host);
        }
        assertTrue(requested.isEmpty(), "no request was ever sent: " + requested);
    }

    @Test
    void aDownAnswerIsItsHttpStatusNotATransportFailure() {
        handler = ex -> reply(ex, 503, "{\"status\":\"DOWN\"}");

        assertEquals(new FetchResult.Http(503), fetcher().fetch(request("/actuator/health", Map.of())));
    }

    @Test
    void aRedirectIsNeverFollowedItIsItsStatus() {
        handler = ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            reply(ex, 302, "");
        };

        assertEquals(new FetchResult.Http(302), fetcher().fetch(request("/x", Map.of())));
        assertEquals(1, requested.size());
    }

    @Test
    void theQueryIsBuiltFromTheGrammarCheckedParamsInSortedOrder() {
        fetcher().fetch(request("/api/cache/size", Map.of("filterByNamespace", "false", "a", "1")));

        assertEquals(List.of("/api/cache/size?a=1&filterByNamespace=false"), requested);
    }

    @Test
    void aPathOrParamOutsideTheGrammarIsRefusedBeforeAnyRequest() {
        for (String path : new String[] {"", "x", "/a;b", "/a b", "/a?x=1", "/$(id)", "/a'b", "/é"}) {
            assertEquals(new FetchResult.Transport("fetch.refused"), fetcher().fetch(request(path, Map.of())), path);
        }
        for (String bad : new String[] {"a&b", "a b", "$(id)", "a%20b", "a;b", "", "a=b", "a\nb"}) {
            assertEquals(new FetchResult.Transport("fetch.refused"),
                    fetcher().fetch(request("/x", Map.of("k", bad))), bad);
            assertEquals(new FetchResult.Transport("fetch.refused"),
                    fetcher().fetch(request("/x", Map.of(bad, "v"))), bad);
        }
        assertEquals(new FetchResult.Transport("fetch.refused"),
                fetcher().fetch(new FetchRequest(PLACEMENT, TARGET, 0, "/x", Map.of())));
        assertTrue(requested.isEmpty(), "nothing reached the server: " + requested);
    }

    @Test
    void theDirectFetcherBoundsTheBodyAndTimesOut() throws Exception {
        // (1) a body larger than the cap is a transport failure, never a truncated body
        handler = ex -> reply(ex, 200, "x".repeat(200));
        FetchProperties small = new FetchProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 100);
        assertEquals(new FetchResult.Transport("body.too.large"),
                new DirectHttpFetcher(rows, small, Clock.systemUTC(), new PermissiveTargetAddressPolicy()).fetch(request("/x", Map.of())));

        // (2) a server that never answers: the request timeout bounds it
        FetchProperties oneSecond = new FetchProperties(Duration.ofSeconds(2), Duration.ofSeconds(1), 65536);
        handler = ex -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        };
        long start = System.nanoTime();
        FetchResult silent = new DirectHttpFetcher(rows, oneSecond, Clock.systemUTC(), new PermissiveTargetAddressPolicy()).fetch(request("/x", Map.of()));
        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(new FetchResult.Transport("timeout"), silent);
        assertTrue(tookMillis < 3000, "bounded by the 1 s request timeout, took " + tookMillis + " ms");

        // (3) MF10: HttpRequest.timeout covers the RESPONSE; a server that sends headers and then drips the body is
        // bounded by the fetcher's own deadline on the body read
        handler = ex -> {
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            try {
                for (int i = 0; i < 40; i++) {
                    out.write('1');
                    out.flush();
                    Thread.sleep(250);
                }
            } catch (IOException | InterruptedException e) {
                // the client gave up: expected
            }
            ex.close();
        };
        start = System.nanoTime();
        FetchResult dripping = new DirectHttpFetcher(rows, oneSecond, Clock.systemUTC(), new PermissiveTargetAddressPolicy()).fetch(request("/x", Map.of()));
        tookMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(new FetchResult.Transport("timeout"), dripping);
        assertTrue(tookMillis < 3000, "a dripping body is bounded too, took " + tookMillis + " ms");
    }

    @Test
    void aClosedPortIsATransportFailureWithAFixedClassifier() throws Exception {
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        FetchResult result = fetcher().fetch(new FetchRequest(PLACEMENT, TARGET, closed, "/x", Map.of()));

        assertInstanceOf(FetchResult.Transport.class, result);
        assertTrue(Set.of("connect.failed", "timeout").contains(((FetchResult.Transport) result).classifier()),
                result.toString());
    }

    @Test
    void theTimestampIsTheMidpointAndReceivedAtIsKept() {
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = t0.plusSeconds(2);
        DirectHttpFetcher fetcher = new DirectHttpFetcher(rows, FetchProperties.defaults(), new SteppingClock(t0, t1), new PermissiveTargetAddressPolicy());

        FetchResult.Ok ok = assertInstanceOf(FetchResult.Ok.class, fetcher.fetch(request("/x", Map.of())));

        assertEquals(t0.plusSeconds(1), ok.ts(), "the midpoint of sent and received");
        assertEquals(t1, ok.receivedAt(), "the response time is kept");
    }

    @Test
    void metricsSharingAUrlShareOneFetch() {
        List<FetchRequest> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(request("/api/cache/stats", Map.of()));
        }
        four.add(request("/api/cache/size", Map.of("filterByNamespace", "false")));

        Map<FetchRequest, FetchResult> answers = fetcher().fetchAll(four);

        assertEquals(2, answers.size());
        assertEquals(2, requested.size(), "one exec per distinct URL: " + requested);
    }

    // ---- the restart token (OQ-1 (a)) ------------------------------------------------------------------------

    private RestartRequest restart(RestartRequest.Signal signal) {
        return new RestartRequest(PLACEMENT, TARGET, signal);
    }

    @Test
    void theDirectRestartTokenIsTheDeclaredRestartSignalsValue() {
        handler = ex -> reply(ex, 200, "{\"name\":\"process.start.time\",\"baseUnit\":\"seconds\","
                + "\"measurements\":[{\"statistic\":\"VALUE\",\"value\":1.790665479471E9}]}");

        RestartRead read = fetcher().restartToken(restart(new RestartRequest.Signal(port(),
                "/actuator/metrics/process.start.time", "/measurements/0/value")));

        assertEquals(new RestartRead.Token("1.790665479471E9"), read);
        assertEquals(List.of("/actuator/metrics/process.start.time"), requested);
    }

    @Test
    void noDeclaredSignalMeansNotDeclaredAndNothingIsFetched() {
        assertEquals(new RestartRead.NotDeclared(), fetcher().restartToken(restart(null)));
        assertTrue(requested.isEmpty());
        assertEquals(0, rows.finds.get());
    }

    @Test
    void aDeclaredSignalThatFailedToFetchIsFailedNeverNotDeclared() {
        handler = ex -> reply(ex, 503, "down");
        assertEquals(new RestartRead.Failed("http.status"), fetcher().restartToken(
                restart(new RestartRequest.Signal(port(), "/s", "/v"))));

        handler = ex -> reply(ex, 200, "{\"v\":{}}");
        assertEquals(new RestartRead.Failed("parse_failed"), fetcher().restartToken(
                restart(new RestartRequest.Signal(port(), "/s", "/v"))));

        rows.absent = true;
        assertEquals(new RestartRead.Failed("target.unavailable"), fetcher().restartToken(
                restart(new RestartRequest.Signal(port(), "/s", "/v"))));

        assertEquals(new RestartRead.Failed("fetch.refused"), fetcher().restartToken(
                restart(new RestartRequest.Signal(port(), "/s;id", "/v"))));
    }
}
