package com.solace.sm.dp.metrics.declare;

import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The rows a declare and a stage write about metrics (plan 011 P11-4.1; M-Q5, M-Q14, M-Q15), as the port
 * {@code InventoryService} and {@code ConfigRecordServiceImpl} hold, so neither names a JDBC type. The one adapter is
 * {@code JdbcMetricDefinitionRepository}.
 *
 * <p>Every method is meant to run INSIDE the caller's transaction: the adapter opens none, so a refusal anywhere rolls
 * the metrics rows back with the placement or the generation they belong to.
 */
public interface MetricDeclarationRows {

    /**
     * Copies a package version's definitions and restart signals once, insert-if-absent. A different digest for a
     * version already recorded is a {@code PackageVersionMismatchException} and nothing is written.
     *
     * @return the number of definition and restart-signal rows newly written; 0 on an idempotent retry
     */
    int insertDefinitionsIfAbsent(UUID tenantId, UUID catalogSolutionId, String packageVersion,
            MetricsDeclaration declaration, Instant at);

    /**
     * Writes a placement's source-port to host-port map in one statement. A host port another live placement on the
     * target already uses, or one named twice, is a {@code MetricsPortCollisionException}.
     */
    void insertEndpoints(UUID tenantId, UUID placementId, UUID targetId, Map<Integer, Integer> metricsPorts, Instant at);

    /** Records the names active for a generation, once. A second write for one generation is an integrity refusal. */
    void insertActiveMetrics(UUID tenantId, UUID instanceId, long generation, List<String> names, Instant at);

    /** The names recorded for that generation, or empty when none was. */
    Optional<List<String>> activeMetricsFor(UUID tenantId, UUID instanceId, long generation);

    /**
     * A sink that accepts only what carries nothing: a service built without a metrics repository (plan 010's tests, which
     * never declare or stage a metric). It discards an EMPTY list or an empty declaration and refuses everything else, so
     * that a production wiring that forgot the real adapter fails on its first metric instead of silently losing it.
     */
    MetricDeclarationRows DISCARDING = new MetricDeclarationRows() {
        @Override
        public int insertDefinitionsIfAbsent(UUID tenantId, UUID catalogSolutionId, String packageVersion,
                MetricsDeclaration declaration, Instant at) {
            throw unavailable();
        }

        @Override
        public void insertEndpoints(UUID tenantId, UUID placementId, UUID targetId, Map<Integer, Integer> metricsPorts,
                Instant at) {
            if (!metricsPorts.isEmpty()) {
                throw unavailable();
            }
        }

        @Override
        public void insertActiveMetrics(UUID tenantId, UUID instanceId, long generation, List<String> names, Instant at) {
            if (!names.isEmpty()) {
                throw unavailable();
            }
        }

        @Override
        public Optional<List<String>> activeMetricsFor(UUID tenantId, UUID instanceId, long generation) {
            return Optional.empty();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException("this service was built without a metrics repository and was asked to "
                    + "record a metrics declaration");
        }
    };
}
