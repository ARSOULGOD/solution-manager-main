package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of one {@code Instance.placements[]} item. ⚠️ Present in the API by design ({@code OR-2}), and NOT a
 * grant level ({@code OR-16}). {@code probeStrength} is the placement's copy of the package's declared strength, or
 * {@code none}: a declaration, never a proof.
 *
 * <p>Plan 014: exactly one of {@code targetId} (a legacy Target) and {@code connectionId} (a remote-server Connection)
 * is present; the other is omitted from the wire, never sent as {@code null}.
 */
public record InstancePlacement(UUID placementId, @WireOptional UUID targetId, String containerName,
        String probeStrength, @WireOptional UUID connectionId) {

    public InstancePlacement {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(containerName, "containerName");
        Objects.requireNonNull(probeStrength, "probeStrength");
        if ((targetId == null) == (connectionId == null)) {
            throw new IllegalArgumentException("exactly one of targetId and connectionId is required");
        }
    }
}
