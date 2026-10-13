package com.solace.sm.dp.home;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port for the apply side of the home attention list (plan 010 T10.5). Read-only, and tenant-scoped
 * in its one method ({@code I5}); it enumerates no tenant.
 */
public interface ApplyAttentionRows {

    /**
     * The tenant's apply requests that need attention: for each (instance, placement) of an ACTIVE instance, its
     * LATEST request (by {@code requested_at}) when that request is {@code APPLY_INDETERMINATE} or
     * {@code CONFIG_DIVERGED}. "Latest" is what keeps a divergence that a later request superseded from staying on
     * the list forever: {@code CONFIG_DIVERGED} is a terminal state, so the row itself never goes away.
     */
    List<Row> needingAttention(UUID tenantId);

    /**
     * @param placementId empty for an instance-wide request (the nil-UUID sentinel is not a placement id)
     * @param state       {@code APPLY_INDETERMINATE} or {@code CONFIG_DIVERGED}, verbatim
     * @param stateVersion the request's OWN stamp
     */
    record Row(UUID instanceId, Optional<UUID> placementId, String state, long stateVersion) {

        public Row {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(placementId, "placementId");
            Objects.requireNonNull(state, "state");
        }
    }
}
