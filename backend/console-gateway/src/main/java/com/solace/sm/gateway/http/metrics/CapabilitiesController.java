package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.metrics.query.CollectorHealthView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryService;
import com.solace.sm.gateway.http.ConsoleOperation;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getCapabilities} (row 53): what the query API serves, and the collector's health. {@code authentication only, install
 * scope}: any authenticated principal of any organization reads it, so nothing in it may be one organization's data except
 * the one field stated below (OQ-16 (a), {@code I5}).
 *
 * <ul>
 *   <li>{@code state}, {@code runwayEnd}, {@code unfrozenClosedPartitions} and {@code disk.state} are install-level health;</li>
 *   <li>{@code reasons} are counted over the last 24 hours for {@code Session.organizationId()} only;</li>
 *   <li>⛔ <b>no byte figure</b>: the sizes are install-wide totals across tenants. {@link MetricsWire.Collector} and
 *       {@link MetricsWire.Disk} have no such component, and {@code MetricsSchemaMirrorTest} fails the build if one is added.</li>
 * </ul>
 *
 * <p>The presets, caps and tiers come from configuration the data plane enforces ({@link DataPlaneQueryLimits}), never from
 * a literal here.
 */
@RestController
@RequestMapping("/api/console/v1")
public class CapabilitiesController {

    private final MetricsQueryService query;
    private final MetricsGatewayProperties properties;
    private final DataPlaneQueryLimits limits;

    public CapabilitiesController(MetricsQueryService query, MetricsGatewayProperties properties,
            DataPlaneQueryLimits limits) {
        this.query = Objects.requireNonNull(query, "query");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @GetMapping("/capabilities")
    @ConsoleOperation("getCapabilities")
    public ResponseEntity<MetricsWire.Capabilities> getCapabilities(Session session) {
        CollectorHealthView health = query.collectorHealth(session.organizationId());
        List<MetricsWire.Tier> tiers = List.of(
                new MetricsWire.Tier("raw", iso(limits.rawRetention()), properties.rawMinBucketSeconds()),
                new MetricsWire.Tier("hourly", iso(limits.hourlyRetention()), 3600),
                new MetricsWire.Tier("daily", null, 86_400));
        return ResponseEntity.ok(new MetricsWire.Capabilities(1, tiers, iso(limits.hourlyRetention()),
                limits.seriesCap(), limits.maxPointsPerSeries(), List.of("line", "gauge", "single-stat"),
                List.of("sum", "avg", "min", "max"), new MetricsWire.Features(true, true, false), collector(health)));
    }

    /**
     * What the data plane has not measured is ABSENT, and the state says so (OR-112 (6), W4-05): {@code runwayEnd} is absent
     * before the data plane's first partition keep, {@code disk} is absent when a budget is set and no measurement has
     * succeeded yet, and neither is replaced by a stand-in (today's date, {@code unconfigured}) that would read as a
     * measurement. The state is {@code degraded} when the data plane says so (a measured fact, even beside a missing one),
     * otherwise {@code unknown} while either is absent, and {@code live} only when both are present. Neither is a byte figure.
     */
    private static MetricsWire.Collector collector(CollectorHealthView health) {
        boolean measured = health.runwayEnd() != null && health.disk() != null;
        String state = health.state() == CollectorHealthView.State.DEGRADED ? "degraded" : measured ? "live" : "unknown";
        return new MetricsWire.Collector(state, health.runwayEnd() == null ? null : health.runwayEnd().toString(),
                health.unfrozenClosedPartitions(),
                health.reasons().stream().map(r -> new MetricsWire.CollectorReasonCount(r.reason().wire(), r.last24h()))
                        .toList(),
                health.disk() == null ? null : new MetricsWire.Disk(health.disk().state().wire()));
    }

    /** A whole number of days as {@code P<n>D}, which is unambiguous (months and years are not). */
    static String iso(Duration duration) {
        return duration.toHours() % 24 == 0 && duration.toMinutes() % 60 == 0 && duration.toSeconds() % 60 == 0
                && duration.toNanosPart() == 0 ? "P" + duration.toDays() + "D" : duration.toString();
    }
}
