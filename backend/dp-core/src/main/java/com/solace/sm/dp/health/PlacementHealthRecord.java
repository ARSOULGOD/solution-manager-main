package com.solace.sm.dp.health;

import com.solace.sm.dp.api.health.HealthState;
import java.time.Instant;
import java.util.UUID;

/** One {@code sm_data.placement_health} row. {@code stateVersion} is read, never written: the database stamps it. */
public record PlacementHealthRecord(UUID tenantId, UUID placementId, UUID instanceId, HealthState state,
        Instant checkedAt, Instant changedAt, long stateVersion) {
}
