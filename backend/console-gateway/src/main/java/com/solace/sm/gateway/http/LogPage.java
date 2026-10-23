package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code LogPage} ({@code AvailabilityOnly} + {@code lines}). No {@code stateVersion}
 * (OR-38). {@code availability} is omitted when the host was read, and {@code unavailable} with its reason when it could
 * not be: an empty {@code lines} with no availability means the container wrote nothing in the window, never that the
 * host was unreachable. {@code gaps} and {@code truncated} are declared only for an agent's page that has them (absent otherwise).
 */
public record LogPage(@WireOptional Availability availability, List<Line> lines, @WireOptional List<Gap> gaps,
        @WireOptional Boolean truncated) {

    public LogPage {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        gaps = gaps == null || gaps.isEmpty() ? null : List.copyOf(gaps);
        truncated = Boolean.TRUE.equals(truncated) ? Boolean.TRUE : null;
    }

    /** A page that declares no gaps and no truncated line (every SSH page). */
    public LogPage(Availability availability, List<Line> lines) {
        this(availability, lines, null, null);
    }

    /** A window of an agent's collected log that is missing; {@code reason} is from the closed vocabulary or {@code UNKNOWN}. */
    public record Gap(String from, String to, String reason) {

        public Gap {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** {@code at} is RFC 3339 in UTC at the container runtime's precision; {@code level} only when parseable. */
    public record Line(String at, @WireOptional String level, String message) {

        public Line {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(message, "message");
        }
    }
}
