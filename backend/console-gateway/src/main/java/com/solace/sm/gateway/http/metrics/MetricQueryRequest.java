package com.solace.sm.gateway.http.metrics;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The body of {@code queryRange} and {@code queryInstant}: the contract's {@code MetricQueryRequest}, {@code
 * additionalProperties: false} at every level (a stray key is {@code 400}, not a silently ignored claim; Spring Boot turns
 * Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so an any-setter that refuses is what makes it real).
 *
 * <p>Structural faults are {@code 400} (the constructor's {@code IllegalArgumentException} reaches the advice as an
 * unreadable body): two metric names (a list where a string is), a missing field, a wrong enum, a label or a
 * {@code groupBy} key (the v1 allowlist is empty, D-10). Whether the request is COHERENT, and answerable, is the data
 * plane's and a {@code 422}.
 *
 * <p>⛔ There is no organization: the tenant is the session's (I5). {@code instanceIds} are the caller's CLAIM, filtered by
 * reach before they go anywhere.
 */
public record MetricQueryRequest(Integer queryVersion, String metric, Selector selector, Range range, String resolution,
        String seriesBy, Aggregation aggregation, List<String> groupBy) {

    static final Pattern METRIC_NAME = Pattern.compile("[a-z][a-z0-9_.]{0,127}");
    /** A sane bound on a selection: the series cap is 10, so more than this is not a chart. */
    static final int MAX_INSTANCE_IDS = 1000;

    private static final Set<String> RESOLUTIONS = Set.of("auto", "raw");
    private static final Set<String> SERIES_BY = Set.of("instance", "replica");
    private static final Set<String> ACROSS_REPLICAS = Set.of("auto", "none", "sum", "avg", "min", "max");
    private static final Set<String> ACROSS_INSTANCES = Set.of("none", "sum", "avg", "min", "max");

    public MetricQueryRequest {
        require(queryVersion != null && queryVersion == 1, "queryVersion");
        require(metric != null && METRIC_NAME.matcher(metric).matches(), "metric");
        require(selector != null && range != null && aggregation != null, "selector, range and aggregation");
        require(RESOLUTIONS.contains(resolution), "resolution");
        require(SERIES_BY.contains(seriesBy), "seriesBy");
        // The v1 label allowlist is empty (D-10): a groupBy key names nothing.
        require(groupBy == null || groupBy.isEmpty(), "groupBy");
        groupBy = List.of();
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    public boolean raw() {
        return "raw".equals(resolution);
    }

    /** {@code entityType} is the declaring component type; {@code labels} must be empty in v1. */
    public record Selector(String entityType, List<UUID> instanceIds, Map<String, String> labels) {

        public Selector {
            require(entityType != null && !entityType.isEmpty() && entityType.length() <= 128, "selector.entityType");
            require(instanceIds != null && !instanceIds.isEmpty() && instanceIds.size() <= MAX_INSTANCE_IDS
                    && !instanceIds.contains(null), "selector.instanceIds");
            // Order kept, a repeated id counted once.
            instanceIds = List.copyOf(new LinkedHashSet<>(instanceIds));
            require(labels != null && labels.isEmpty(), "selector.labels");
            labels = Map.of();
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    /** {@code from} inclusive, {@code to} exclusive; ISO-8601 date-times. */
    public record Range(String from, String to) {

        public Range {
            require(from != null && to != null, "range");
            Instant lower = parse(from);
            Instant upper = parse(to);
            require(upper.isAfter(lower), "range");
        }

        public Instant fromInstant() {
            return parse(from);
        }

        public Instant toInstant() {
            return parse(to);
        }

        private static Instant parse(String text) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("range is not an ISO-8601 date-time");
            }
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    /** {@code acrossReplicas}: see {@code QueryComposer} for what v1 applies. */
    public record Aggregation(String acrossReplicas, String acrossInstances) {

        public Aggregation {
            require(ACROSS_REPLICAS.contains(acrossReplicas), "aggregation.acrossReplicas");
            require(ACROSS_INSTANCES.contains(acrossInstances), "aggregation.acrossInstances");
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(what + " is missing or invalid");
        }
    }
}
