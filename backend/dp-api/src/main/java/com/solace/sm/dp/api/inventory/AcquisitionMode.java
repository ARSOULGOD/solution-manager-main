package com.solace.sm.dp.api.inventory;

import java.util.Locale;

/**
 * How an instance's health and metrics are acquired (owner rulings OR-71, OR-82): the MODE only. {@code DIRECT_HTTP}
 * polls the target's registered address ({@code target_connection.host}) at the placement's health port and path;
 * {@code VIA_SSH} reads through the target's SSH session. No address is stored per instance.
 *
 * <p>{@code AGENT} (plan 015 T3.5; V028's third member of the column's CHECK) is the mode of an instance an enrolled host agent
 * registered: its health and metrics arrive as the agent's reports. It is never chosen by an operator - a declare body naming it is
 * refused - and exists so that a reader of {@code instance_state.acquisition_mode} meets the value as a value, not as a parse failure
 * the first time an agent instance exists.
 */
public enum AcquisitionMode {
    DIRECT_HTTP, VIA_SSH, AGENT;

    /** The closed wire vocabulary ({@code direct-http}, {@code via-ssh}, {@code agent}) the database CHECK and the contract share. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static AcquisitionMode fromWire(String wire) {
        for (AcquisitionMode m : values()) {
            if (m.wire().equals(wire)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown acquisition mode");
    }
}
