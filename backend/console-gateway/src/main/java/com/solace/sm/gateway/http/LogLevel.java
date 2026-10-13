package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code LogLevel} ({@code AvailabilityOnly} + component, placementId, liveLevel,
 * configuredLevel, runtimeOverride). {@code availability} is omitted for a live answer, as {@link Health}'s is.
 * {@code configuredLevel} is the configuration of record's, which T9.4 fills; until then it is omitted, never {@code null}.
 * {@code runtimeOverride} is {@code false} under OR-103: SM has no live write, so it cannot have caused one.
 */
public record LogLevel(@WireOptional Availability availability, String component, UUID placementId,
        @WireOptional String liveLevel, @WireOptional String configuredLevel, boolean runtimeOverride) {

    public LogLevel {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(placementId, "placementId");
    }
}
