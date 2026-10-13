package com.solace.sm.dp.api.metrics.declare;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the gateway resolved from the control plane's package at declare (plan 011 M-Q5, M-Q14, M-Q15). The data plane
 * never reads a package: it stores what it is handed, once, in declare's reserve transaction.
 *
 * @param packageDigest      {@code sha256:} plus 64 lowercase hex digits: the loaded package version's content digest.
 *                           {@code null} only for {@link #none()}, which carries nothing else either
 * @param definitions        the version's metric definitions (M-Q5)
 * @param restartSignals     the per-component restart signals the version declares; optional, may be empty (OQ-1 (a))
 * @param metricsPorts       package source port to host-side port on the target (M-Q14); a map, so a source port is
 *                           named once. The same HOST port twice is a different refusal, made by the database.
 * @param activeMetricNames  the metric names the collector reads at generation 0 (M-Q15)
 */
public record MetricsDeclaration(String packageDigest, List<MetricDefinitionSpec> definitions,
        List<RestartSignalSpec> restartSignals, Map<Integer, Integer> metricsPorts, List<String> activeMetricNames) {

    public MetricsDeclaration {
        definitions = List.copyOf(definitions);
        restartSignals = List.copyOf(restartSignals);
        metricsPorts = Map.copyOf(metricsPorts);
        activeMetricNames = List.copyOf(activeMetricNames);
        if (packageDigest == null && !(definitions.isEmpty() && restartSignals.isEmpty() && metricsPorts.isEmpty()
                && activeMetricNames.isEmpty())) {
            throw new NullPointerException("packageDigest");   // only none() has no digest, and it has nothing else
        }
    }

    /**
     * "This declare carries no metrics declaration": the data plane writes no definition, no endpoint and no generation-0
     * list for it (plan 010's declare, before plan 011). Not "a package with no metrics": that one has a digest, and its
     * declaration is recorded, empty.
     *
     * <p>⚠️ A production path that reached for this by mistake would silently declare no metrics, which is why
     * {@code MetricsDeclarationConstructorTest} forbids {@code src/main} code from the constructor that supplies it.
     */
    public static MetricsDeclaration none() {
        return new MetricsDeclaration(null, List.of(), List.of(), Map.of(), List.of());
    }

    /** Whether this is {@link #none()}. */
    public boolean isNone() {
        return packageDigest == null;
    }
}
