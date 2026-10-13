package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code SolutionList} ({@code AvailabilityOnly} + {@code items}). Carries no
 * collection-level {@code stateVersion}: a collection carries versions per item ({@code OR-55}, §6.1).
 */
public record SolutionList(@WireOptional Availability availability, List<Solution> items) {

    public SolutionList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
