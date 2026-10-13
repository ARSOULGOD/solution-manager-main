package com.solace.sm.dp.meshbridge.ingest;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/** Opens {@link JcsmpSampleSubscriber}s (plan 011 P11-5.1): the production {@link SampleSubscriberFactory}. */
public final class JcsmpSampleSubscriberFactory implements SampleSubscriberFactory {

    private final Clock clock;

    public JcsmpSampleSubscriberFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SampleSubscriber open(BrokerEndpoint endpoint, char[] credential, Consumer<RawBrokerMessage> sink)
            throws BrokerSessionException {
        return JcsmpSampleSubscriber.open(endpoint, credential, sink, clock);
    }
}
