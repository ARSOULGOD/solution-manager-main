package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ScopeNodeList} ({@code AvailabilityOnly} + {@code items}).
 *
 * <p>⚠️ Carries no {@code stateVersion} (ruling {@code OR-38}): non-entity responses are exempt,
 * as {@code Identity} already is.
 */
public record ScopeNodeList(@WireOptional Availability availability, List<ScopeNode> items) {

    public ScopeNodeList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
