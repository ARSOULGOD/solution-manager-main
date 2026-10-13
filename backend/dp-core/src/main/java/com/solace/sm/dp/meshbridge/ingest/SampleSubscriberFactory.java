package com.solace.sm.dp.meshbridge.ingest;

import java.util.function.Consumer;

/**
 * Opens a session and starts delivering to {@code sink} (plan 011 P11-5.1). {@code credential} is the caller's buffer and is
 * zeroised by the caller as soon as this returns; an implementation that must keep a copy past that point (an SDK that takes
 * a {@code String}) owns the limit and says so.
 */
@FunctionalInterface
public interface SampleSubscriberFactory {

    /** @throws BrokerSessionException the broker could not be reached or refused the identity; a fixed sentence */
    SampleSubscriber open(BrokerEndpoint endpoint, char[] credential, Consumer<RawBrokerMessage> sink)
            throws BrokerSessionException;
}
