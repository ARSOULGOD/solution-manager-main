package com.solace.sm.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed shared dashboard used by the local remote-Cache demo. */
@Service
final class DemoDashboardStore {
    private static final Set<String> METRICS = Set.of(
            "cache.hit_rate", "cache.miss_count", "cache.evictions", "cache.size", "jvm.memory.used");
    private static final Set<String> MARKS = Set.of("line", "gauge", "single-stat");
    private static final int MAX_DOCUMENT_CHARS = 64 * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    DemoDashboardStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    synchronized ObjectNode get(String dashboardId) {
        UUID instanceId = instanceId(dashboardId);
        requireInstance(instanceId);
        List<String> rows = jdbc.query("select document::text from demo_control.monitoring_dashboard where dashboard_id = ?",
                (rs, ignored) -> rs.getString(1), dashboardId);
        return rows.isEmpty() ? emptyDashboard(dashboardId) : parse(rows.get(0));
    }

    synchronized JsonNode saveWidget(String dashboardId, JsonNode submitted) {
        UUID instanceId = instanceId(dashboardId);
        ObjectNode dashboard = get(dashboardId);
        JsonNode saved = appendWidget(dashboard, submitted, instanceId);
        persist(dashboardId, instanceId, dashboard);
        return saved;
    }

    synchronized void setLayout(String dashboardId, JsonNode submitted) {
        ObjectNode dashboard = get(dashboardId);
        JsonNode layout = submitted.path("layout");
        validateLayout(layout, dashboard.path("widgets"));
        dashboard.set("layout", layout.deepCopy());
        persist(dashboardId, instanceId(dashboardId), dashboard);
    }

    synchronized void setHidden(String dashboardId, String widgetId, JsonNode submitted) {
        ObjectNode dashboard = get(dashboardId);
        if (!submitted.path("hidden").isBoolean()) {
            throw new DemoBadRequestException("The hidden state must be true or false.");
        }
        JsonNode target = null;
        for (JsonNode widget : dashboard.withArray("widgets")) {
            if (widgetId.equals(widget.path("id").asText())) {
                target = widget;
                break;
            }
        }
        if (target == null) throw new DemoNotFoundException();
        if (!"product-default".equals(target.path("layer").asText())) {
            throw new DemoBadRequestException("Only product-default widgets can be hidden.");
        }
        ((ObjectNode) target).put("hidden", submitted.path("hidden").asBoolean());
        persist(dashboardId, instanceId(dashboardId), dashboard);
    }

    synchronized ObjectNode reset(String dashboardId) {
        UUID instanceId = instanceId(dashboardId);
        requireInstance(instanceId);
        ObjectNode dashboard = emptyDashboard(dashboardId);
        persist(dashboardId, instanceId, dashboard);
        return dashboard;
    }

    static ObjectNode emptyDashboard(String dashboardId) {
        ObjectNode dashboard = JsonNodeFactory.instance.objectNode();
        dashboard.put("id", dashboardId);
        dashboard.put("schemaVersion", 1);
        dashboard.putArray("widgets");
        dashboard.putArray("layout");
        dashboard.putObject("range").put("preset", "24h");
        dashboard.putObject("refresh").putNull("intervalSeconds");
        return dashboard;
    }

