package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CollectionRows} over {@link JdbcMetricDefinitionRepository}'s existing reads (plan 011 P11-4.3): no SQL of its
 * own, so the one place that spells these statements stays P11-2.2's and P11-4.1's.
 */
public final class MetricDefinitionCollectionRows implements CollectionRows {

    private final JdbcMetricDefinitionRepository definitions;

    public MetricDefinitionCollectionRows(JdbcMetricDefinitionRepository definitions) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
    }

    @Override
    public List<MetricDefinitionSpec> definitions(UUID tenantId, UUID catalogSolutionId, String packageVersion) {
        return definitions.definitionsFor(tenantId, catalogSolutionId, packageVersion);
    }

    @Override
    public List<RestartSignalSpec> restartSignals(UUID tenantId, UUID catalogSolutionId, String packageVersion) {
        return definitions.restartSignalsFor(tenantId, catalogSolutionId, packageVersion);
    }

    @Override
    public Map<Integer, Integer> endpoints(UUID tenantId, UUID placementId) {
        return definitions.endpointsFor(tenantId, placementId);
    }

    @Override
    public Optional<List<String>> activeMetrics(UUID tenantId, UUID instanceId, long generation) {
        return definitions.activeMetricsFor(tenantId, instanceId, generation);
    }
}
