package com.solace.sm.dp.api.inventory;

import java.util.Objects;

/**
 * Browser-named fields. {@code reserve} refuses ({@link PlacementOutsideEnvelopeException}, 422) any outside the
 * target's envelope (B2). {@code probe} is null when the package declares no HTTP probe.
 */
public record PlacementSpec(String containerName, String configDirectory, String containerConfigPath,
        String fileName, HealthProbeSpec probe) {

    public PlacementSpec {
        Objects.requireNonNull(containerName, "containerName");
        Objects.requireNonNull(configDirectory, "configDirectory");
        Objects.requireNonNull(containerConfigPath, "containerConfigPath");
        Objects.requireNonNull(fileName, "fileName");
    }
}
