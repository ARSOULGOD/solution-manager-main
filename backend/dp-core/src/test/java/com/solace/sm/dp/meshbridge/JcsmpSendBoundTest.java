package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPInterruptedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 010 T6.6-1: a broker outage never holds a caller. JCSMP's {@code producer.send}, issued while the session is
 * reconnecting ({@code reconnectRetries=-1}), parks in {@code waitUntilSessionReconnectDone} instead of failing; the
 * real transport must therefore bound every send. These tests park the send the same way - an interruptible wait on a
 * latch that ends in a {@link JCSMPInterruptedException}, exactly what JCSMP's {@code Condition.await} does - and prove
 * the caller returns inside the bound and the notice is counted as a drop.
 *
 * <p>Every test carries a JUnit {@code @Timeout}: with the bound removed the parked send blocks the calling thread and
 * the test fails by timeout instead of hanging the build (the mutation proof recorded in the plan's T6.6-1 Results).
 */
class JcsmpSendBoundTest {

    private static final String TOPIC = "sm/v1/acme/notify/instance/i-1/health";
    private static final Duration BOUND = Duration.ofMillis(200);

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aParkedSendReturnsWithinTheBoundAndIsCountedAsADrop() {
        AtomicLong dropped = new AtomicLong();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch brokerBack = new CountDownLatch(1);
        var transport = new JcsmpEventPublisher.JcsmpTransport((topic, body) -> {
            try {
                brokerBack.await();   // the reconnect wait
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new JCSMPInterruptedException("interrupted while waiting for session reconnect", e);
            }
        }, () -> { }, dropped, BOUND);

        long start = System.nanoTime();
        transport.accept(TOPIC, "{}");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis < 5_000, "the caller was held " + elapsedMillis + " ms; the bound is " + BOUND.toMillis());
        assertTrue(elapsedMillis >= BOUND.toMillis() - 50, "the send was given its bound before it was abandoned: " + elapsedMillis);
        assertEquals(1L, dropped.get(), "the abandoned notice is counted as a drop, exactly once");
        awaitTrue(interrupted, "the parked send was cancelled (interrupted), not leaked");
        transport.close();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void publishingResumesByItselfWhenTheBrokerReturns() {
        AtomicLong dropped = new AtomicLong();
        AtomicBoolean brokerUp = new AtomicBoolean();
        List<String> delivered = new CopyOnWriteArrayList<>();
        var transport = new JcsmpEventPublisher.JcsmpTransport((topic, body) -> {
            if (!brokerUp.get()) {
                try {
                    new CountDownLatch(1).await();   // parked until interrupted
                } catch (InterruptedException e) {
                    throw new JCSMPInterruptedException("interrupted", e);
                }
            }
            delivered.add(body);
        }, () -> { }, dropped, BOUND);

        transport.accept(TOPIC, "while-down");
        assertEquals(1L, dropped.get());
        assertTrue(delivered.isEmpty());

        brokerUp.set(true);
        transport.accept(TOPIC, "after-return");
        assertEquals(List.of("after-return"), delivered, "the next notice is delivered: publishing resumed");
        assertEquals(1L, dropped.get(), "and it is not a drop");
        transport.close();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void withEverySenderStuckAnOfferIsRefusedAtOnceNotWaitedFor() throws Exception {
        AtomicLong dropped = new AtomicLong();
        CountDownLatch release = new CountDownLatch(1);
        // A send that ignores interruption: the worst case the pool cap exists for. Cancel cannot free its thread.
        var transport = new JcsmpEventPublisher.JcsmpTransport((topic, body) -> {
            boolean interruptedOnce = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interruptedOnce = true;
                }
            }
            if (interruptedOnce) {
                Thread.currentThread().interrupt();
            }
        }, () -> { }, dropped, Duration.ofMillis(20));

        int stuck = JcsmpEventPublisher.JcsmpTransport.MAX_IN_FLIGHT_SENDS;
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < stuck; i++) {
            Thread caller = new Thread(() -> transport.accept(TOPIC, "{}"));
            callers.add(caller);
            caller.start();
        }
        for (Thread caller : callers) {
            caller.join(10_000);
            assertFalse(caller.isAlive(), "every caller came back inside its bound even though its send is stuck");
        }
        assertEquals(stuck, dropped.get(), "each abandoned send was counted");

        long start = System.nanoTime();
        transport.accept(TOPIC, "{}");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis < 15, "with all senders stuck the offer is refused immediately, not after the bound: "
                + elapsedMillis + " ms");
        assertEquals(stuck + 1L, dropped.get(), "the refused offer is counted too");

        release.countDown();
        transport.close();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aCheckedFailureFromAFastSendIsStillCountedAndSwallowed() {
        AtomicLong dropped = new AtomicLong();
        var transport = new JcsmpEventPublisher.JcsmpTransport((topic, body) -> {
            throw new JCSMPException("broker refused the send");
        }, () -> { }, dropped, BOUND);

        transport.accept(TOPIC, "{}");

        assertEquals(1L, dropped.get());
        transport.close();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void anUncheckedFailureStillReachesPublishesCountingCatch() {
        AtomicLong dropped = new AtomicLong();
        var transport = new JcsmpEventPublisher.JcsmpTransport((topic, body) -> {
            throw new IllegalStateException("session closed");
        }, () -> { }, dropped, BOUND);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> transport.accept(TOPIC, "{}"),
                "unchecked failures propagate to JcsmpEventPublisher.publish, which counts them (unchanged behaviour)");
        transport.close();
    }

    private static void awaitTrue(AtomicBoolean flag, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!flag.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(flag.get(), message);
    }
}
