package com.solace.sm.dp.api.inventory;

/**
 * A second {@code pending} or {@code active} instance tried to take a name already held in the same environment
 * (OR-88: an abandoned instance no longer holds its name). Names no instance and no value: the caller already knows
 * which name it sent, and an existence oracle on another instance's identity is not owed to it.
 */
public final class InstanceNameTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstanceNameTakenException() {
        super("a pending or active instance in this environment already has that name");
    }
}
