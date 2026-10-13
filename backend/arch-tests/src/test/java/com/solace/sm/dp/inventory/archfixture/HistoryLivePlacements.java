package com.solace.sm.dp.inventory.archfixture;

import com.solace.sm.dp.apply.engine.LivePlacements;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import java.util.List;

/**
 * PLANTED (plan 008 S3, {@code BE-D46}): a {@code LivePlacements} that answers from the apply history (it holds
 * {@code ApplyRequestRepository}) instead of the registry. It would return a retired placement and fail every later
 * instance-wide stop. {@code PlacementEnumerationTest} must report it.
 */
public final class HistoryLivePlacements implements LivePlacements {

    private ApplyRequestRepository history;

    @Override
    public List<PlacementId> of(TenantId tenant, InstanceId instance) {
        return history == null ? List.of() : List.of();
    }
}
