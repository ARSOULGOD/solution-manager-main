package com.solace.sm.dp.apply.events;

/**
 * {@code instance.rollout.payload.step} - the contract's closed vocabulary
 * ({@code contracts/events/notify-events.schema.json}), as the data plane's own type. The engine's execution
 * vocabulary ({@code Step}) is internal to {@code sm_data}; this is the wire one, and the mapping between them is
 * made at the call sites in {@code ApplyExecutor} (design section 3.5).
 */
public enum RolloutStep {

    STAGED("staged"),
    RESTART_REQUESTED("restart-requested"),
    RESTART_OBSERVED("restart-observed");

    private final String wire;

    RolloutStep(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
