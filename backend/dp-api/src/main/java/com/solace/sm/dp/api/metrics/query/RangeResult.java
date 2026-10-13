package com.solace.sm.dp.api.metrics.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answer to a range or an instant query: the console contract's {@code MetricQueryResult}, in Java.
 *
 * @param gapThresholdSeconds the served tier's threshold: 2 times the raw interval (never less than the bucket), or 2
 *                            times the rollup bucket width (DMR-21)
 */
public record RangeResult(Metric metric, Render render, Served resolution, Coverage coverage, int gapThresholdSeconds,
        List<SeriesView> series, Partial partial) {

    public RangeResult {
        series = List.copyOf(series);
    }

    public enum MetricType { COUNTER, GAUGE }

    public enum Transform { NONE, RATE, DELTA }

    public enum Tier { RAW, HOURLY, DAILY }

    public record Metric(String name, MetricType metricType, String unit) {
    }

    /** Server-decided from the type: a counter is never shipped as a raw climb (SM-D31). */
    public record Render(Transform transform, Integer rateSeconds) {
    }

    public record Served(RangeQuery.Resolution requested, Tier served, int bucketSeconds) {
    }

    /**
     * @param provisionalFrom set when the right edge of a rollup-tier range is stitched from raw samples that have not
     *                        finished rolling up (discovery default D-6): everything from here on is provisional
     */
    public record Coverage(Instant requestedFrom, Instant servedFrom, boolean truncatedByRetention, Instant provisionalFrom) {
    }

    /** Only instances the caller may see (the instance ids are reach-filtered before they reach the data plane). */
    public record Partial(boolean degraded, List<Unavailable> unavailable) {
        public Partial {
            unavailable = List.copyOf(unavailable);
        }
    }

    public record Unavailable(UUID instanceId, String label, Reason reason) {
    }

    public enum Reason { NOT_COLLECTING, COLLECTOR_DEGRADED }
}
