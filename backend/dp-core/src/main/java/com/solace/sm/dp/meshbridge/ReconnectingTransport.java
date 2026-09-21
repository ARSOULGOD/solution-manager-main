package com.solace.sm.dp.meshbridge;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A mesh transport that STARTS DISCONNECTED and connects in the background with capped exponential
 * backoff — the deliberate answer to the bean-registration question {@code JcsmpEventPublisher}'s
 * javadoc left open: an eager singleton must never let a down broker block application boot
 * ({@code BE-D1}: broker down degrades freshness, never availability — design §9 row 1).
 *
 * <p><b>While disconnected, {@code accept} THROWS {@link IllegalStateException}</b> rather than
 * dropping silently. That is not a violation of the never-throw rule — this class sits on the
 * transport side of {@code JcsmpEventPublisher}'s seam, whose {@code publish} catch is the existing
 * counting swallow site. Throwing into it means every pre-connection publish is counted on the one
 * drop counter and WARN-logged with its eventId, with zero counter plumbing added here.
 *
 * <p><b>Division of labour with JCSMP:</b> this class owns the initial connection only. Once
 * {@code connectFactory} succeeds, mid-session outages are the session's own problem — the
 * {@code JcsmpTransport} constructor configures JCSMP channel reconnection, and a send that fails
 * anyway is counted by the existing {@code accept} catch. This class never tears down and re-creates
 * a session it has handed out.
 *
 * <p><b>Close/connect race, owned explicitly.</b> A JCSMP connect is not interruptible: if the
 * context shuts down while {@code connectFactory.get()} is in flight, the factory can still return a
 * live session into a dead context. So installation is an ownership handoff: the connector installs
 * the fresh delegate, then re-checks {@code closed}; {@code close()} flips {@code closed}, then
 * takes the delegate. Whichever side wins the atomic {@code getAndSet(null)} owns the close — never
 * both (no double-close), never neither (no leaked session outliving its context). {@code close()}
 * itself is idempotent behind a CAS, because Task 6's context {@code destroyMethod} may run after a
 * manual close.
 */
final class ReconnectingTransport implements BiConsumer<String, String>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingTransport.class);

    private final AtomicReference<BiConsumer<String, String>> delegate = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final Thread connector;

    ReconnectingTransport(Supplier<BiConsumer<String, String>> connectFactory,
            Duration initialBackoff, Duration maxBackoff) {
        this.connector = new Thread(() -> {
            long waitMillis = initialBackoff.toMillis();
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                BiConsumer<String, String> fresh;
                try {
                    fresh = connectFactory.get();
                } catch (RuntimeException e) {
                    log.warn("mesh connect failed — retrying in {} ms (publishes drop until then)",
                            waitMillis, e);
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    waitMillis = Math.min(waitMillis * 2, maxBackoff.toMillis());
                    continue;
                }
                // Ownership handoff — see the class javadoc. Install, then re-check `closed`:
                // whichever of this thread and close() wins the getAndSet(null) closes the session.
                delegate.set(fresh);
                if (closed.get()) {
                    closeQuietly(delegate.getAndSet(null));
                    return;
                }
                connected.countDown();
                log.info("mesh session established");
                return;
            }
        }, "sm-mesh-connect");
        this.connector.setDaemon(true);
        this.connector.start();
    }

    @Override
    public void accept(String topic, String body) {
        // Closed is checked FIRST and independently of the delegate: in the close-before-install
        // window the connector can briefly install a session after close() returned (the handoff
        // then reclaims and closes it), and a concurrent accept() must refuse rather than ride
        // that doomed session. Lands in JcsmpEventPublisher.publish's counting catch either way.
        if (closed.get()) {
            throw new IllegalStateException("mesh transport closed");
        }
        BiConsumer<String, String> d = delegate.get();
        if (d == null) {
            // Lands in JcsmpEventPublisher.publish's counting catch — see the class javadoc.
            throw new IllegalStateException("mesh session not yet established — still connecting");
        }
        d.accept(topic, body);
    }

    boolean isConnected() {
        return delegate.get() != null;
    }

    /** Test/IT seam: true once the background connect has succeeded within {@code timeout}. */
    boolean awaitConnected(Duration timeout) throws InterruptedException {
        return connected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Test seam: true once the retry thread has ended (connected, interrupted, or closed). */
    boolean awaitRetirement(Duration timeout) throws InterruptedException {
        connector.join(timeout.toMillis());
        return !connector.isAlive();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;   // idempotent: a second closer must not close a session the first already did
        }
        connector.interrupt();
        closeQuietly(delegate.getAndSet(null));
    }

    private static void closeQuietly(BiConsumer<String, String> transport) {
        if (transport instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("error closing mesh transport", e);
            }
        }
    }
}
