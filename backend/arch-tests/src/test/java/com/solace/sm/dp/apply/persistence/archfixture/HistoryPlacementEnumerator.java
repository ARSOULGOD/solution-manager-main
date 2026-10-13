package com.solace.sm.dp.apply.persistence.archfixture;

import com.solace.sm.dp.apply.model.PlacementId;
import java.util.List;

/**
 * PLANTED (plan 008 S3, {@code BE-D46}): a persistence class that enumerates placements, by type, as the deleted
 * {@code ApplyRequestRepository.placementsOf} did. {@code PlacementEnumerationTest} must report it. The query it stands for
 * would be {@code SELECT DISTINCT placement_id FROM apply_request}: request HISTORY, which includes a retired placement.
 */
public final class HistoryPlacementEnumerator {

    public List<PlacementId> placementsOf() {
        return List.of();
    }
}
