package com.solace.sm.dp.api.health;

import java.util.List;
import java.util.Objects;

/**
 * An instance's health, per live placement. No top-level state or version ({@code OR-39}, {@code OR-55}).
 *
 * @param placements         the live placements that have been polled, each with its own state and version
 * @param unpolledPlacements live placements nothing has polled yet: the surface says so rather than staying silently
 *                           short ("a degraded surface is declared, never silently empty")
 */
public record HealthView(List<PlacementHealthView> placements, int unpolledPlacements) {

    public HealthView {
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        if (unpolledPlacements < 0) {
            throw new IllegalArgumentException("unpolledPlacements must not be negative");
        }
    }
}
