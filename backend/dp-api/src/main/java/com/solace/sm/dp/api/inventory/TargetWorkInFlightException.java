package com.solace.sm.dp.api.inventory;

/**
 * An edit of a target's host, port or pin was refused because work is in flight on a placement of the target (plan 013
 * T1.5, OQ-12 (a)): an apply that is {@code REQUESTED}, {@code RUNNING} or {@code APPLY_INDETERMINATE}, or a fleet member
 * reserving the placement that has not settled. Changing where a running apply connects mid-flight could leave it
 * straddling two hosts. {@code 409}; the caller retries when the work has finished. Names no request, instance or
 * placement.
 */
public final class TargetWorkInFlightException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TargetWorkInFlightException() {
        super("work is in flight on a placement of this target; let it finish, then edit the connection");
    }
}
