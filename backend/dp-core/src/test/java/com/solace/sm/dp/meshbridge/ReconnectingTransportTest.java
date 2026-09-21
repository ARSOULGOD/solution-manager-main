package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class ReconnectingTransportTest {

    private static final Duration TICK = Duration.ofMillis(10);
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Test
    void startsDisconnectedAndRefusesLoudlyUntilConnected() throws Exception {
        CapturingTransport real = new CapturingTransport();
        AtomicInteger failuresLeft = new AtomicInteger(3);
        try (ReconnectingTransport t = new ReconnectingTransport(() -> {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("broker not reachable yet");
            }
            return real;
        }, TICK, TICK)) {

            // Whether the background thread has already won the race is timing; what is NOT timing
            // is the contract: before connection, accept() must throw so the publisher's swallow
            // site counts the drop — never a silent no-op that looks like a delivery.
            if (!t.isConnected()) {
                assertThrows(IllegalStateException.class,
                        () -> t.accept("sm/v1/acme/notify/instance/i-1/health", "{}"),
                        "a disconnected transport must throw into the publisher's counting catch");
            }

            assertTrue(t.awaitConnected(PATIENCE),
                    "three factory failures at a 10ms backoff must connect well within patience");
            t.accept("sm/v1/acme/notify/instance/i-1/health", "{}");
            assertEquals("sm/v1/acme/notify/instance/i-1/health", real.sent().getKey(),
                    "once connected, accept() must delegate to the real transport untouched");
        }
    }

    @Test
    void aPermanentlyUnreachableBrokerCostsCountedDropsNeverAThrowAtTheCaller() throws IOException {
        // Composed with the real publisher: the ISE from the disconnected transport must land in
        // JcsmpEventPublisher's existing RuntimeException catch — counted and swallowed, per BE-D1.
        ReconnectingTransport never = new ReconnectingTransport(() -> {
            throw new IllegalStateException("nothing listens here");
        }, Duration.ofMinutes(10), Duration.ofMinutes(10));
        try (never) {
            JcsmpEventPublisher p = new JcsmpEventPublisher(never);
            EventEnvelope event = EventJson.reader().readValue(Files.readString(
                    ContractArtifacts.dir("events/fixtures").resolve("health-transition.json")));

            p.publish(event);
            p.publish(event);

            assertEquals(2L, p.droppedCount(),
                    "broker-down publishes are freshness loss: counted, warned, never thrown");
            assertFalse(never.isConnected());
        }
    }

    @Test
    void closeBeforeEverConnectingStopsTheRetryThread() throws Exception {
        ReconnectingTransport t = new ReconnectingTransport(() -> {
            throw new IllegalStateException("still down");
        }, TICK, TICK);
        t.close();
        assertTrue(t.awaitRetirement(PATIENCE),
                "close() must end the background retry thread, or every context restart leaks one");
    }

    @Test
    void closeAfterConnectingClosesTheDelegate() throws Exception {
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> session, TICK, TICK);
        assertTrue(t.awaitConnected(PATIENCE));
        t.close();
        assertEquals(1, session.closedCount.get(), "the owned session must be closed exactly once");
    }

    /**
     * The context-restart leak: a JCSMP connect is NOT interruptible, so close() can run while
     * connectFactory.get() is still in flight — and the session the factory then produces belongs
     * to a dead context. The handoff must close it; without the post-install re-check it runs on
     * forever, holding a broker session nobody owns.
     */
    @Test
    void closeDuringAnInFlightConnectClosesTheSessionThatConnectProduces() throws Exception {
        CountDownLatch connectStarted = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> {
            connectStarted.countDown();
            awaitUninterruptibly(releaseConnect);   // models JCSMP: connect ignores Thread.interrupt
            return session;
        }, TICK, TICK);

        assertTrue(connectStarted.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS),
                "precondition: the connect attempt must actually be in flight before close() runs");
        t.close();                                  // context shutdown mid-connect
        releaseConnect.countDown();                 // the connect now completes into a dead context

        assertTrue(t.awaitRetirement(PATIENCE),
                "the connector thread must retire after handing its late session to the closer");
        assertEquals(1, session.closedCount.get(),
                "a session produced by an in-flight connect after close() must be closed, never "
                        + "leaked to outlive its context");
        assertFalse(t.isConnected(), "a closed transport must never present as connected");
    }

    @Test
    void closeIsIdempotentAndClosesTheDelegateExactlyOnce() throws Exception {
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> session, TICK, TICK);
        assertTrue(t.awaitConnected(PATIENCE));
        t.close();
        t.close();   // manual close followed by the context destroyMethod is a real Task 6 sequence
        assertEquals(1, session.closedCount.get(),
                "double close must not close the session twice");
    }

    @Test
    void acceptAfterCloseIsRefusedAsClosedNotAsStillConnecting() throws Exception {
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> session, TICK, TICK);
        assertTrue(t.awaitConnected(PATIENCE));
        t.close();
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> t.accept("sm/v1/acme/notify/instance/i-1/health", "{}"));
        assertTrue(boom.getMessage().contains("closed"),
                "a post-close publish is a lifecycle contract violation and must say so — "
                        + "'still connecting' would invite the caller to wait for a session that "
                        + "will never come. Was: " + boom.getMessage());
    }

    /** A delegate that counts its closes — the observable for every ownership assertion above. */
    static final class CountingCloseTransport implements BiConsumer<String, String>, AutoCloseable {
        final AtomicInteger closedCount = new AtomicInteger();

        @Override
        public void accept(String topic, String body) { }

        @Override
        public void close() {
            closedCount.incrementAndGet();
        }
    }

    /** A latch-wait that survives interruption, the way a real JCSMP connect does. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
