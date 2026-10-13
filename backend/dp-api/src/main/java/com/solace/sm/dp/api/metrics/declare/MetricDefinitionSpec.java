package com.solace.sm.dp.api.metrics.declare;

import java.util.Objects;

/**
 * One metric a package version declares (plan 011 M-Q5). {@code sourceJson} is the package's grammar-checked
 * {@code source} object as JSON text; this plan's tables keep it opaque, and PostgreSQL's {@code jsonb} may
 * re-order its keys and drop its whitespace on the way back, so a caller compares parsed documents, never text.
 *
 * @param intervalSeconds null when the metric is pushed rather than polled
 */
public record MetricDefinitionSpec(String metricName, String componentType, String metricType, String unit,
        String sourceKind, String sourceJson, Integer intervalSeconds, boolean defaultChart) {

    public MetricDefinitionSpec {
        Objects.requireNonNull(metricName, "metricName");
        Objects.requireNonNull(componentType, "componentType");
        Objects.requireNonNull(metricType, "metricType");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceJson, "sourceJson");
    }
}
