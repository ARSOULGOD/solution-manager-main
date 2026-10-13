package com.solace.sm.cp.catalog;

/** {@code OR-148}: whether a first load creates the missing entitlement, and with what limit. */
public record FirstLoadEntitlement(boolean enabled, int maxInstances) {

    public FirstLoadEntitlement {
        if (enabled && maxInstances < 1) {
            throw new IllegalArgumentException("sm.cp.entitlement.on-first-load.max-instances must be at least 1");
        }
    }

    public static FirstLoadEntitlement disabled() {
        return new FirstLoadEntitlement(false, 1);
    }
}
