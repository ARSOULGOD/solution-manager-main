package com.solace.sm.dp.api.agw;

import java.util.Objects;
import java.util.UUID;

/** The DP's identifier of one agent command; the wire carries it as a string. */
public record CommandId(UUID value) {
    public CommandId {
        Objects.requireNonNull(value, "command id");
    }

    /** A malformed wire id is a command the DP never issued: {@link GatewayCode#UNKNOWN_COMMAND}. */
    public static CommandId parse(String wire) {
        try {
            return new CommandId(UUID.fromString(wire));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GatewayPortException(GatewayCode.UNKNOWN_COMMAND, "unknown command");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
