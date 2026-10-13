package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code CreateConnectionRequest} (plan 013 T4, {@code BE-D66}). No
 * {@code organizationId} ({@code I5}): the organization is the session's. {@code attributes} may carry
 * secret-typed values (write-only, never echoed back on success, stripped from the echo on a failed
 * {@code testBeforeSave}).
 *
 * <p>The any-setter makes the contract's {@code additionalProperties: false} real (the {@code ScopeRef} pattern):
 * a stray top-level key is {@code 400}, never silently ignored.
 */
public record CreateConnectionRequest(String name, String connectionType, ScopeRef ownershipScope,
                                      Map<String, Object> attributes, @WireOptional Boolean testBeforeSave) {

    public CreateConnectionRequest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(connectionType, "connectionType");
        Objects.requireNonNull(ownershipScope, "ownershipScope");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    boolean testBeforeSaveOrDefault() {
        return testBeforeSave != null && testBeforeSave;
    }
}
