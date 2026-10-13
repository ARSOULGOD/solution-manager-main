package com.solace.sm.dp.meshbridge.ingest;

import java.util.Set;
import java.util.UUID;

/**
 * The control surface the push service drives (plan 011 P11-5.1): the desired subscriptions of each broker connection.
 * Declarative and idempotent: the service states what each connection should hold on every tick, and the implementation
 * makes it so without ever holding the caller (the metrics loop's thread).
 */
public interface BrokerSubscriptions {

    /**
     * States that {@code connectionId} (of {@code tenantId}) should hold a session on {@code endpoint}, as the identity whose
     * credential is {@code credentialRef}, subscribed to exactly {@code patterns}, delivering to {@code sink}. An empty set is
     * the same as {@link #release}.
     */
    void reconcile(UUID tenantId, UUID connectionId, BrokerEndpoint endpoint, UUID credentialRef, Set<String> patterns,
            BrokerMessageSink sink);

    /** States that {@code connectionId} of {@code tenantId} should hold no session. */
    void release(UUID tenantId, UUID connectionId);

    /** The connections that currently hold an open session. */
    Set<Key> held();

    /**
     * A broker connection's identity: {@code broker_connection}'s primary key is {@code (tenant_id, broker_connection_id)} and an
     * operator may choose the id, so two organizations can hold the same connection id (W3-15). Every map is keyed by both.
     */
    record Key(UUID tenantId, UUID connectionId) {
        public Key {
            java.util.Objects.requireNonNull(tenantId, "tenantId");
            java.util.Objects.requireNonNull(connectionId, "connectionId");
        }
    }
}
