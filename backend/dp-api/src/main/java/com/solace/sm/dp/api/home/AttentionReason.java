package com.solace.sm.dp.api.home;

/**
 * Why an instance is on the attention list, spelt as {@code console.yaml}'s {@code HomeAttention.reason} spells it. Each
 * reason is guarded by the verb that guards its SOURCE ({@code OR-61}, Q-C2): {@link #HEALTH} by {@code health.view},
 * {@link #APPLY_INDETERMINATE} and {@link #CONFIG_DIVERGED} by {@code config.view}.
 */
public enum AttentionReason {
    HEALTH("health"),
    APPLY_INDETERMINATE("apply-indeterminate"),
    CONFIG_DIVERGED("config-diverged");

    private final String wire;

    AttentionReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
