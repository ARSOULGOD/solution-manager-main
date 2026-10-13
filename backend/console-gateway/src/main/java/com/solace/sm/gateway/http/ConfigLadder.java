package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ConfigLadder} ({@code Versioned} + {@code rungs} + {@code reached} +
 * {@code placements}).
 *
 * <p>All five rungs are always listed, in ladder order; a rung that is not reported has {@code present: false} and
 * the response's {@code availability} says why. {@code placements} is omitted (it is schema-optional) until per-
 * placement rungs exist: a per-placement answer derived from the instance's would claim a fact nobody observed.
 */
public record ConfigLadder(long stateVersion, @WireOptional Availability availability, List<Rung> rungs,
        String reached, @WireOptional List<PlacementRung> placements) {

    public ConfigLadder {
        rungs = List.copyOf(Objects.requireNonNull(rungs, "rungs"));
        Objects.requireNonNull(reached, "reached");
        placements = placements == null ? null : List.copyOf(placements);
    }

    public record Rung(String rung, boolean present, @WireOptional Long generation,
            @WireOptional String configHash) {
    }

    public record PlacementRung(java.util.UUID placementId, String reached) {
    }
}
