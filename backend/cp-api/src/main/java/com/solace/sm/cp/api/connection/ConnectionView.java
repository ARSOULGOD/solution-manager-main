package com.solace.sm.cp.api.connection;

import com.solace.sm.cp.api.authz.ScopePath;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A Connection config object (plan 013 T2, {@code BE-D66}, {@code OR-130}): the cp-owned half of the
 * cp/dp split. {@code attributes} never carries a secret-typed value ({@code I6}) — the caller (the
 * layer that knows the connection type's descriptor, Task 3/4) is responsible for splitting secret
 * attributes out before they ever reach {@code cp-core}; this record, and the repository behind it,
 * persist exactly what they are given and no more.
 *
 * <p>{@code secretStatus} is presence metadata only ({@code key -> set}), never a value.
 */
public record ConnectionView(UUID connectionId, String name, String connectionType, int descriptorVersion,
        ScopePath ownershipScope, Map<String, Object> attributes, Map<String, Boolean> secretStatus, boolean active,
        int usedByCount, long revision, Instant createdAt, Instant updatedAt) {

    public ConnectionView {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(connectionType, "connectionType");
        Objects.requireNonNull(ownershipScope, "ownershipScope");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        secretStatus = Map.copyOf(Objects.requireNonNull(secretStatus, "secretStatus"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
