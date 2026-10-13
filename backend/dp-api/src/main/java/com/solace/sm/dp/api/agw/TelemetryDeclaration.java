package com.solace.sm.dp.api.agw;

import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * What the gateway tells an agent to collect for one placement: the package's {@code http_json} metrics and its
 * declared health probe. {@code metrics} is the COMPLETE set (it replaces the previous set atomically);
 * {@code healthProbe} is {@code null} when the package declares none — then nothing is probed (OR-101).
 */
public record TelemetryDeclaration(UUID placementId, Duration interval, List<Metric> metrics,
        HealthProbeSpec healthProbe) {
    public TelemetryDeclaration {
        metrics = List.copyOf(metrics == null ? List.of() : metrics);
    }

    /** One HTTP+JSON-pointer metric; {@code containerPort} is the container side, the agent maps it to the published host port. */
    public record Metric(String name, String metricType, String unit, int containerPort, String path,
            String pointer, boolean degradeWhenUnavailable) { }
}
