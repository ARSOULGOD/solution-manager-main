package com.solace.sm.cp.api.dashboard;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a dashboard needs from OUTSIDE the control plane to know its product-default layer and to validate a widget
 * against the instance's package version (M-Q10, M-Q23). It is data, not a port: the control plane never calls the
 * data plane ({@code I7}), so the gateway composes the two and passes the result in.
 *
 * <p>⛔ <b>Two inputs, from two places, and they are not interchangeable.</b> {@code declared} comes from the
 * instance's package version ({@code SolutionCatalog.documentOf}): it is what a widget may name, and what a
 * default widget could be. {@code activeMetrics} comes from the data plane ({@code activeMetrics(org,
 * instanceId)}): it is what is actually collected for the instance now. A default widget needs both.
 *
 * @param entityType    the instance's component type, as the package spells it; every widget's selector names it
 * @param declared      the metrics the instance's package version declares for that component, in declared order
 * @param activeMetrics the metric names the data plane currently collects for the instance
 */
public record DefaultLayerInput(String entityType, List<DeclaredMetric> declared, Set<String> activeMetrics) {

    /**
     * One declared metric.
     *
     * @param metricType  {@code counter}, {@code gauge} or {@code histogram}
     * @param defaultChart the package author's wish for a product-default widget (OQ-7 (b): {@code false} gets none)
     */
    public record DeclaredMetric(String name, String metricType, String unit, boolean defaultChart) {

        public DeclaredMetric {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(metricType, "metricType");
            unit = unit == null ? "" : unit;
        }
    }

    public DefaultLayerInput {
        Objects.requireNonNull(entityType, "entityType");
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("entityType is required");
        }
        declared = List.copyOf(Objects.requireNonNull(declared, "declared"));
        activeMetrics = Set.copyOf(Objects.requireNonNull(activeMetrics, "activeMetrics"));
    }
}
