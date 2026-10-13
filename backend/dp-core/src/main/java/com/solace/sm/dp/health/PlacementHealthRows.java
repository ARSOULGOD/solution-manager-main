package com.solace.sm.dp.health;

import com.solace.sm.dp.api.health.HealthState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The persistence port for {@code placement_health}. Every method names the tenant (I5). */
public interface PlacementHealthRows {

    Optional<PlacementHealthRecord> find(UUID tenantId, UUID placementId);

    /**
     * Records one probe's verdict: inserts the first row, otherwise updates it. The database stamps the row only when
     * {@code state} changes (OR-39: only changes are persisted), and {@code checked_at} always moves.
     *
     * @return whether the state changed (the stamp moved) and the row's stamp after the write
     */
    Recorded record(UUID tenantId, UUID instanceId, UUID placementId, HealthState state, Instant at);

    /** The persisted health of the instance's LIVE (not retired) placements only. */
    List<PlacementHealthRecord> liveOf(UUID tenantId, UUID instanceId);

    /** @param changed true when this write inserted the row or changed its state */
    record Recorded(boolean changed, long stateVersion) {
    }
}
