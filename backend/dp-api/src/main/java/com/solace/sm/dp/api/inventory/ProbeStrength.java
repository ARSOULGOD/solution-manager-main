package com.solace.sm.dp.api.inventory;

import java.util.Locale;

/** The package's declared probe strength (T0.1a, owner ruling 2026-09-29 (S0-10)): what a health gate can prove. */
public enum ProbeStrength {
    READINESS, LIVENESS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ProbeStrength fromWire(String wire) {
        for (ProbeStrength s : values()) {
            if (s.wire().equals(wire)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown probe strength");
    }
}
