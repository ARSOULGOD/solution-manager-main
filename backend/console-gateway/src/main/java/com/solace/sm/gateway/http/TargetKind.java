package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;

/** The {@code Target.kind} vocabulary: the MI runtime SM manages (Q-C3 (a)), as distinct from the transport. */
public enum TargetKind {
    DOCKER("docker"),
    SYSTEMD("systemd"),
    KUBERNETES("kubernetes"),
    VM("vm");

    private final String wireValue;

    TargetKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    static TargetKind fromWire(String wireValue) {
        for (TargetKind kind : values()) {
            if (kind.wireValue.equals(wireValue)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown target kind");
    }
}
