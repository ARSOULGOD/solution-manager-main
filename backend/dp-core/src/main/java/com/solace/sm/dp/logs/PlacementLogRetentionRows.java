package com.solace.sm.dp.logs;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The persistence port for {@code sm_data.placement_log_retention} (plan 016 task 9): the stored on-disk log cap of an agent placement. */
public interface PlacementLogRetentionRows {

    /**
     * Whether THIS tenant has a live (not retired) placement with this id on an agent target. Tenant-bound (I5): another
     * organization's placement, a retired one and an SSH one all answer {@code false}.
     */
    boolean isLiveAgentPlacement(UUID tenantId, UUID placementId);

    /** Stores the cap, replacing an earlier one ({@code INSERT ... ON CONFLICT DO UPDATE} of the cap and its time only). */
    void upsert(UUID tenantId, UUID placementId, long maxBytes, Instant at);

    /** The stored cap, if one was ever set. */
    Optional<Long> find(UUID tenantId, UUID placementId);
}
