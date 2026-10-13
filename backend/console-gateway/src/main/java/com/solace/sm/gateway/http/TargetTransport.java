package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;

/** The {@code Target.transport} vocabulary: how SM reaches the target. v1 has two (plan 015 T3.5): SSH, and an enrolled host agent. */
public enum TargetTransport {
    SSH_DOCKER("ssh-docker"),
    SSH("ssh"),
    AGENT("agent");

    private final String wireValue;

    TargetTransport(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    static TargetTransport fromWire(String wireValue) {
        for (TargetTransport transport : values()) {
            if (transport.wireValue.equals(wireValue)) {
                return transport;
            }
        }
        throw new IllegalArgumentException("unknown target transport");
    }
}
