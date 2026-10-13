package com.solace.sm.dp.api.health;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One placement's persisted health. {@code stateVersion} is the row's OWN stamp - the watermark of
 * {@code instance.health} for this placement ({@code OR-55}: composed responses carry per-row versions only), moved
 * only when {@code state} changes. {@code checkedAt} moves on every probe.
 */
public record PlacementHealthView(UUID placementId, HealthState state, Instant checkedAt, long stateVersion) {

    public PlacementHealthView {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
