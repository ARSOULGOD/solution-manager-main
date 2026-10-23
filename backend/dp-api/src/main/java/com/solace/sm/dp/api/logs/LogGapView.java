package com.solace.sm.dp.api.logs;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * A window of a host-local agent's collected log that is missing, so a page never reads as continuous where it is not
 * (plan 016, spec section 3: a gap is never silently skipped). {@code reason} is always one of the closed vocabulary, never
 * text the agent chose: anything else is {@value #UNKNOWN} (I6).
 */
public record LogGapView(Instant from, Instant to, String reason) {

    /** The agent's closed gap vocabulary. */
    public static final Set<String> REASONS =
            Set.of("DROPPED_OLDEST", "COLLECTION_PAUSED", "CONTAINER_UNREADABLE", "CAP_REDUCED", "OVERSIZE");

    /** What any other reason becomes. */
    public static final String UNKNOWN = "UNKNOWN";

    public LogGapView {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
        if (!REASONS.contains(reason) && !UNKNOWN.equals(reason)) {
            throw new IllegalArgumentException("a gap reason is one of the closed vocabulary or " + UNKNOWN);
        }
    }

    /** A gap whose reason came from outside: kept if it is in the closed vocabulary, else {@link #UNKNOWN}. */
    public static LogGapView of(Instant from, Instant to, String reason) {
        return new LogGapView(from, to, reason != null && REASONS.contains(reason) ? reason : UNKNOWN);
    }
}
