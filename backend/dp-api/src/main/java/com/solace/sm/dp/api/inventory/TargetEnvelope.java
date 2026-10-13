package com.solace.sm.dp.api.inventory;

import java.util.Objects;

/**
 * The envelope a target's registrant fixed (B2): what a declare body may name paths and containers inside. Read-only
 * and immutable once registered. It is the only part of a connection record that leaves the data plane; the host, the
 * user, the pinned fingerprint and the credential reference do not.
 */
public record TargetEnvelope(String allowedConfigRoot, String allowedContainerPrefix) {

    public TargetEnvelope {
        Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
        Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
    }
}
