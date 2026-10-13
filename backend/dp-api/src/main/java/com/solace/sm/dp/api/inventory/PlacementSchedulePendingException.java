package com.solace.sm.dp.api.inventory;

/**
 * {@code retirePlacement} found a {@code PENDING} scheduled apply that names the placement (Fable F-3; the principle of
 * {@code OR-131} Q2's undeclare refusal, applied to one placement). {@code 409}. Cancel the placement's pending schedules,
 * then retire it. Names neither the schedule nor its submitter.
 */
public final class PlacementSchedulePendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlacementSchedulePendingException() {
        super("a scheduled apply is still pending on the placement; cancel it, then retire the placement");
    }
}
