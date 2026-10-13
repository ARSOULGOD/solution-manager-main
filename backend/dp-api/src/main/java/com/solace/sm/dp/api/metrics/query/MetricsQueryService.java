package com.solace.sm.dp.api.metrics.query;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The data plane's metrics read side (plan 011 P11-6.1; M-Q10, M-Q17, M-Q20, M-Q22, M-Q24). The console gateway composes
 * these with the control plane's declared metadata; the control plane never calls this.
 *
 * <p><b>The organization is the tenant (I5).</b> Every method takes the caller's {@code organizationId} and the data plane
 * binds it into every statement: an id of another organization's instance simply finds nothing, exactly like an id that
 * does not exist. The {@code instanceIds} are ALREADY reach-filtered by the gateway (M-Q17); the data plane does not know
 * a caller's reach and must never be handed one it did not filter.
 */
public interface MetricsQueryService {

    /**
     * One metric over a time range.
     *
     * @throws MetricsQueryRefusedException           the request is incoherent (a closed {@link QueryRefusal} code)
     * @throws MetricsQueryUnavailableException       the database budget is spent or the query timed out
     * @throws IllegalArgumentException               the request is malformed (an inverted range, no instance, a label)
     */
    RangeResult range(UUID organizationId, RangeQuery query);

    /** The latest point of each series of the same request shape (single-stat and gauge marks). Same throws as {@link #range}. */
    RangeResult instant(UUID organizationId, RangeQuery query);

    /**
     * The data plane's half of the metrics catalog: what has been observed for these instances, per metric.
     * {@code entityType} is the declaring component type.
     */
    List<ObservedMetric> observed(UUID organizationId, String entityType, Set<UUID> instanceIds);

    /**
     * The instance's CURRENT active metric names: the list of the newest generation that reached restart-observed, or
     * generation 0's (the declare-time list) when none has (M-Q15; P11-4.3's query). Empty when none was recorded. The
     * gateway's only source for the default layer.
     */
    List<String> activeMetrics(UUID organizationId, UUID instanceId);

    /**
     * The collector's health. {@code reasons} are the caller's organization ONLY; {@code runwayEnd},
     * {@code unfrozenClosedPartitions} and the disk state are install-level and carry no tenant figure.
     */
    CollectorHealthView collectorHealth(UUID organizationId);
}
