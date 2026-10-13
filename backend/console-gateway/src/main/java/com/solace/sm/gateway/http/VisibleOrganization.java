package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code VisibleOrganization} — one organization the caller can see,
 * with the sub-organizations visible beneath it (ruling {@code OR-116}).
 */
public record VisibleOrganization(UUID id, String name, List<ScopeNode> subOrganizations) {

    public VisibleOrganization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        subOrganizations = List.copyOf(Objects.requireNonNull(subOrganizations, "subOrganizations"));
    }
}
