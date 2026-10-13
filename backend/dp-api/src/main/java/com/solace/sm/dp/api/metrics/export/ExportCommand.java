package com.solace.sm.dp.api.metrics.export;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One export request (plan 011 P11-6.2, M-Q18): one metric over a half-open range {@code [from, to)} for the instances the
 * caller may see.
 *
 * <p>{@code instanceIds} are ALREADY reach-filtered by the gateway (M-Q17: an id the viewer cannot see is omitted
 * silently, exactly like a nonexistent one, and {@code metrics.export} was intersected). The data plane never takes the
 * organization from this record: it is the separate {@code organizationId} argument of the service, bound into every
 * statement ({@code I5}), so a hostile id in this list can only name a series the organization already owns.
 *
 * @param metric        the declared metric name
 * @param instanceIds   reach-filtered, never null; empty is a header-only export
 * @param from          inclusive
 * @param to            exclusive, after {@code from}
 * @param correlationId the request's id, carried to the audit row and nothing else
 */
public record ExportCommand(String metric, List<UUID> instanceIds, Instant from, Instant to, UUID correlationId) {

    /** The longest series name the store accepts ({@code series_metric_name}). */
    public static final int MAX_METRIC_LENGTH = 128;

    public ExportCommand {
        Objects.requireNonNull(metric, "metric");
        if (metric.isBlank() || metric.length() > MAX_METRIC_LENGTH) {
            throw new IllegalArgumentException("metric must be 1.." + MAX_METRIC_LENGTH + " characters");
        }
        instanceIds = List.copyOf(Objects.requireNonNull(instanceIds, "instanceIds"));
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
