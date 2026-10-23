package com.solace.sm.dp.api.logs;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A page of log lines, or the declared reason there are none to give. {@code unavailableReason} is fixed text (never a
 * host, path, container name or exception message): present means the host could not be read and {@code lines} is
 * empty; empty means the host was read. For a host-local agent's page, {@code gaps} are the windows its collected log is missing
 * and {@code truncated} says a line was cut by the agent; an SSH page has neither.
 */
public record LogPageView(List<LogLineView> lines, Optional<String> unavailableReason, List<LogGapView> gaps,
        boolean truncated) {

    /** A page with no gaps and no truncated line (every SSH page). */
    public LogPageView(List<LogLineView> lines, Optional<String> unavailableReason) {
        this(lines, unavailableReason, List.of(), false);
    }

    public LogPageView {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
        Objects.requireNonNull(unavailableReason, "unavailableReason");
        if (unavailableReason.isPresent() && (unavailableReason.get().isBlank() || !lines.isEmpty())) {
            throw new IllegalArgumentException("an unavailable page has a reason and no lines");
        }
        if (unavailableReason.isPresent() && (!gaps.isEmpty() || truncated)) {
            throw new IllegalArgumentException("an unavailable page has no gaps and no truncated line");
        }
    }
}
