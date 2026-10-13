package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Request body of {@code setConfigSecret} (console.yaml: {@code required: [value, baseStateVersion]},
 * {@code additionalProperties: false}). A structural fault (a missing field, a stray key) is {@code 400}, exactly as
 * {@link StageConfigRequest}.
 *
 * <p>⛔ {@code I6}: {@code value} is the secret literal. It is write-only: it appears in no response, no exception
 * message and no log line, and this record never prints it. There is no organization and no instance in the body: the
 * tenant is the session's and the instance is the path's ({@code I5}).
 */
public record SetConfigSecretRequest(String value, Long baseStateVersion) {

    public SetConfigSecretRequest {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        if (baseStateVersion == null || baseStateVersion < 0) {
            throw new IllegalArgumentException("baseStateVersion is required and not negative");
        }
    }

    @Override
    public String toString() {
        return "SetConfigSecretRequest[value=[REDACTED], baseStateVersion=" + baseStateVersion + "]";
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
