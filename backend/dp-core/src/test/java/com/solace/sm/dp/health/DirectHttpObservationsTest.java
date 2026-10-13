package com.solace.sm.dp.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.DirectHttpProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T7.2 (OR-82), after OR-105 (d): a {@code direct-http} instance's poll runs the apply path's one
 * {@link DirectHttpProbe} through {@link DirectHttpObservations}. The behaviour the poller's own probe used to be measured
 * for is kept here against real sockets on loopback (a closed port, a stalled listener, a name that does not resolve, an
 * oversized answer), and the mapping table is pinned over a scripted probe.
 */
class DirectHttpObservationsTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int serve(String path, int status, byte[] body, String location) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private final DirectHttpProbe jdk = DirectHttpProbe.jdk(new PermissiveTargetAddressPolicy());

    private Observation observe(String host, int port, String path, Duration timeout) {
        return DirectHttpObservations.observe(jdk, host, port, path, timeout);
    }

    private static Observation mapped(HostProbe.ProbeResult result) {
        return DirectHttpObservations.observe((h, p, path, t) -> result, "h.example", 8081, "/h", Duration.ofSeconds(3));
    }

    private static HostProbe.ProbeResult exit(int code) {
        return new HostProbe.ProbeResult(code, 0, new byte[0]);
    }

    @Test
    void anAnswerIsItsStatusAndItsBody() throws Exception {
        int port = serve("/actuator/health", 200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8), null);

        Observation o = observe("127.0.0.1", port, "/actuator/health", Duration.ofSeconds(3));

        Observation.Answered answered = assertInstanceOf(Observation.Answered.class, o);
        assertEquals(200, answered.httpStatus());
        assertEquals("{\"status\":\"UP\"}", new String(answered.body(), StandardCharsets.UTF_8));
    }

    @Test
    void aDownAnswerIsAnAnswerNotAFailure() throws Exception {
        int port = serve("/h", 503, "{\"status\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8), null);

        Observation.Answered answered = assertInstanceOf(Observation.Answered.class,
                observe("127.0.0.1", port, "/h", Duration.ofSeconds(3)));

        assertEquals(503, answered.httpStatus());
    }

    @Test
    void aRedirectIsNeverFollowed() throws Exception {
        // following one would let a target send the DP's request to an address nobody registered (B2)
        int port = serve("/h", 302, new byte[0], "http://169.254.169.254/latest/meta-data/");

        Observation.Answered answered = assertInstanceOf(Observation.Answered.class,
                observe("127.0.0.1", port, "/h", Duration.ofSeconds(3)));

        assertEquals(302, answered.httpStatus());
    }

    @Test
    void aClosedPortIsRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }

        assertEquals(Observation.REFUSED, observe("127.0.0.1", port, "/h", Duration.ofSeconds(3)));
    }

    @Test
    void aListenerThatAcceptsButNeverAnswersIsSilentWithinTheTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
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
            long start = System.nanoTime();

            Observation o = observe("127.0.0.1", s.getLocalPort(), "/h", Duration.ofSeconds(1));

            assertEquals(Observation.SILENT, o,
                    "the TCP connection was made, so the host is reachable and the service is not answering: red, as over SSH");
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0,
                    "bounded by the timeout");
            release.countDown();
        }
    }

    @Test
    void aNameThatDoesNotResolveIsUnreachable() {
        assertEquals(Observation.UNREACHABLE, observe("no-such-host.invalid", 8081, "/h", Duration.ofSeconds(3)));
    }

    @Test
    void anOversizedAnswerIsNotAHealthDocument() throws Exception {
        int port = serve("/h", 200, new byte[HostProbe.MAX_BODY_BYTES + 1024], null);

        assertEquals(Observation.SILENT, observe("127.0.0.1", port, "/h", Duration.ofSeconds(3)));
    }

    @Test
    void aPathOutsideTheGrammarOrAPortOutsideTheRangeIsRefusedBeforeAnyConnection() {
        for (String path : new String[] {"", "relative", "/a;b", "/a b", "/a?x=1", "/$(id)", "/a\nb", "/é",
                "/" + "a".repeat(256)}) {
            assertThrows(RuntimeAdapterException.class,
                    () -> observe("127.0.0.1", 8081, path, Duration.ofSeconds(3)), path);
        }
        for (int port : new int[] {0, -1, 65536}) {
            assertThrows(RuntimeAdapterException.class,
                    () -> observe("127.0.0.1", port, "/h", Duration.ofSeconds(3)));
        }
        for (String host : new String[] {"", "a b", "a/b", "a@b", "a:b", "-x", "user@host"}) {
            assertThrows(RuntimeAdapterException.class,
                    () -> observe(host, 8081, "/h", Duration.ofSeconds(3)), host);
        }
    }

    // ---- the mapping table, over a scripted probe --------------------------------------------------------------

    @Test
    void anExitZeroIsAnAnswerWhateverItsStatus() {
        Observation o = mapped(new HostProbe.ProbeResult(0, 503, "{}".getBytes(StandardCharsets.UTF_8)));

        Observation.Answered answered = assertInstanceOf(Observation.Answered.class, o);
        assertEquals(503, answered.httpStatus());
        assertEquals("{}", new String(answered.body(), StandardCharsets.UTF_8));
    }

    @Test
    void theExitCodesMapToTheObservationsTheSshPathUsesForTheSameCauses() {
        assertEquals(Observation.REFUSED, mapped(exit(7)), "connection refused: the host answered, nothing listens");
        assertEquals(Observation.UNREACHABLE, mapped(exit(DirectHttpProbe.EXIT_UNREACHABLE)),
                "the host or the network could not be reached");
        assertEquals(Observation.SILENT, mapped(exit(28)), "reached, no usable answer in time");
        assertEquals(Observation.SILENT, mapped(exit(1)), "any other transport failure after the host was reached");
    }

    @Test
    void anOversizedAnswerReadsSilentAndAnyOtherAdapterFailureReadsUnreachable() {
        assertEquals(Observation.SILENT, DirectHttpObservations.observe((h, p, path, t) -> {
            throw new RuntimeAdapterException(new ErrorClass("http.probe.output.too.large"));
        }, "h.example", 8081, "/h", Duration.ofSeconds(3)));
        assertEquals(Observation.UNREACHABLE, DirectHttpObservations.observe((h, p, path, t) -> {
            throw new RuntimeAdapterException(new ErrorClass("something.else"));
        }, "h.example", 8081, "/h", Duration.ofSeconds(3)));
    }

    @Test
    void aGrammarRefusalIsNotMappedToAHealthStateItPropagates() {
        // the poller already logs and skips a placement whose declaration cannot be probed; it must not invent a state
        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class,
                () -> DirectHttpObservations.observe((h, p, path, t) -> {
                    throw new RuntimeAdapterException(new ErrorClass("http.probe.refused"));
                }, "h.example", 8081, "/h", Duration.ofSeconds(3)));
        assertEquals("http.probe.refused", refused.errorClass().token());
    }

    @Test
    void aProbeCutOffByAnInterruptRecordsNothing() {
        try {
            Observation o = DirectHttpObservations.observe((h, p, path, t) -> {
                Thread.currentThread().interrupt();
                return exit(1);
            }, "h.example", 8081, "/h", Duration.ofSeconds(3));

            assertEquals(Observation.DEFERRED, o, "a shutdown must not be persisted as a red service");
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt is left set for the caller");
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
}
