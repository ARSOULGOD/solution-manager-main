package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Objects;

/**
 * A window of the host's log the agent could not keep or read. {@code reason} is one of the agent's closed vocabulary
 * ({@code DROPPED_OLDEST}, {@code COLLECTION_PAUSED}, {@code CONTAINER_UNREADABLE}, {@code CAP_REDUCED}, the reserved
 * {@code OVERSIZE}); the gateway passes it through and a repeated marker across consecutive chunks is possible (plan 016, spec 4.1).
 */
public record AgentLogGap(Instant from, Instant to, String reason) {
    public AgentLogGap {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
    }
}
