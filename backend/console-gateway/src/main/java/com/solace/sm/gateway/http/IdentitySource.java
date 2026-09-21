package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;

/** The {@code Identity.source} vocabulary. */
public enum IdentitySource {
    LOCAL("local"),
    IDP("idp");

    private final String wireValue;

    IdentitySource(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
