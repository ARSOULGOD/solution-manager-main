package com.solace.sm.dp.api.metrics.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One metric over a range (the {@code MetricQueryRequest} of the console contract, minus what the gateway owns: the
 * session, the reach filter and the rate limit).
 *
 * @param metric           exactly one metric (DM-D23)
 * @param entityType       the declaring component type ({@code cache-engine}); filters instances to those whose package
 *                         declares {@code metric} on that component
 * @param instanceIds      already reach-filtered by the gateway (M-Q17)
 * @param labels           must be empty in v1 (the allowlist is empty, D-10)
 * @param acrossInstances  {@link Across#NONE} keeps one line per instance
 * @param groupBy          must be empty in v1; with {@link Across#NONE} it is {@link QueryRefusal#GROUP_BY_REQUIRES_AGGREGATION}
 */
public record RangeQuery(String metric, String entityType, List<UUID> instanceIds, Map<String, String> labels,
        Instant from, Instant to, Resolution resolution, SeriesBy seriesBy, Across acrossInstances, List<String> groupBy) {

    public enum Resolution { AUTO, RAW }

    public enum SeriesBy { INSTANCE, REPLICA }

    public enum Across { NONE, SUM, AVG, MIN, MAX }

    public RangeQuery {
        instanceIds = instanceIds == null ? List.of() : List.copyOf(instanceIds);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
    }
}
