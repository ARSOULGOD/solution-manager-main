package com.solace.sm.dp.api.inventory;

/**
 * {@code retirePlacement} was asked to retire the instance's LAST live placement. That is releasing the instance from
 * management (undeclare, plan 012), not removing one placement, so it is refused ({@code 409}, {@code BE-D53}).
 */
public final class LastLivePlacementException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LastLivePlacementException() {
        super("the placement is the instance's last live placement; undeclare the instance instead");
    }
}
