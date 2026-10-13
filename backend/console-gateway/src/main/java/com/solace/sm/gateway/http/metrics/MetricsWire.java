package com.solace.sm.gateway.http.metrics;

import com.solace.sm.gateway.http.Availability;
import com.solace.sm.gateway.http.WireOptional;
import java.util.List;
import java.util.UUID;

/**
 * The response shapes of the monitoring routes, as {@code contracts/openapi/console.yaml} names them. Each nested record
 * mirrors one schema: {@code MetricsSchemaMirrorTest} pins component names and requiredness to the contract in both
 * directions, so a field added to the contract and not here (or the reverse) is a red build and not a client
 * that sees a surprise.
 *
 * <p>A schema-optional component is {@link WireOptional}: omitted, never serialized as {@code null}. A component the
 * contract makes required-and-nullable ({@code placementId}, {@code sourceKey}, a tier's {@code retention}) is NOT: it is
 * written as {@code null}.
 */
public final class MetricsWire {

    private MetricsWire() {
    }

    // ---- getCapabilities -----------------------------------------------------------------------------------------

    public record Capabilities(int queryVersion, List<Tier> tiers, String maxRange, int maxSeriesPerWidget,
            int maxPointsPerSeries, List<String> marks, List<String> aggregations, Features features,
            Collector collector) {
    }

    public record Tier(String name, String retention, int minBucketSeconds) {
    }

    public record Features(boolean forceRaw, boolean streamingExport, boolean pngExport) {
    }

    /** {@code runwayEnd} and {@code disk} are absent (not null on the wire) while unmeasured: OR-112 (6), W4-05. */
    public record Collector(String state, @WireOptional String runwayEnd, int unfrozenClosedPartitions,
            List<CollectorReasonCount> reasons, @WireOptional Disk disk) {
    }

    public record CollectorReasonCount(String reason, long last24h) {
    }

    public record Disk(String state) {
    }

    // ---- entity types and instances ------------------------------------------------------------------------------

    public record EntityType(String id, String label) {
    }

    public record MetricInstance(UUID id, String label, int replicaCount) {
    }

    public record MetricInstanceList(List<MetricInstance> items, int total) {
    }

    // ---- the catalog ---------------------------------------------------------------------------------------------

    public record MetricCatalogEntry(String name, String metricType, String unit, String layer, String state,
            @WireOptional String unsupportedReason, @WireOptional Long lastSeen, List<String> labelKeys,
            String durability, String onUnavailable, @WireOptional String restartDetection,
            @WireOptional EntryCollector collector, Availability observedAvailability) {
    }

    public record EntryCollector(String scope, List<CollectorReasonCount> reasons) {
    }

    // ---- query results -------------------------------------------------------------------------------------------

    public record QueryResult(int queryVersion, ResultMetric metric, Render render, Resolution resolution, Coverage coverage,
            int gapThresholdSeconds, List<ResultSeries> series, Partial partial) {
    }

    public record ResultMetric(String name, String metricType, String unit) {
    }

    public record Render(String transform, @WireOptional Integer rateSeconds) {
    }

    public record Resolution(String requested, String served, int bucketSeconds) {
    }

    public record Coverage(String requestedFrom, String servedFrom, boolean truncatedByRetention,
            @WireOptional String provisionalFrom) {
    }

    public record ResultSeries(SeriesRef seriesRef, String label, ReplicaComposition replicaComposition, String state,
            List<List<Object>> points, List<Gap> gaps) {
    }

    public record SeriesRef(UUID instanceId, UUID placementId, String sourceKey) {
    }

    public record ReplicaComposition(int total, int inactive) {
    }

    public record Gap(String from, String to, String reason) {
    }

    public record Partial(boolean degraded, List<Unavailable> unavailable) {
    }

    public record Unavailable(UUID instanceId, String label, String reason) {
    }
}
