package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.meshbridge.ingest.BrokerEndpoint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code sm_data.broker_connection} row (plan 011 P11-5.1; OR-107 OQ-3 (a)): the customer broker an environment's
 * managed integrations publish their business events to. {@code credentialRef} is a reference into the secret store
 * ({@code BROKER_CREDENTIAL}); this record never holds the credential (I6), and its {@code toString} names no coordinate a log
 * line has no business carrying.
 */
public record BrokerConnectionRecord(UUID tenantId, UUID environmentId, UUID brokerConnectionId, String scheme,
        String host, int port, String vpn, String clientUsername, UUID credentialRef, UUID createdBy, Instant createdAt,
        Instant retiredAt) {

    public BrokerConnectionRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(vpn, "vpn");
        Objects.requireNonNull(clientUsername, "clientUsername");
        Objects.requireNonNull(credentialRef, "credentialRef");
    }

    public BrokerEndpoint endpoint() {
        return new BrokerEndpoint(scheme, host, port, vpn, clientUsername);
    }

    /** Whether {@code other} describes the same connection, whatever its credential reference and audit columns say. */
    public boolean sameConnectionAs(BrokerConnectionRecord other) {
        return tenantId.equals(other.tenantId) && environmentId.equals(other.environmentId)
                && brokerConnectionId.equals(other.brokerConnectionId) && endpoint().equals(other.endpoint());
    }

    @Override
    public String toString() {
        return "BrokerConnectionRecord[" + brokerConnectionId + "]";
    }
}
