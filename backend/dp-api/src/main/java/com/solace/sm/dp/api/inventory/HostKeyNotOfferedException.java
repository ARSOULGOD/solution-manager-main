package com.solace.sm.dp.api.inventory;

/**
 * The pinned host key is not one the host offered on a fresh scan (plan 013 T1.4, {@code BE-D55}): a pin is only ever a
 * key the host itself presents. The message is fixed and names neither the host nor either fingerprint.
 */
public final class HostKeyNotOfferedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HostKeyNotOfferedException() {
        super("the host did not offer the pinned host key");
    }
}
