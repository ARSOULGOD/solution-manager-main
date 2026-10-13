package com.solace.sm.dp.api.config;

/**
 * Where a generation came from (OR-33). The wire spellings are the {@code config_generation.origin} CHECK's
 * closed set; {@link #wire()} is the one place they are written.
 */
public enum GenerationOrigin {
    EDIT("edit"),
    ROLLBACK("rollback"),
    RESTORE_RUNTIME("restore-runtime"),
    ACCEPT_RUNTIME("accept-runtime");

    private final String wire;

    GenerationOrigin(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static GenerationOrigin fromWire(String wire) {
        for (GenerationOrigin o : values()) {
            if (o.wire.equals(wire)) {
                return o;
            }
        }
        throw new IllegalArgumentException("unknown generation origin");
    }
}
