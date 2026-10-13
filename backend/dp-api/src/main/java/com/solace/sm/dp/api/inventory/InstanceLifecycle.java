package com.solace.sm.dp.api.inventory;

import java.util.Locale;

/**
 * {@code pending} until the declare's second phase activates it; {@code abandoned} when the declare is given up.
 * {@code retiring} while an undeclare is between its data-plane intent and its completion, and {@code retired} once it
 * finished (plan 012 T3; both are terminal-ward: only {@code retiring} can return, and only to {@code active} when the
 * sweep rolls an undeclare back). Every reader that serves an instance serves {@code active} only.
 */
public enum InstanceLifecycle {
    PENDING, ACTIVE, ABANDONED, RETIRING, RETIRED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static InstanceLifecycle fromWire(String wire) {
        for (InstanceLifecycle l : values()) {
            if (l.wire().equals(wire)) {
                return l;
            }
        }
        throw new IllegalArgumentException("unknown instance lifecycle");
    }
}
