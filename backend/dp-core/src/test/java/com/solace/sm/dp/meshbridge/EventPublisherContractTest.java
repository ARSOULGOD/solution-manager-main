package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.events.contracttest.AbstractEventPublisherContractTest;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.publisher.EventPublisher;
import com.solacesystems.jcsmp.JCSMPException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@link JcsmpEventPublisher}'s half of the publisher contract — no broker, no mocks of JCSMP.
 *
 * <p>Everything that is <b>the seam's</b> contract rather than this implementation's now lives in
 * {@link AbstractEventPublisherContractTest} and runs here by inheritance: derive-and-serialize,
 * the loud guards, the swallow-and-count paths, and the emitted-payload-key check across every
 * fixture. Extending the kit is not optional — design §5.2 requires it of every
 * {@link EventPublisher} implementation, precisely so a later one cannot skip
 * {@code NotifyEventShapes.validate} behind a green happy-path suite of its own.
 *
 * <p>What stays here is only what is <b>JCSMP-specific</b>: the two drop sites inside
 * {@code JcsmpTransport} and {@code DirectPublishEvents}, which are this class's internals and have
 * no meaning for another implementation of the seam.
 *
 * <p>{@link JcsmpEventPublisher}'s package-private seam constructor takes a
 * {@code BiConsumer<String,String>} of <b>(derived topic, serialized body)</b>, which
 * {@link CapturingTransport} implements; this test class lives in the same package, so the kit
 * reaches the real publisher without the seam having to be widened.
 */
class EventPublisherContractTest extends AbstractEventPublisherContractTest {

    @Override
    protected EventPublisher publisher(CapturingTransport transport) {
        return new JcsmpEventPublisher(transport);
    }

    @Override
    protected long droppedCount(EventPublisher publisher) {
        return ((JcsmpEventPublisher) publisher).droppedCount();
    }

    // ---- the two drop sites that no test could reach -------------------------------------------

    /**
     * {@code JcsmpTransport.accept}'s {@code JCSMPException} catch. Until R8 the only test that
     * could reach it was the compose-gated IT, which skips — an R8 mutation run deleted the
     * increment and the whole build stayed green. The seam hands this class's real {@code accept}
     * a send that throws exactly what {@code producer.send} throws, so what is under test is the
     * catch itself and not a stub of it.
     */
    @Test
    void theJcsmpSendCatchCountsItsDropAndSwallowsTheException() {
        AtomicLong dropped = new AtomicLong();
        var transport = new JcsmpEventPublisher.JcsmpTransport(
                (topic, body) -> {
                    throw new JCSMPException("broker refused the send");
                },
                () -> { },
                dropped);

        assertDoesNotThrow(() -> transport.accept("sm/v1/acme/notify/instance/i-1/health", "{}"),
                "a checked JCSMP failure must be swallowed at this layer, never rethrown");
        assertEquals(1L, dropped.get(), "the JCSMP send catch must count its drop");

        assertDoesNotThrow(() -> transport.accept("sm/v1/acme/notify/instance/i-1/health", "{}"));
        assertEquals(2L, dropped.get(), "the counter must accumulate, not latch");
    }

    /**
     * The asynchronous error handler — the one drop site with no synchronous trace at all, and
     * therefore the one where an uncounted drop is completely invisible. Same story as above: its
     * increment was deletable with the build green, because only a live broker ever called it.
     */
    @Test
    void theAsynchronousPublishErrorHandlerCountsItsDrop() {
        AtomicLong dropped = new AtomicLong();
        var handler = new JcsmpEventPublisher.DirectPublishEvents(dropped);

        handler.handleErrorEx("correlation-key", new JCSMPException("async transport error"),
                System.currentTimeMillis());
        assertEquals(1L, dropped.get(), "the async error handler must count its drop");

        handler.responseReceivedEx("correlation-key");
        assertEquals(1L, dropped.get(),
                "an acknowledgement is not a drop — DIRECT never calls this, and if it ever does "
                        + "it must not move the counter");

        handler.handleErrorEx("correlation-key", new JCSMPException("and another"), 0L);
        assertEquals(2L, dropped.get(), "the counter must accumulate, not latch");
    }
}
