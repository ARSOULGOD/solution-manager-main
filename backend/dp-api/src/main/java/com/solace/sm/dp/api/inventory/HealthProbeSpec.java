package com.solace.sm.dp.api.inventory;

import java.util.Objects;

/**
 * From the package's {@code health.probe} (gateway-resolved at declare, M8) plus the host-side port. {@code kind} is one
 * of the two HTTP kinds ({@code actuator-health}, {@code http-status}); a package that declares no HTTP probe passes
 * {@code null} for the whole spec. {@code strength} is the package's declared probe strength (T0.1a).
 */
public record HealthProbeSpec(String kind, String path, int hostPort, ProbeStrength strength) {

    public HealthProbeSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(strength, "strength");
    }
}
