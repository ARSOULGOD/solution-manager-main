package com.solace.sm.gateway.http.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The data plane's query limits, read from the SAME keys it binds ({@code sm.dp.metrics.query.*}, plan 011 P11-6.1) so
 * {@code getCapabilities} can advertise them: the UI hardcodes none of them (DM-D10).
 *
 * <p>⚠️ <b>The defaults are stated in two modules</b> ({@code dp-core}'s {@code QueryProperties} and here), because the
 * gateway may not import {@code dp-core} (I7), exactly as {@code DeclareOrchestrator} restates the declare sweep's
 * window. {@code MetricsEndpointsIT.capabilitiesAdvertiseTheLimitsTheDataPlaneEnforces} binds both to one context and
 * compares them, so a default that drifts in one module is a red build and not a console that advertises a cap the
 * data plane does not apply.
 *
 * @param seriesCap       rendered series per request
 * @param maxPointsPerSeries the raw tier's points per series
 * @param rawMaxRange     a range up to this is served raw
 * @param hourlyMaxRange  a range up to this is served hourly, a longer one daily
 * @param rawRetention    how far back raw is kept
 * @param hourlyRetention how far back hourly rollups are kept; daily is kept indefinitely
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.query")
public record DataPlaneQueryLimits(
        @DefaultValue("10") int seriesCap,
        @DefaultValue("1000") int maxPointsPerSeries,
        @DefaultValue("7d") Duration rawMaxRange,
        @DefaultValue("90d") Duration hourlyMaxRange,
        @DefaultValue("183d") Duration rawRetention,
        @DefaultValue("730d") Duration hourlyRetention) {

    public static DataPlaneQueryLimits defaults() {
        return new DataPlaneQueryLimits(10, 1000, Duration.ofDays(7), Duration.ofDays(90), Duration.ofDays(183),
                Duration.ofDays(730));
    }
}
