package com.solace.sm.dp.api.config;

/**
 * The five rungs of the configuration-state ladder, in order (OR-37): the same five, with the same wire spellings, in
 * {@code console.yaml}'s {@code ConfigLadder} and the {@code instance.config-state} notify event.
 */
public enum LadderRung {
    DESIRED("desired"),
    RENDERED("rendered"),
    PERSISTED("persisted"),
    RESTART_OBSERVED("restart-observed"),
    EFFECTIVE_VERIFIED("effective-verified");

    private final String wire;

    LadderRung(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
