package com.solace.sm.dp.api.health;

/**
 * A placement's health in the notify vocabulary (owner ruling S0-11, {@code OR-39}, {@code OR-54}): the SCREAMING
 * rollup keys plus the four NOTFOUND sub-states as distinct values, spelt exactly as
 * {@code contracts/events/notify-events.schema.json}'s {@code instance.health} payload and {@code console.yaml}'s
 * {@code Health} state enum spell them. {@code UNREACHABLE} is OR-39's "UNKNOWN, not DOWN": a host or network problem,
 * not an instance problem.
 *
 * <p>The poller produces {@link #GREEN}, {@link #RED}, {@link #UNREACHABLE}, {@link #NOT_EXPOSED} and, for a placement
 * whose package declares no probe ({@code OR-101}), {@link #NOT_APPLICABLE}. The rest of the
 * vocabulary is carried so the contract's enum is whole; nothing in this plan produces them.
 */
public enum HealthState {
    GREEN("GREEN"),
    RED("RED"),
    RESTART_EXPECTED("RESTART_EXPECTED"),
    INACTIVE_REPLICA("INACTIVE_REPLICA"),
    NOT_APPLICABLE("NOT_APPLICABLE"),
    UNREACHABLE("unreachable"),
    NOT_EXPOSED("not_exposed"),
    MISSING("missing"),
    BOOTSTRAP("bootstrap");

    private final String wire;

    HealthState(String wire) {
        this.wire = wire;
    }

    /** The token on the wire, in the database CHECK and in the event payload. */
    public String wire() {
        return wire;
    }

    public static HealthState fromWire(String wire) {
        for (HealthState state : values()) {
            if (state.wire.equals(wire)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown health state");
    }
}
