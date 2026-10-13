package com.solace.sm.dp.apply.persistence.archfixture;

import com.solace.sm.dp.apply.model.PlacementId;
import java.util.List;
import java.util.Optional;

/**
 * The counterpart: persistence methods that mention a placement without ENUMERATING placements. A single id, an optional
 * one and a list of something else are all legal; a rule that rejected them would reject {@code stagedRequest} and prove
 * nothing.
 */
public final class LegalPlacementReturns {

    public PlacementId one() {
        return PlacementId.INSTANCE_WIDE;
    }

    public Optional<PlacementId> maybe() {
        return Optional.empty();
    }

    public List<String> somethingElse() {
        return List.of();
    }
}
