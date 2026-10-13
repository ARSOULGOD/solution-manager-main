package com.solace.sm.dp.api.inventory;

/**
 * {@code retirePlacement} found work in flight that names the placement ({@code BE-D53}): a {@code REQUESTED},
 * {@code RUNNING} or {@code APPLY_INDETERMINATE} request on it or on the whole instance (an instance-wide stop
 * addresses every live placement), or a fleet member reserving it that has not settled. {@code 409}. Names neither the
 * request nor the fleet.
 */
public final class PlacementInFlightException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlacementInFlightException() {
        super("work is in flight on the placement; let it land, then retire it");
    }
}
