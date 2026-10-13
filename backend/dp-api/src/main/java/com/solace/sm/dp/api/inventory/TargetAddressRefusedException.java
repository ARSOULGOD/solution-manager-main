package com.solace.sm.dp.api.inventory;

/**
 * The data plane may not connect to the address a target's host names (plan 013 T1.2, D-REG section 7.3): it resolves
 * to loopback, link-local, unspecified or multicast, or lies outside the operator's allow-list. The message is fixed
 * and names neither the host nor the address (a refusal text is never a reflection of the request).
 */
public final class TargetAddressRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TargetAddressRefusedException() {
        super("the target's address is not one the data plane may connect to");
    }
}
