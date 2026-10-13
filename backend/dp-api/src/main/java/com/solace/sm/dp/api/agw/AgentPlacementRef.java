package com.solace.sm.dp.api.agw;

import java.util.Objects;
import java.util.UUID;

/**
 * A placement addressed by the Data Plane itself, never by an agent. Mirrors {@code dp-core}'s
 * {@code PlacementRef(tenant, instance, placement)} with plain ids ({@code dp-api} cannot import {@code dp-core});
 * named {@code AgentPlacementRef} so it never collides by simple name with that built type. Every id here was
 * resolved by the DP — an agent-supplied {@code bindingId} or label never builds one (I5).
 */
public record AgentPlacementRef(UUID tenantId, UUID instanceId, UUID placementId) {
    public AgentPlacementRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
    }
}
