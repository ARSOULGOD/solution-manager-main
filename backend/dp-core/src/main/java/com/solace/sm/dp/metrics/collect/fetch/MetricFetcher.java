package com.solace.sm.dp.metrics.collect.fetch;

import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One way of reading a placement's metric documents and its restart token (plan 011 P11-4.2). There are exactly two,
 * chosen per INSTANCE at declare (M-Q2, {@code OR-71}): {@link DirectHttpFetcher} and the SSH-to-loopback fetcher. A
 * failure of one is never retried through the other: there is no automatic fallback.
 *
 * <p>Neither method throws for a host, network or grammar problem: each answers in a result the collector counts as a
 * gap, never a zero.
 */
public interface MetricFetcher {

    /** One document. A request outside the path/query grammar is refused before any I/O ({@code fetch.refused}). */
    FetchResult fetch(FetchRequest request);

    /**
     * One start-time read per placement per cycle (OQ-1 (a), M-Q9): direct mode reads the component's declared
     * {@code restartSignal}; SSH mode reads {@code docker inspect}'s id and {@code StartedAt}.
     */
    RestartRead restartToken(RestartRequest request);

    /** Called by the collector's loop once per tick: release what the fetcher holds idle. Nothing, by default. */
    default void housekeeping() {
    }

    /**
     * Fetches every DISTINCT request once and answers each: metrics that share a URL share the fetch (Cache: four
     * metrics, one {@code /api/cache/stats}). Equality is {@link FetchRequest}'s, so key order does not matter.
     */
    default Map<FetchRequest, FetchResult> fetchAll(Collection<FetchRequest> requests) {
        Map<FetchRequest, FetchResult> answers = new LinkedHashMap<>();
        for (FetchRequest request : requests) {
            if (!answers.containsKey(request)) {
                answers.put(request, fetch(request));
            }
        }
        return answers;
    }

    /**
     * @param placement the placement being read
     * @param targetId  the placement's target (direct mode reads its registered address, fresh, from it)
     * @param signal    the declared {@code restartSignal} with its HOST port, or {@code null} when the component
     *                  declares none; ignored in SSH mode, where the signal is the container's own
     */
    record RestartRequest(PlacementRef placement, UUID targetId, Signal signal) {
        public RestartRequest {
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(targetId, "targetId");
        }

        /** A declared {@code restartSignal}: an http_json read of a process start time. */
        public record Signal(int hostPort, String path, String pointer) {
        }
    }

    /**
     * The restart token read this cycle. The collector (not the fetcher) derives the source's detection from its
     * declaration and mode (M-Q9): a source with a declared signal whose read {@link Failed} is a failed signal read,
     * counted {@code scrape_failed}, never an inferred reset.
     */
    sealed interface RestartRead permits RestartRead.Token, RestartRead.NotDeclared, RestartRead.Failed {

        /** @param value an opaque string compared for equality; never logged as a measurement */
        record Token(String value) implements RestartRead {
            public Token {
                Objects.requireNonNull(value, "value");
            }
        }

        /** Direct mode, and the component declares no {@code restartSignal}: detection is inferred. */
        record NotDeclared() implements RestartRead {
        }

        /** A signal exists and could not be read this cycle. */
        record Failed(String classifier) implements RestartRead {
            public Failed {
                Objects.requireNonNull(classifier, "classifier");
            }
        }
    }
}
