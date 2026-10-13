package com.solace.sm.dp.inventory;

import java.util.Objects;
import java.util.UUID;

/**
 * The B2 envelope of one LIVE {@code sm_data.connection_runtime} row (V038, plan 014): the only part of a remote-server
 * Connection's projection a declare needs. Carries no host, user, fingerprint or credential reference.
 */
public record ConnectionRuntimeEnvelope(UUID connectionId, String allowedConfigRoot, String allowedContainerPrefix) {

    public ConnectionRuntimeEnvelope {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
        Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
    }
}
