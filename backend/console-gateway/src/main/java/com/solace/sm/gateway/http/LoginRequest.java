package com.solace.sm.gateway.http;

/** Request body for the OpenAPI {@code login} operation. */
public record LoginRequest(String username, String credential) {

    public LoginRequest {
        requireLength(username, 1, 256, "username");
        requireLength(credential, 1, Integer.MAX_VALUE, "credential");
    }

    static void requireLength(String value, int minimum, int maximum, String field) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(field + " length must be between " + minimum + " and " + maximum);
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", credential=[REDACTED]]";
    }
}
