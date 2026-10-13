package com.solace.sm.dp.api.inventory;

/**
 * {@code addPlacement} found an instance-wide request in flight ({@code BE-D47}, W2 review W2A-2): a {@code REQUESTED},
 * {@code RUNNING} or {@code APPLY_INDETERMINATE} request on the whole instance. An instance-wide stop reads the live
 * placement set once, when it starts running, so a placement added while one is in flight would be left running under a
 * stop that then reports the instance stopped. {@code 409}; the symmetric refusal of {@code retirePlacement}'s. Names no
 * request.
 */
public final class InstanceRequestInFlightException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstanceRequestInFlightException() {
        super("an instance-wide request is in flight; let it land, then add the placement");
    }
}
