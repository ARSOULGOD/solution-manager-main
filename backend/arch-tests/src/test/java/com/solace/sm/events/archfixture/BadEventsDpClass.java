package com.solace.sm.events.archfixture;

import com.solace.sm.dp.api.DpMarker;

/**
 * Planted {@code I7} violation: the plane-neutral {@code mesh-events} module taking a data-plane
 * dependency.
 *
 * <p>The mirror of {@link BadEventsCpClass}. Today only {@code dp-core} consumes
 * {@code mesh-events}, so a data-plane edge here looks harmless — but the moment {@code cp-core}
 * consumes it too, {@code cp-core → mesh-events → dp-api} is a live conduit no direct rule fires
 * on. Neutrality is enforced in both directions, before the second consumer exists rather than
 * after.
 *
 * <p><b>Do not copy this pattern into production code.</b> Task 9 fills {@code mesh-events}.
 */
public final class BadEventsDpClass {

    private BadEventsDpClass() {
    }

    /** The forbidden edge: {@code com.solace.sm.events..} depending on {@code com.solace.sm.dp..}. */
    public static DpMarker forbiddenDataPlaneReference() {
        return null;
    }
}
