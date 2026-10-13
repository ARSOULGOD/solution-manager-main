package com.solace.sm.dp.api.fleet;

/**
 * A member's placement is already reserved by another running fleet ({@code 409}). Names the member's ordinal only,
 * never the other fleet's id ({@code fleet-apply.md} section 7).
 */
public final class FleetConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int ordinal;

    public FleetConflictException(int ordinal) {
        super("member " + ordinal + " names a placement reserved by another running fleet");
        this.ordinal = ordinal;
    }

    public int ordinal() {
        return ordinal;
    }
}
