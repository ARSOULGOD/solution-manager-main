package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;

/** The {@code Availability.state} vocabulary; the wire values are the contract's lower-case ones. */
public enum AvailabilityState {
    LIVE("live"),
    DEGRADED("degraded"),
    UNAVAILABLE("unavailable");

    private final String wireValue;

    AvailabilityState(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
