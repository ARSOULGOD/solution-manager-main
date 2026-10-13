package com.solace.sm.dp.meshbridge.ingest;

import java.util.UUID;

/**
 * Where a subscriber hands what it received (plan 011 P11-5.1). Called ON THE BROKER SDK'S RECEIVE THREAD, so an
 * implementation must not block: it queues and returns. The tenant and connection are the SUBSCRIBER's, fixed when the session
 * was opened, never read from the message (I5).
 */
@FunctionalInterface
public interface BrokerMessageSink {

    void accept(UUID tenantId, UUID brokerConnectionId, RawBrokerMessage message);
}
