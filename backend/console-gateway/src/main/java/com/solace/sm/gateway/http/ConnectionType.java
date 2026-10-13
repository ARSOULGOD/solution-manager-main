package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ConnectionType} (plan 013 T4, {@code UI-D27}). Mirrors
 * {@code contracts/connection-types/*.json} field for field, so {@link ConnectionTypeCatalog} deserializes those
 * files directly into this record and serves them byte-for-byte equivalent (never reshaped).
 */
public record ConnectionType(String connectionType, String label, int descriptorVersion,
                             List<ConnectionTypeAttribute> attributes) {

    public ConnectionType {
        Objects.requireNonNull(connectionType, "connectionType");
        Objects.requireNonNull(label, "label");
        attributes = List.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
