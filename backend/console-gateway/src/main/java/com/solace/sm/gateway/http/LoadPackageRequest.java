package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body of {@code loadPackage}: {@code {"package": {...}}} and nothing else ({@code additionalProperties:
 * false}).
 *
 * <p>⛔ <b>No {@code organizationId}, and no other key, is accepted.</b> The tenant is {@code
 * Session.organizationId()} ({@code I5}); {@link ResourceResolver} never reads a body, so a body naming another
 * organization could never have been honoured. Spring Boot disables Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * globally and {@code @JsonIgnoreProperties(ignoreUnknown = false)} cannot re-enable it, so an any-setter that
 * refuses is what makes the contract's {@code additionalProperties: false} real: a stray key is a {@code 400}, not
 * a silently ignored claim.
 */
public record LoadPackageRequest(@JsonProperty("package") JsonNode pkg) {

    public LoadPackageRequest {
        if (pkg == null || !pkg.isObject()) {
            throw new IllegalArgumentException("package must be a JSON object");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
