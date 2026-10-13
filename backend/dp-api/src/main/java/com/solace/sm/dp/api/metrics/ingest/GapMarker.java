package com.solace.sm.dp.api.metrics.ingest;

import java.time.Instant;
import java.util.Objects;

/**
 * A producer's statement that it saw nothing between two instants. Must be empty from {@code DP_POLL} in v1: the data
 * plane derives its own gaps from the samples it holds (discovery default D-3), and a producer's marker would be a
 * second, unchecked claim about the same thing.
 */
public record GapMarker(Instant from, Instant to, String reason) {

    public GapMarker {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
    }
}