    static JsonNode appendWidget(ObjectNode dashboard, JsonNode submitted, UUID instanceId) {
        validateWidget(submitted, instanceId);
        ObjectNode saved = ((ObjectNode) submitted).deepCopy();
        saved.put("id", "w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        saved.put("title", submitted.path("title").asText().trim());
        saved.put("layer", "shared");
        saved.remove("hidden");

        dashboard.withArray("widgets").add(saved);
        ArrayNode layout = dashboard.withArray("layout");
        int nextRow = 0;
        int nextOrder = 0;
        for (JsonNode placement : layout) {
            nextRow = Math.max(nextRow, placement.path("y").asInt() + placement.path("h").asInt());
            nextOrder = Math.max(nextOrder, placement.path("order").asInt() + 1);
        }
        ObjectNode placement = layout.addObject();
        placement.put("widgetId", saved.path("id").asText());
        placement.put("x", 0);
        placement.put("y", nextRow);
        placement.put("w", 6);
        placement.put("h", 2);
        placement.put("order", nextOrder);
        return saved.deepCopy();
    }

    private static void validateWidget(JsonNode widget, UUID instanceId) {
        if (!widget.isObject() || widget.toString().length() > MAX_DOCUMENT_CHARS) {
            throw new DemoBadRequestException("The widget document is invalid.");
        }
        String title = widget.path("title").asText().trim();
        if (widget.path("schemaVersion").asInt() != 1 || title.isEmpty() || title.length() > 120
                || !MARKS.contains(widget.path("mark").asText())
                || !"shared".equals(widget.path("layer").asText())) {
            throw new DemoBadRequestException("The widget document is invalid.");
        }
        JsonNode query = widget.path("query");
        JsonNode selector = query.path("selector");
        JsonNode selected = selector.path("instanceIds");
        if (!METRICS.contains(query.path("metric").asText())
                || !"cache-engine".equals(selector.path("entityType").asText())
                || !selected.isArray() || selected.size() != 1
                || !instanceId.toString().equals(selected.path(0).asText())) {
            throw new DemoBadRequestException("The widget must target this Cache Engine instance.");
        }
        if (!widget.path("options").isObject() || !widget.path("axes").isObject()
                || !widget.path("legend").isObject() || !widget.path("series").isObject()
                || !widget.path("thresholds").isArray() || !widget.path("refresh").isObject()) {
            throw new DemoBadRequestException("The widget document is incomplete.");
        }
    }

    private static void validateLayout(JsonNode layout, JsonNode widgets) {
        if (!layout.isArray() || layout.size() != widgets.size()) {
            throw new DemoBadRequestException("The dashboard layout must place every widget exactly once.");
        }
        Set<String> expected = new HashSet<>();
        widgets.forEach(widget -> expected.add(widget.path("id").asText()));
        Set<String> seen = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (JsonNode placement : layout) {
            String widgetId = placement.path("widgetId").asText();
            int x = placement.path("x").asInt(-1);
            int y = placement.path("y").asInt(-1);
            int width = placement.path("w").asInt(-1);
            int height = placement.path("h").asInt(-1);
            int order = placement.path("order").asInt(-1);
            if (!expected.contains(widgetId) || !seen.add(widgetId) || !orders.add(order)
                    || x < 0 || width < 1 || x + width > 12 || y < 0 || y > 10_000
                    || height < 1 || height > 20 || order < 0) {
                throw new DemoBadRequestException("The dashboard layout is invalid.");
            }
        }
    }

    private ObjectNode parse(String document) {
        try {
            JsonNode parsed = json.readTree(document);
            if (!(parsed instanceof ObjectNode object)) throw new DemoUnavailableException();
            return object;
        } catch (JsonProcessingException invalid) {
            throw new DemoUnavailableException();
        }
    }

    private void persist(String dashboardId, UUID instanceId, ObjectNode dashboard) {
        jdbc.update("insert into demo_control.monitoring_dashboard (dashboard_id, instance_id, document, updated_at) "
                        + "values (?, ?, ?::jsonb, ?) on conflict (dashboard_id) do update "
                        + "set document = excluded.document, updated_at = excluded.updated_at",
                dashboardId, instanceId, dashboard.toString(), Timestamp.from(Instant.now()));
    }

    private void requireInstance(UUID instanceId) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from demo_control.instance where instance_id = ?)", Boolean.class, instanceId);
        if (!Boolean.TRUE.equals(exists)) throw new DemoNotFoundException();
    }

    private static UUID instanceId(String dashboardId) {
        if (dashboardId == null || !dashboardId.startsWith("db_")) throw new DemoNotFoundException();
        try {
            return UUID.fromString(dashboardId.substring(3));
        } catch (IllegalArgumentException invalid) {
            throw new DemoNotFoundException();
        }
    }
}
