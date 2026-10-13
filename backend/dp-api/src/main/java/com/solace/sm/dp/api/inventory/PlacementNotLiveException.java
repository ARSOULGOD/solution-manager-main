package com.solace.sm.dp.api.inventory;

/**
 * {@code retirePlacement} named a placement that is not a LIVE placement of this instance of this tenant: unknown,
 * another instance's, another tenant's, or already retired. All four are one answer ({@code 404}), so the operation is
 * no existence oracle for another instance's placements (I5). Names nothing.
 */
public final class PlacementNotLiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlacementNotLiveException() {
        super("no such live placement of this instance");
    }
}
