package com.solace.sm.dp.api.inventory;

/**
 * An operation that addresses an SSH connection record (an edit, a credential rotation, a connection test) was asked of a target whose transport is {@code agent} (plan 015 T3.5, V028). {@code 409 TARGET_TRANSPORT_UNSUPPORTED}. Nothing was read or written and no connector was built. Names no target, host, agent or placement.
 */
public final class TargetTransportUnsupportedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TargetTransportUnsupportedException() {
        super("an agent target has no SSH connection to change");
    }
}
