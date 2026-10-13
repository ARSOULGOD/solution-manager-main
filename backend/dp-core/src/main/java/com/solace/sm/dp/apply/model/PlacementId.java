package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A placement, or {@link #INSTANCE_WIDE} — design §3.1's explicit nil-UUID sentinel.
 *
 * <p>The sentinel is a VALUE, never SQL NULL, and the reason is in the DDL: PostgreSQL 14 treats NULL
 * index keys as distinct, so a nullable {@code placement_id} would let two instance-wide requests
 * both satisfy {@code apply_inflight_fence}. {@code placement_watermark} refuses the sentinel outright
 * ({@code real_placements_only}) because instance-wide watermarks live on {@code apply_scope}.
 *
 * @param value the placement's UUID, or the nil UUID for instance-wide
 */
public record PlacementId(UUID value) {

    /** The instance-wide sentinel of design §3.1. */
    public static final PlacementId INSTANCE_WIDE = new PlacementId(new UUID(0L, 0L));

    public PlacementId {
        Objects.requireNonNull(value, "placement id");
    }

    public boolean isInstanceWide() {
        return INSTANCE_WIDE.value.equals(value);
    }
}
