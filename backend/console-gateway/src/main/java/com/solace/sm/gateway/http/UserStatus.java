package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;

/** The {@code User.status} vocabulary. A user is deactivated, never deleted, and never reactivated over the API. */
public enum UserStatus {
    ACTIVE("active"),
    DEACTIVATED("deactivated");

    private final String wireValue;

    UserStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
