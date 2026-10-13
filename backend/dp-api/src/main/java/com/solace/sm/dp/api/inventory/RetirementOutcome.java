package com.solace.sm.dp.api.inventory;

/** How a retirement record was closed: it finished, or the sweep gave up on it and the instance is active again. */
public enum RetirementOutcome {
    RETIRED("retired"), ROLLED_BACK("rolled-back");

    private final String wire;

    RetirementOutcome(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static RetirementOutcome fromWire(String wire) {
        for (RetirementOutcome o : values()) {
            if (o.wire.equals(wire)) {
                return o;
            }
        }
        throw new IllegalArgumentException("unknown retirement outcome");
    }
}
