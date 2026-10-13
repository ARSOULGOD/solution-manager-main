package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Request body of {@code scanHostKeys} (console.yaml {@code HostKeyScanRequest}: {@code required: [host,
 * ownershipScope]}, {@code additionalProperties: false}). A structural fault is {@code 400}; the host and port GRAMMAR is
 * the data plane's ({@code TargetConnectionValidator}, the one place it lives) and is {@code 422}.
 *
 * <p>There is no organization in the body ({@code I5}): the scope names a node, and {@link OwnershipScopeResolver} has
 * already authorized {@code target.create} at it.
 */
public record HostKeyScanRequest(String host, Integer port, ScopeRef ownershipScope) {

    /** The SSH port when none is given. */
    public static final int DEFAULT_PORT = 22;

    public HostKeyScanRequest {
        if (host == null || host.isEmpty() || host.length() > 254) {
            throw new IllegalArgumentException("host is required and at most 254 characters");
        }
        if (ownershipScope == null) {
            throw new IllegalArgumentException("ownershipScope is required");
        }
    }

    public int portOrDefault() {
        return port == null ? DEFAULT_PORT : port;
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
