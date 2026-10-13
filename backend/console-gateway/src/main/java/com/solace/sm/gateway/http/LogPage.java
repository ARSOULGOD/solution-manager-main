package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code LogPage} ({@code AvailabilityOnly} + {@code lines}). No {@code stateVersion}
 * (OR-38). {@code availability} is omitted when the host was read, and {@code unavailable} with its reason when it could
 * not be: an empty {@code lines} with no availability means the container wrote nothing in the window, never that the
 * host was unreachable.
 */
public record LogPage(@WireOptional Availability availability, List<Line> lines) {

    public LogPage {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /** {@code at} is RFC 3339 in UTC at the container runtime's precision; {@code level} only when parseable. */
    public record Line(String at, @WireOptional String level, String message) {

        public Line {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(message, "message");
        }
    }
}
