package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;

/** The {@code Availability.mode} vocabulary; the wire values are the contract's lower-case ones. */
public enum AvailabilityMode {
    EVENTS("events"),
    POLLING("polling");

    private final String wireValue;

    AvailabilityMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
