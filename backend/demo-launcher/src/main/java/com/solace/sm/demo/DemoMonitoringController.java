package com.solace.sm.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerMetrics;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live Cache Engine bridge for the imported Monitoring shell.
 * Cache does not retain a time series, so query responses contain the current
 * sample only and explicitly mark historical ranges as unavailable.
 */
@RestController
@RequestMapping("/api/console/v1")
final class DemoMonitoringController {
    private final DemoSessionService sessions;
    private final DemoInstanceService instances;
    private final DemoDashboardStore dashboards;
    private final DemoMetricHistory metricHistory;
    private final JdbcTemplate jdbc;

    DemoMonitoringController(DemoSessionService sessions, DemoInstanceService instances,
            DemoDashboardStore dashboards, DemoMetricHistory metricHistory, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.instances = instances;
        this.dashboards = dashboards;
        this.metricHistory = metricHistory;
        this.jdbc = jdbc;
    }

    @GetMapping("/capabilities")
    Capabilities capabilities(HttpServletRequest request) {
        require(request);
        return new Capabilities(1, List.of(new Tier("raw", "P1D", 10)), "P1D", 10, 1_000,
                List.of("line", "gauge", "single-stat"), List.of("sum", "avg", "min", "max"),
                new Features(false, false, false));
    }

    @GetMapping("/dashboards/{dashboardId}")
    JsonNode dashboard(@PathVariable String dashboardId, HttpServletRequest request) {
        require(request);
        return dashboards.get(dashboardId);
    }

    @PostMapping("/dashboards/{dashboardId}/widgets")
    ResponseEntity<JsonNode> saveWidget(@PathVariable String dashboardId, @RequestBody JsonNode widget,
            HttpServletRequest request) {
        require(request, true);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .body(dashboards.saveWidget(dashboardId, widget));
    }

    @PutMapping("/dashboards/{dashboardId}/layout")
    ResponseEntity<Void> setLayout(@PathVariable String dashboardId, @RequestBody JsonNode layout,
            HttpServletRequest request) {
        require(request, true);
        dashboards.setLayout(dashboardId, layout);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/dashboards/{dashboardId}/widgets/{widgetId}")
    ResponseEntity<Void> setHidden(@PathVariable String dashboardId, @PathVariable String widgetId,
            @RequestBody JsonNode hidden, HttpServletRequest request) {
        require(request, true);
        dashboards.setHidden(dashboardId, widgetId, hidden);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dashboards/{dashboardId}/reset")
    JsonNode resetDashboard(@PathVariable String dashboardId, HttpServletRequest request) {
        require(request, true);
        return dashboards.reset(dashboardId);
    }

    @GetMapping("/entity-types")
    List<EntityType> entityTypes(HttpServletRequest request) {
        require(request);
        return List.of(new EntityType("cache-engine", "Cache Engine"));
    }

    @GetMapping("/entity-types/{entityType}/instances")
    PagedInstances metricInstances(@PathVariable String entityType, HttpServletRequest request) {
        require(request);
        if (!"cache-engine".equals(entityType)) {
            return new PagedInstances(List.of(), 0);
        }
        List<MetricInstance> rows = instanceRows();
        return new PagedInstances(rows, rows.size());
    }

    @GetMapping("/metrics/catalog")
    List<Metric> metricCatalog(HttpServletRequest request) {
        require(request);
        long now = Instant.now().toEpochMilli();
        return List.of(
                metric("cache.hit_rate", "gauge", "ratio", now),
                metric("cache.miss_count", "counter", "misses", now),
                metric("cache.evictions", "counter", "evictions", now),
                metric("cache.size", "gauge", "entries", now),
                metric("jvm.memory.used", "gauge", "bytes", now));
    }

    @GetMapping("/labels/{labelKey}/values")
    List<String> labelValues(@PathVariable String labelKey, HttpServletRequest request) {
        require(request);
        return List.of();
    }

    @PostMapping("/query/range")
    QueryResult queryRange(@RequestBody JsonNode body, HttpServletRequest request) throws IOException {
        require(request);
        return query(body, request);
    }

    @PostMapping("/query/instant")
    QueryResult queryInstant(@RequestBody JsonNode body, HttpServletRequest request) throws IOException {
        require(request);
        return query(body, request);
    }

    private QueryResult query(JsonNode body, HttpServletRequest request) throws IOException {
        String metric = requiredText(body, "metric");
        MetricDefinition definition = METRICS.get(metric);
        if (definition == null) {
            throw new DemoBadRequestException("The selected metric is not available from Cache Engine.");
        }
        JsonNode selector = body.path("selector");
        if (!"cache-engine".equals(selector.path("entityType").asText()) || selector.path("instanceIds").size() != 1) {
            throw new DemoBadRequestException("Select exactly one Cache Engine instance.");
        }
        UUID instanceId = uuid(selector.path("instanceIds").path(0).asText());
        MetricInstance instance = instance(instanceId);
        Instant requestedFrom = instant(body.path("range").path("from").asText());
        String resolution = body.path("resolution").asText("auto");
        if (!"auto".equals(resolution) && !"raw".equals(resolution)) {
            throw new DemoBadRequestException("The selected resolution is not supported.");
        }

        DemoMetricHistory.Sample observed = metricHistory.observe(instanceId,
                () -> instances.metrics(instanceId, vaultKey(request)));
        List<DemoMetricHistory.Sample> history = metricHistory.samples(instanceId, requestedFrom);
        String servedFrom = history.isEmpty() ? observed.observedAt().toString() : history.get(0).observedAt().toString();
        List<List<Object>> points = history.stream()
                .map(sample -> List.<Object>of(sample.observedAt().toString(),
                        definition.value().value(sample.metrics()), 1, 1))
                .toList();
        return new QueryResult(1, new MetricDescriptor(metric, definition.type(), definition.unit()),
                new Render("none"), new Resolution(resolution, "live", 10),
                new Coverage(requestedFrom.toString(), servedFrom, requestedFrom.isBefore(Instant.parse(servedFrom))), 30,
                List.of(new Series(new SeriesRef(instance.id(), null, "live-cache-engine"), instance.label(),
                        new ReplicaComposition(1, 0), "unverified-durability", points, List.of())),
                new Partial(false, List.of()));
    }

    private List<MetricInstance> instanceRows() {
        return jdbc.query("select instance_id, display_name from demo_control.instance order by created_at desc",
                (rs, ignored) -> new MetricInstance(rs.getObject(1, UUID.class).toString(), rs.getString(2), 1));
    }

    private MetricInstance instance(UUID instanceId) {
        return jdbc.query("select instance_id, display_name from demo_control.instance where instance_id = ?",
                (rs, ignored) -> new MetricInstance(rs.getObject(1, UUID.class).toString(), rs.getString(2), 1), instanceId)
                .stream().findFirst().orElseThrow(DemoNotFoundException::new);
    }

    private void require(HttpServletRequest request) {
        require(request, false);
    }

    private void require(HttpServletRequest request, boolean mutation) {
        sessions.require(cookie(request, DemoSessionService.SESSION_COOKIE), request.getHeader("X-SM-CSRF"), mutation);
    }

    private byte[] vaultKey(HttpServletRequest request) {
        return sessions.vaultKey(cookie(request, DemoSessionService.SESSION_COOKIE));
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static String requiredText(JsonNode value, String name) {
        String text = value.path(name).asText();
        if (text.isBlank()) throw new DemoBadRequestException("The monitoring query is invalid.");
        return text;
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new DemoBadRequestException("The selected instance does not exist."); }
    }

    private static Instant instant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw new DemoBadRequestException("The monitoring range is invalid."); }
    }

