package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code TargetList} ({@code AvailabilityOnly} + {@code items}).
 *
 * <p>⚠️ Carries no {@code stateVersion} (ruling {@code OR-38}): a list's versions are per item, and a target has none.
 */
public record TargetList(@WireOptional Availability availability, List<Target> items) {

    public TargetList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
