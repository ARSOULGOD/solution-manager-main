package com.solace.sm.dp.meshbridge;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Plan 010 T6.6, resilience scenario 3: "a notice is dropped (the transport throws once)".
 *
 * <p>This helper is a TEST class that deliberately lives in {@code dp-core}'s own package, because the seam it needs is
 * package-private there: {@code JcsmpEventPublisher}'s constructor over a transport and a shared drop counter (the same
 * seam its own unit tests use), {@code ReconnectingTransport} and {@code JcsmpTransport}. It builds the SAME composition
 * {@code JcsmpEventPublisher.connectingInBackground} builds - a real JCSMP session, connecting in the background, one
 * shared counter - and puts exactly one switchable fault IN FRONT of the real transport: when armed, the next
 * {@code accept} throws once instead of sending. Everything above it (the after-commit publisher, the emitter, the
 * engine) and the swallow-and-count catch inside {@code JcsmpEventPublisher.publish} are the production code, which is
 * the code scenario 3 asserts on.
 *
 * <p>Nothing in {@code dp-core}'s production tree changes; this file is under {@code launcher/src/test} only.
 */
public final class ThrowOnceMeshPublisher {

    private final JcsmpEventPublisher publisher;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicInteger injected = new AtomicInteger();

    private ThrowOnceMeshPublisher(MeshProperties props) {
        AtomicLong dropped = new AtomicLong();
        ReconnectingTransport real = new ReconnectingTransport(() -> new JcsmpEventPublisher.JcsmpTransport(props, dropped),
                Duration.ofSeconds(1), Duration.ofSeconds(30));
        FaultyTransport faulty = new FaultyTransport(real);
        this.publisher = new JcsmpEventPublisher(faulty, dropped);
    }

    /** The publisher the application should use in place of the one {@code MeshBridgeConfiguration} builds. */
    public static ThrowOnceMeshPublisher connectingInBackground(MeshProperties props) {
        return new ThrowOnceMeshPublisher(props);
    }

    public JcsmpEventPublisher publisher() {
        return publisher;
    }

    /** The next transport call throws once; every call after it goes to the real transport. */
    public void armThrowOnce() {
        armed.set(true);
    }

    /** How many calls this helper turned into a throw. */
    public int injectedThrows() {
        return injected.get();
    }

    private final class FaultyTransport implements BiConsumer<String, String>, AutoCloseable {

        private final ReconnectingTransport real;

        FaultyTransport(ReconnectingTransport real) {
            this.real = real;
        }

        @Override
        public void accept(String topic, String body) {
            if (armed.compareAndSet(true, false)) {
                injected.incrementAndGet();
                throw new IllegalStateException("injected transport failure (plan 010 T6.6 scenario 3)");
            }
            real.accept(topic, body);
        }

        @Override
        public void close() {
            real.close();
        }
    }
}
