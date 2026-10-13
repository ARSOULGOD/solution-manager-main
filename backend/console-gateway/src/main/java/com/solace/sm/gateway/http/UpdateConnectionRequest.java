package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code UpdateConnectionRequest} (plan 013 T4, {@code BE-D66}). {@code null} fields
 * mean "leave unchanged"; {@code attributes} is merged new-over-existing by {@code ConnectionService}, not
 * replaced wholesale. {@code connectionType} and any id are not properties here — Spring Boot disables Jackson's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, so the any-setter that refuses is what makes the contract's
 * {@code additionalProperties: false} real ({@code ScopeRef} does the same): a stray key is {@code 400}, never a
 * silently ignored claim.
 */
public record UpdateConnectionRequest(@WireOptional String name, @WireOptional Map<String, Object> attributes,
                                      @WireOptional Boolean testBeforeSave) {

    public UpdateConnectionRequest {
        attributes = attributes == null ? null : Map.copyOf(attributes);
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    boolean testBeforeSaveOrDefault() {
        return testBeforeSave != null && testBeforeSave;
    }
}
