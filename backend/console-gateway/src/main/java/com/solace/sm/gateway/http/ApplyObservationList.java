package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ApplyObservationList} ({@code AvailabilityOnly} + {@code items}), ascending by
 * each item's {@code stateVersion}. ⚠️ No {@code stateVersion} of its own (§6.1's collection rule, {@code OR-55}).
 */
public record ApplyObservationList(@WireOptional Availability availability, List<ApplyObservation> items) {

    public ApplyObservationList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
