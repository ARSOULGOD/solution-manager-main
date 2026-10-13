package com.solace.sm.dp.api.inventory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One {@code retiring} instance, for the sweep: which, which open record, and since when ({@code requestedAt}). */
public record RetiringInstance(UUID instanceId, UUID retirementId, Instant requestedAt) {
    public RetiringInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(retirementId, "retirementId");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
