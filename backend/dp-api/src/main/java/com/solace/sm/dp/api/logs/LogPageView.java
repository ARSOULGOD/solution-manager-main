package com.solace.sm.dp.api.logs;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A page of log lines, or the declared reason there are none to give. {@code unavailableReason} is fixed text (never a
 * host, path, container name or exception message): present means the host could not be read and {@code lines} is
 * empty; empty means the host was read.
 */
public record LogPageView(List<LogLineView> lines, Optional<String> unavailableReason) {

    public LogPageView {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(unavailableReason, "unavailableReason");
        if (unavailableReason.isPresent() && (unavailableReason.get().isBlank() || !lines.isEmpty())) {
            throw new IllegalArgumentException("an unavailable page has a reason and no lines");
        }
    }
}
