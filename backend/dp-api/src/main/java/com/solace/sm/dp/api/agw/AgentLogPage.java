package com.solace.sm.dp.api.agw;

import com.solace.sm.dp.api.logs.LogLineView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One answer to a page request. {@code unavailableReason} is a fixed code naming no host, path or container: the agent's
 * {@code NOT_COLLECTING}, {@code CONTAINER_MISSING}, {@code BUSY}, {@code PLACEMENT_NOT_IN_LEASE}, or the gateway's own
 * {@code NO_SESSION}, {@code NO_LOGS_CAPABILITY}, {@code TIMEOUT}. Present means {@code lines} is empty. Lines are oldest first.
 */
public record AgentLogPage(List<LogLineView> lines, List<AgentLogGap> gaps, Optional<String> unavailableReason) {
    public AgentLogPage {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
        Objects.requireNonNull(unavailableReason, "unavailableReason");
        if (unavailableReason.isPresent() && (unavailableReason.get().isBlank() || !lines.isEmpty())) {
            throw new IllegalArgumentException("an unavailable page has a reason and no lines");
        }
    }

    public static AgentLogPage unavailable(String reason) {
        return new AgentLogPage(List.of(), List.of(), Optional.of(reason));
    }
}
