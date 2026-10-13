package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;

/**
 * Plan 011 P11-4.4's scenarios run EVERY build against two local stub Caches (JDK {@code HttpServer}, no dependency) read in
 * {@code direct-http} mode at the loopback address the registered target names, so the assertions in
 * {@link AbstractMetricsCollectionWalkthrough} (declare with metrics ports, the production loop collecting with nobody
 * driving it, per-port attribution, one reset per restart, a gap and a count for a stop) have been executed before they
 * ever meet a host. The stubs answer what the Cache answers: {@code /api/cache/stats}, {@code /api/cache/size},
 * {@code /actuator/metrics/process.start.time} and {@code /actuator/health}.
 *
 * <p>What a rehearsal cannot show, and the live run alone does: the SSH transport, {@code docker inspect}'s start time as
 * the reset signal ({@code docker}, not {@code declared}), and a real container restart and stop. The cadence is faster here
 * ({@code default-interval-seconds=2}); the rules are the same ones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("broker")
class MetricsCollectionRehearsalIT extends AbstractMetricsCollectionWalkthrough {

    /** A local stand-in for one Cache container. */
    static final class StubCache implements AutoCloseable {
        private final HttpServer server;
        private final AtomicLong misses = new AtomicLong();
        private final long base;
        volatile boolean down;
        volatile long startedAt = 1_790_000_000L;

        StubCache(long base) throws IOException {
            this.base = base;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "stub-cache");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handle);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (down) {
                reply(exchange, 503, "{}");
            } else if (path.equals("/api/cache/stats")) {
                reply(exchange, 200, "{\"hitRate\":0.5,\"hitCount\":" + base + ",\"missCount\":"
                        + (base / 100 + misses.get()) + ",\"evictionCount\":0}");
            } else if (path.equals("/api/cache")) {
                // the real Cache: a key that is absent answers 404 and counts a miss (Caffeine's missCount); no entry is made
                misses.incrementAndGet();
                reply(exchange, 404, "{}");
            } else if (path.equals("/api/cache/size")) {
                reply(exchange, 200, "42");
            } else if (path.equals("/actuator/metrics/process.start.time")) {
                reply(exchange, 200, "{\"name\":\"process.start.time\",\"measurements\":[{\"statistic\":\"VALUE\","
                        + "\"value\":" + startedAt + ".0}]}");
            } else if (path.equals("/actuator/health")) {
                reply(exchange, 200, "{\"status\":\"UP\"}");
            } else {
                reply(exchange, 404, "{}");
            }
        }

        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /** A new process: a new start time (the reset signal) and a counter that begins again. */
        void restart() {
            startedAt += 60;
            misses.set(0);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private StubCache first;
    private StubCache second;
    private Path key;

    /** In the constructor, not a {@code @BeforeAll}: the base class's own {@code @BeforeAll} needs the stubs' ports first. */
    MetricsCollectionRehearsalIT() throws IOException {
        first = new StubCache(1_000);
        second = new StubCache(50_000);
        key = Files.createTempFile("sm-e2e-metrics-rehearsal-", ".key");
        Files.writeString(key, com.solace.sm.dp.inventory.SshTestKeys.ed25519OpenSsh(
                    com.solace.sm.dp.inventory.SshTestKeys.ed25519Pair()).text()); // generated now, never committed
    }

    @AfterAll
    void closeStubs() throws IOException {
        if (first != null) {
            first.close();
        }
        if (second != null) {
            second.close();
        }
        if (key != null) {
            Files.deleteIfExists(key);
        }
    }

    private Map<String, String> properties() {
        Map<String, String> p = new HashMap<>();
        p.put("sm.walkthrough.ssh.host", "127.0.0.1");
        p.put("sm.walkthrough.ssh.user", "ec2-user");
        p.put("sm.walkthrough.ssh.fingerprint", "SHA256:" + "R".repeat(43));
        p.put("sm.walkthrough.ssh.key-file", key.toString());
        p.put("sm.walkthrough.ssh.container", "cache-engine");
        p.put("sm.walkthrough.config-dir", "/srv/cache-engine/config");
        p.put("sm.walkthrough.acquisition", "direct-http");
        p.put("sm.walkthrough.health-port", String.valueOf(first.port()));
        p.put("sm.walkthrough.metrics.minutes", "1");
        p.put("sm.walkthrough.metrics.host-port", String.valueOf(first.port()));
        p.put("sm.walkthrough.metrics.second.container", "cache-engine-2");
        p.put("sm.walkthrough.metrics.second.config-dir", "/srv/cache-engine/config/second");
        p.put("sm.walkthrough.metrics.second.host-port", String.valueOf(second.port()));
        p.put("sm.walkthrough.metrics.disruptive", "yes");
        p.put("sm.walkthrough.metrics.traffic", "yes");
        p.put("sm.walkthrough.metrics.traffic.count", "12");
        return p;
    }

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        assumeTrue(LocalMeshBroker.imageAvailable(), "the local image " + LocalMeshBroker.IMAGE
                + " is not in this Docker daemon, so the run's local broker cannot start");
        return E2eStack.liveOnLoopbackStubs(WalkthroughTarget.parse(properties()::get, n -> null), evidence);
    }

    @Override
    MetricsLiveTarget liveTarget() {
        Map<String, String> p = properties();
        return MetricsLiveTarget.parse(p::get, WalkthroughTarget.parse(p::get, n -> null));
    }

    @Override
    String evidencePrefix() {
        return "MetricsCollectionRehearsalIT";
    }

    @Override
    Map<String, String> bootOverrides() {
        return Map.of("sm.dp.metrics.ingest.default-interval-seconds", "2", "sm.dp.metrics.collect.tick", "2s");
    }

    @Override
    Duration interval() {
        return Duration.ofSeconds(2);
    }

    /** A 503 is an answer, so a stub that is down is not backed off: a few intervals show the gap. */
    @Override
    Duration downWindow() {
        return Duration.ofSeconds(10);
    }

    @Override
    boolean directReachable(MetricsLiveTarget.Cache cache) {
        return true;
    }

    private StubCache stubOf(Run run) {
        return run.cache().hostPort() == first.port() ? first : second;
    }

    @Override
    void restart(Run run) {
        stubOf(run).restart();
    }

    @Override
    void stop(Run run) {
        stubOf(run).down = true;
    }

    @Override
    void start(Run run) {
        StubCache stub = stubOf(run);
        stub.restart();
        stub.down = false;
    }
}
