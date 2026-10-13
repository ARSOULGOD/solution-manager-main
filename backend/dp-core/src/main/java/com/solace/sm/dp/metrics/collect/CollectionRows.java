package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads the plan builder needs that no earlier port gives it (plan 011 P11-4.3): a package version's definitions and
 * restart signals, a placement's metrics-port map, and a generation's active list. Read-only, tenant first (I5). The
 * one adapter delegates to {@code JdbcMetricDefinitionRepository}'s existing reads and holds no SQL of its own.
 */
public interface CollectionRows {

    List<MetricDefinitionSpec> definitions(UUID tenantId, UUID catalogSolutionId, String packageVersion);

    List<RestartSignalSpec> restartSignals(UUID tenantId, UUID catalogSolutionId, String packageVersion);

    /** The placement's source port to HOST port map (M-Q14). */
    Map<Integer, Integer> endpoints(UUID tenantId, UUID placementId);

    /** The metric names active for one generation (M-Q15), or empty when none was recorded. */
    Optional<List<String>> activeMetrics(UUID tenantId, UUID instanceId, long generation);
}
