package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code InstanceList} ({@code AvailabilityOnly} + {@code items}).
 *
 * <p>⚠️ Carries no {@code stateVersion} (§6.1's collection rule, {@code OR-55}): each {@link Instance} item carries its own.
 */
public record InstanceList(@WireOptional Availability availability, List<Instance> items) {

    public InstanceList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
