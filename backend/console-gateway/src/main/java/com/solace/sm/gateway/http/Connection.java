package com.solace.sm.gateway.http;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code Connection} (plan 013 T4, {@code BE-D66}, {@code OR-130}).
 *
 * <p>⛔ {@code I6}: {@code attributes} never carries a secret-typed value — a secret attribute reaches here only as
 * {@code { "set": true }}-style redaction ({@link ConnectionWire#redact}); a client never receives the value it wrote.
 */
public record Connection(UUID connectionId, String name, String connectionType, ScopePath ownershipScope,
                         Map<String, Object> attributes, boolean active, int usedByCount,
                         @WireOptional String createdAt, @WireOptional String updatedAt) {

    public Connection {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(connectionType, "connectionType");
        Objects.requireNonNull(ownershipScope, "ownershipScope");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
