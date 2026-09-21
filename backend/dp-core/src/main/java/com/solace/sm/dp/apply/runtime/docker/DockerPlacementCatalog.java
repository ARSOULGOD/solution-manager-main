package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.runtime.PlacementRef;

/**
 * Resolves a placement to its Docker binding.
 *
 * <p>An interface, not a table lookup, because the inventory tables are W5's and the endpoints that
 * would populate them are G2's — and plan 004 left {@code apply_request.placement_id} with no
 * referential guard at all. A configuration-resolved catalog is the only non-auth, non-schema way to
 * name a placement today, and it is one interface away from the table that replaces it.
 */
@FunctionalInterface
public interface DockerPlacementCatalog {

    /** @throws IllegalArgumentException when this placement has no declared Docker binding. */
    DockerPlacement lookup(PlacementRef placement);
}
