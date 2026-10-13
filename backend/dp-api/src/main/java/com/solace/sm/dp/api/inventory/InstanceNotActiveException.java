package com.solace.sm.dp.api.inventory;

/**
 * The instance a placement operation named is not {@code active} (or this tenant has no such instance), seen under the
 * instance row's lock. The gateway reads an instance before it calls, so this is the race: the instance left
 * {@code active} between that read and the lock (plan 012's undeclare is the writer). Answered {@code 404}, the same as
 * an instance that was never there. Names nothing.
 */
public final class InstanceNotActiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstanceNotActiveException() {
        super("the instance is not active");
    }
}
