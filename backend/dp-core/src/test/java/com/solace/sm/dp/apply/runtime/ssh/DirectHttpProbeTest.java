package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code direct-http} read, against a real loopback server: it answers like {@code curl} does (M19). */
class DirectHttpProbeTest {

    private HttpServer server;
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private final DirectHttpProbe probe = DirectHttpProbe.jdk(new PermissiveTargetAddressPolicy());

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/up", x -> reply(x, 200, "{\"status\":\"UP\"}"));
        server.createContext("/down", x -> reply(x, 503, "{\"status\":\"DOWN\"}"));
        server.createContext("/redirect", x -> {
            x.getResponseHeaders().add("Location", "/target");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/target", x -> {
            redirectTargetHits.incrementAndGet();
            reply(x, 200, "{\"status\":\"UP\"}");
        });
        server.createContext("/big", x -> reply(x, 200, "x".repeat(HostProbe.MAX_BODY_BYTES + 10)));
        server.createContext("/slow", x -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply(x, 200, "late");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    @Test
    void anAnswerIsReportedWithItsStatusAndBodyWhateverTheStatusIs() {
        HostProbe.ProbeResult up = probe.get("localhost", port(), "/up", Duration.ofSeconds(5));
        assertEquals(0, up.exitCode());
        assertEquals(200, up.httpStatus());
        assertArrayEquals("{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8), up.body());

        HostProbe.ProbeResult down = probe.get("localhost", port(), "/down", Duration.ofSeconds(5));
        assertEquals(0, down.exitCode(), "a 503 is an answer, not a transport failure (M19, no --fail)");
        assertEquals(503, down.httpStatus());
        assertArrayEquals("{\"status\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8), down.body());
    }

    @Test
    void aRefusedConnectionIsExit7WithNoStatus() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        HostProbe.ProbeResult refused = probe.get("localhost", closed, "/up", Duration.ofSeconds(5));
        assertEquals(7, refused.exitCode());
        assertEquals(0, refused.httpStatus());
    }

    @Test
    void aStalledServerIsExit28NotAHang() {
        long started = System.nanoTime();
        HostProbe.ProbeResult slow = probe.get("localhost", port(), "/slow", Duration.ofSeconds(1));
        assertEquals(28, slow.exitCode());
        assertEquals(0, slow.httpStatus());
        assertEquals(true, Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }

    /** OR-105 (d): one probe serves the gate and the health poller, so the two causes the poller tells apart are told apart here. */
    @Test
    void aNameThatDoesNotResolveIsExit6NotARefusal() {
        HostProbe.ProbeResult unresolved = probe.get("no-such-host.invalid", 8081, "/up", Duration.ofSeconds(3));
        assertEquals(DirectHttpProbe.EXIT_UNREACHABLE, unresolved.exitCode(),
                "curl's own value for 'could not resolve host'; a refusal (7) means the host answered");
        assertEquals(0, unresolved.httpStatus());
    }

    @Test
    void aListenerThatAcceptsButNeverAnswersIsExit28WithinTheTimeout() throws IOException, InterruptedException {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ServerSocket s = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try (var c = s.accept()) {
                    release.await();
                } catch (Exception ignored) {
                    // closed by the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            long started = System.nanoTime();

            HostProbe.ProbeResult silent = probe.get("127.0.0.1", s.getLocalPort(), "/up", Duration.ofSeconds(1));

            assertEquals(28, silent.exitCode(), "the connection was made: reachable, and not answering");
            assertEquals(true, Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4)) < 0);
            release.countDown();
        }
    }

    @Test
    void aRedirectIsAnAnswerAndIsNeverFollowed() {
        HostProbe.ProbeResult redirect = probe.get("localhost", port(), "/redirect", Duration.ofSeconds(5));
        assertEquals(302, redirect.httpStatus(), "a redirect is a second address nobody registered (OR-82)");
        assertEquals(0, redirectTargetHits.get());
    }

    @Test
    void anOversizedAnswerIsATransportFailureNotATruncatedBody() {
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> probe.get("localhost", port(), "/big", Duration.ofSeconds(5)));
        assertEquals("http.probe.output.too.large", e.errorClass().token());
    }

    @Test
    void aHostPortPathOrTimeoutOutsideTheGrammarIsRefusedBeforeAnyConnection() {
        for (String host : List.of("", "a b", "a/b", "user@host", "[::1]", "host:80", "-x.example", "a\nb")) {
            assertThrows(RuntimeAdapterException.class,
                    () -> probe.get(host, port(), "/up", Duration.ofSeconds(5)), host);
        }
        for (String path : List.of("", "up", "/a?x=1", "/a b", "/a#f", "//evil/x@y")) {
            assertThrows(RuntimeAdapterException.class,
                    () -> probe.get("localhost", port(), path, Duration.ofSeconds(5)), path);
        }
        assertThrows(RuntimeAdapterException.class, () -> probe.get("localhost", 0, "/up", Duration.ofSeconds(5)));
        assertThrows(RuntimeAdapterException.class,
                () -> probe.get("localhost", port(), "/up", Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS + 1)));
        assertThrows(RuntimeAdapterException.class, () -> probe.get("localhost", port(), "/up", null));
    }
}
