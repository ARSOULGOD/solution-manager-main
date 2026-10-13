package com.solace.sm.dp.inventory;

import com.solace.sm.dp.apply.engine.LivePlacements;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Objects;

/**
 * Plan 008 S3 ({@code BE-D46}): the engine's {@link LivePlacements} over the placement registry. Binds
 * {@code (tenant, instance)} and filters out a retired row ({@link PlacementRows#live}); it reads nothing from the apply
 * journal, and {@code PlacementEnumerationTest} keeps it that way. Lives here, not beside the engine, because the registry
 * is this package's and the engine may not depend on it.
 */
public final class RegistryLivePlacements implements LivePlacements {

    private final PlacementRows placements;

    public RegistryLivePlacements(PlacementRows placements) {
        this.placements = Objects.requireNonNull(placements, "placements");
    }

    @Override
    public List<PlacementId> of(TenantId tenant, InstanceId instance) {
        return placements.live(tenant.value(), instance.value()).stream()
                .map(p -> new PlacementId(p.placementId())).toList();
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "RegistryLivePlacements";
    }
}
