package com.solace.sm.dp.metrics.export;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The persistence port of the export (plan 011 P11-6.2). JDK types only, so the service holds no JDBC. Every method names the
 * tenant ({@code I5}) and binds every value; the implementation lives in {@code dp.apply.persistence}.
 */
public interface ExportRows {

    /**
     * The series the export would read, each with the intervals in force: one bound read of {@code series} and
     * {@code series_segment}, no raw rows. Segments are in {@code effectiveFrom} order.
     */
    List<SeriesSpan> spans(UUID tenantId, String metric, List<UUID> instanceIds);

    /**
     * W3-12: how many raw rows these series hold in {@code [from, to)}, counted by one bound read over the
     * {@code (series_id, ts)} primary key, and STOPPED at {@code atMost} (so a huge series costs at most that many index
     * entries, never a full scan). For a series with no declared interval (the push path), whose row count an interval cannot
     * project.
     *
     * @param statementTimeout applied to the transaction only
     * @return the count, never above {@code atMost}
     */
    long countRaw(UUID tenantId, List<Long> seriesIds, Instant from, Instant to, long atMost, Duration statementTimeout);

    /**
     * Streams the tenant's raw rows of {@code metric} for the given instances in {@code [from, to)} to {@code sink}, one at a
     * time, from a server-side cursor inside a read-only transaction, ordered by series then time. Nothing is collected: the
     * rows are handed over as they arrive.
     *
     * @param statementTimeout applied to the transaction only ({@code SET LOCAL} semantics)
     * @param fetchSize        rows per round trip; must be above 0
     * @return rows handed to the sink
     * @throws IOException whatever the sink threw (the client went away)
     */
    long stream(UUID tenantId, String metric, List<UUID> instanceIds, Instant from, Instant to, Duration statementTimeout,
            int fetchSize, Sink sink) throws IOException;

    /** One series and the intervals declared for it over time. */
    record SeriesSpan(long seriesId, List<Segment> segments) {
        public SeriesSpan {
            segments = List.copyOf(segments);
        }
    }

    /**
     * The interval in force from {@code effectiveFrom} until the next segment's start. {@code intervalSeconds} is null on
     * the push path (nothing was declared).
     */
    record Segment(Instant effectiveFrom, Integer intervalSeconds) {
    }

    /** Receives one raw row. */
    @FunctionalInterface
    interface Sink {
        void row(UUID instanceId, UUID placementId, Instant ts, double value) throws IOException;
    }
}
