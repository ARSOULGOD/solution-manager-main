package com.solace.sm.dp.api.inventory;

/**
 * A target cannot be retired while a live placement names it (plan 013 T1.5; V007's
 * {@code target_connection_retire_guard}, mapped). {@code 409}. Names no instance and no placement.
 */
public final class TargetHasLivePlacementsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TargetHasLivePlacementsException() {
        super("a target cannot be retired while it has live placements");
    }
}