    private static Metric metric(String name, String type, String unit, long lastSeen) {
        return new Metric(name, type, unit, "package-baseline", "plottable", lastSeen, List.of(), "best-effort");
    }

    record Capabilities(int queryVersion, List<Tier> tiers, String maxRange, int maxSeriesPerWidget,
            int maxPointsPerSeries, List<String> marks, List<String> aggregations, Features features) {}
    record Tier(String name, String retention, int minBucketSeconds) {}
    record Features(boolean forceRaw, boolean streamingExport, boolean pngExport) {}
    record EntityType(String id, String label) {}
    record PagedInstances(List<MetricInstance> items, int total) {}
    record MetricInstance(String id, String label, int replicaCount) {}
    record Metric(String name, String metricType, String unit, String layer, String state, long lastSeen,
            List<String> labelKeys, String durability) {}
    record MetricDescriptor(String name, String metricType, String unit) {}
    record Render(String transform) {}
    record Resolution(String requested, String served, int bucketSeconds) {}
    record Coverage(String requestedFrom, String servedFrom, boolean truncatedByRetention) {}
    record SeriesRef(String instanceId, String placementId, String sourceKey) {}
    record ReplicaComposition(int total, int inactive) {}
    record Series(SeriesRef seriesRef, String label, ReplicaComposition replicaComposition, String state,
            List<List<Object>> points, List<Object> gaps) {}
    record Partial(boolean degraded, List<Object> unavailable) {}
    record QueryResult(int queryVersion, MetricDescriptor metric, Render render, Resolution resolution,
            Coverage coverage, int gapThresholdSeconds, List<Series> series, Partial partial) {}

    @ExceptionHandler(DemoUnauthorizedException.class)
    ResponseEntity<DemoController.ApiError> unauthorized() { return error(HttpStatus.UNAUTHORIZED, "Authentication is required."); }
    @ExceptionHandler(DemoBadRequestException.class)
    ResponseEntity<DemoController.ApiError> badRequest(DemoBadRequestException error) { return error(HttpStatus.BAD_REQUEST, error.getMessage()); }
    @ExceptionHandler(DemoNotFoundException.class)
    ResponseEntity<DemoController.ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "The selected instance does not exist."); }
    @ExceptionHandler(RuntimeAdapterException.class)
    ResponseEntity<DemoController.ApiError> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "The configured instance is unavailable."); }
    @ExceptionHandler(DemoUnavailableException.class)
    ResponseEntity<DemoController.ApiError> demoUnavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "The configured instance is unavailable."); }
    private static ResponseEntity<DemoController.ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new DemoController.ApiError(message));
    }

    private record MetricDefinition(String type, String unit, MetricValue value) {}
    @FunctionalInterface
    private interface MetricValue { double value(SshDockerMetrics.MetricsSnapshot sample); }
    private static final Map<String, MetricDefinition> METRICS = Map.of(
            "cache.hit_rate", new MetricDefinition("gauge", "ratio", SshDockerMetrics.MetricsSnapshot::hitRate),
            "cache.miss_count", new MetricDefinition("counter", "misses", SshDockerMetrics.MetricsSnapshot::missCount),
            "cache.evictions", new MetricDefinition("counter", "evictions", SshDockerMetrics.MetricsSnapshot::evictionCount),
            "cache.size", new MetricDefinition("gauge", "entries", sample -> sample.size()),
            "jvm.memory.used", new MetricDefinition("gauge", "bytes", SshDockerMetrics.MetricsSnapshot::heapUsed));
}
