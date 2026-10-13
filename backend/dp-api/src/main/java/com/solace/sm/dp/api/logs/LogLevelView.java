package com.solace.sm.dp.api.logs;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One placement's log level for one component (OR-103). {@code liveLevel} is what the running process reports.
 * {@code configuredLevel} is the level the configuration of record (the instance's latest generation) sets for the
 * logger, empty when it sets none (T9.4); it may differ from {@code liveLevel} until the service restarts.
 * {@code runtimeOverride} is {@code false} under OR-103's option Rd: SM has no live write, so it can never have caused one.
 *
 * <p>Availability is never silent: a state other than {@code LIVE} carries the reason, and the reason is fixed text that
 * quotes nothing the host returned.
 */
public record LogLevelView(UUID placementId, String component, Optional<LoggerLevel> liveLevel,
        Optional<LoggerLevel> configuredLevel, boolean runtimeOverride, Availability availability) {

    public enum State { LIVE, DEGRADED, UNAVAILABLE }

    /** @param reason required unless {@code state} is {@code LIVE} */
    public record Availability(State state, String reason) {
        public Availability {
            Objects.requireNonNull(state, "state");
            if (state != State.LIVE && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("a reason is required for a non-live availability");
            }
        }
    }

    public LogLevelView {
        Objects.requireNonNull(placementId, "placement");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(liveLevel, "live level");
        Objects.requireNonNull(configuredLevel, "configured level");
        Objects.requireNonNull(availability, "availability");
    }
}
