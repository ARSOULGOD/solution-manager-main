package com.solace.sm.events.contracttest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyTopics;
import com.solace.sm.events.publisher.EventPublisher;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Proves the kit itself fires: a publisher that skips NotifyEventShapes.validate() must FAIL
 * AbstractEventPublisherContractTest's shape check. Without this, the kit is decoration (L7).
 * Deliberately NOT a subclass of the kit — subclassing would run every kit test against the
 * bad publisher and redden the build; only the bypass check is exercised, inverted.
 */
class PublisherContractKitSelfTest {

    /** Derives the topic but never validates shape — the exact bypass the kit exists to catch. */
    private static final class NonValidatingPublisher implements EventPublisher {
        private final CapturingTransport transport;

        NonValidatingPublisher(CapturingTransport transport) {
            this.transport = transport;
        }

        @Override
        public void publish(EventEnvelope event) {
            transport.accept(NotifyTopics.topicFor(event), EventJson.write(event));
        }
    }

    @Test
    void theKitFailsAPublisherThatSkipsShapeValidation() {
        CapturingTransport transport = new CapturingTransport();
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> AbstractEventPublisherContractTest
                        .assertShapeInvalidEventNeverReachesTheTransport(
                                new NonValidatingPublisher(transport), transport));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank(),
                "the kit must name what the bad publisher got wrong");
        // Pin WHICH assertion fired. Without this the test passes on any kit failure at all —
        // including one caused by the kit itself breaking — rather than on the bypass it exists to
        // catch. The kit's first assertion on this path is assertThrows(IllegalArgumentException),
        // whose failure reads "Expected java.lang.IllegalArgumentException to be thrown, but
        // nothing was thrown": the non-validating publisher put the bad event on the wire instead.
        assertTrue(failure.getMessage().contains("IllegalArgumentException"),
                "the kit must fail because the bad publisher threw nothing, not for some other "
                        + "reason. Was: " + failure.getMessage());
    }
}
