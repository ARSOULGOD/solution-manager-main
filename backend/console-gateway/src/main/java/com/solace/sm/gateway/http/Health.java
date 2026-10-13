package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code Health} ({@code AvailabilityOnly} + {@code placements}). No top-level state or
 * version (OR-39, OR-55): health is placement-scoped and watermarked per placement. {@code state} is the notify
 * vocabulary, spelt as the contract spells it.
 *
 * <p>A live placement nothing has polled yet is not listed; {@code availability} says so (degraded, with the reason)
 * rather than the list being silently short.
 */
public record Health(@WireOptional Availability availability, List<PlacementHealth> placements) {

    public Health {
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
    }

    public record PlacementHealth(UUID placementId, String state, String checkedAt, long stateVersion) {

        public PlacementHealth {
            Objects.requireNonNull(placementId, "placementId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(checkedAt, "checkedAt");
        }
    }
}
