package com.solace.sm.events.archfixture;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.EventPublisher;

/**
 * Planted violation for the publisher-inventory check: an {@link EventPublisher} implementation the
 * inventory does not list. If the inventory cannot flag this class, it cannot flag the real thing —
 * a future publisher added without a contract-kit subclass (design §5.2).
 */
public final class BadUnkittedPublisherClass implements EventPublisher {

    @Override
    public void publish(EventEnvelope event) {
        // Planted violation — never called.
    }
}
