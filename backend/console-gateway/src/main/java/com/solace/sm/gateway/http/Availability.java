package com.solace.sm.gateway.http;

import java.time.OffsetDateTime;
import java.util.Objects;

/** The per-surface availability object composed into versioned response models. */
public record Availability(AvailabilityState state, @WireOptional AvailabilityMode mode,
                           @WireOptional String reason, @WireOptional OffsetDateTime since) {

    public Availability {
        Objects.requireNonNull(state, "state");
        if (state != AvailabilityState.LIVE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason is required for non-live availability");
        }
    }
}
