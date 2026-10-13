package com.solace.sm.dp.metrics.collect.fetch;

import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One metric document to fetch from one placement (plan 011 P11-4.2, M-Q2).
 *
 * <p>It carries <b>no host</b>. In {@code direct-http} mode the address is the target's registered address, read fresh
 * from {@code target_connection} by the fetcher on every round ({@code OR-82}); in {@code via-ssh} mode the host is the
 * placement's SSH binding. Nothing about where the request goes can come from a browser (B2). {@code hostPort} is the
 * HOST-side port the placement's {@code placement_metrics_endpoint} mapping gives ({@code OR-74}), never the package's
 * container port.
 *
 * <p>{@code queryParams} is copied into a sorted map, so two requests for the same URL are {@code equals} whatever
 * order the package listed the keys in: that equality is what lets metrics that share a URL share one fetch
 * ({@link MetricFetcher#fetchAll}). The grammar of {@code path} and of every key and value is NOT checked here: it is
 * re-checked by the fetcher at the point of use, and a request outside it is refused before any I/O.
 */
public record FetchRequest(PlacementRef placement, UUID targetId, int hostPort, String path,
        Map<String, String> queryParams) {

    public FetchRequest {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(targetId, "targetId");
        queryParams = queryParams == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(queryParams));
    }

    /**
     * M-Q24 (8): a polled sample's {@code ts} is the UTC midpoint of request-sent and response-received. The caller
     * keeps the response time as {@code receivedAt}.
     */
    public static Instant midpoint(Instant sent, Instant received) {
        Objects.requireNonNull(sent, "sent");
        Objects.requireNonNull(received, "received");
        if (received.isBefore(sent)) {
            return sent;
        }
        return sent.plusNanos(java.time.Duration.between(sent, received).toNanos() / 2);
    }
}
