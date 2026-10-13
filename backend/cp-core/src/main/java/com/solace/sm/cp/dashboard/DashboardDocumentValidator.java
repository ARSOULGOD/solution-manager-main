package com.solace.sm.cp.dashboard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException.Kind;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Validates what a caller submits to a dashboard: one widget (M-Q23's document shape, the demo's, generalized) or
 * one layout.
 *
 * <p><b>Generic by construction (I1/SM-D16, plan 011 Global Constraints).</b> The only thing this class knows about
 * a solution is what the caller passes in {@link DefaultLayerInput}: the instance's component type and the metrics
 * its package version declares. The demo's hard-coded metric set and entity type are gone; a different solution
 * differs only through its package.
 *
 * <p>Every refusal is an {@link InvalidDashboardException} with a fixed sentence. The submitted document is never
 * echoed back, in a message or anywhere else.
 */
public final class DashboardDocumentValidator {

    static final int MAX_WIDGET_CHARS = 64 * 1024;
    static final int MAX_TITLE_CHARS = 120;
    static final int GRID_COLUMNS = 12;

    private static final Set<String> MARKS = Set.of("line", "gauge", "single-stat");

    /** Strict: a duplicate key or trailing content is a refusal, never a silent last-wins. */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Parses and validates a submitted widget for this dashboard's instance, and returns it as the shared-layer
     * widget to store: the server's id is NOT set here, the title is trimmed, the layer is {@code shared} and no
     * {@code hidden} key survives.
     */
    public ObjectNode shared(String submittedJson, UUID instanceId, DefaultLayerInput input) {
        if (submittedJson.length() > MAX_WIDGET_CHARS) {
            throw invalid("The widget document is invalid.");
        }
        JsonNode parsed = parse(submittedJson, "The widget document is invalid.");
        if (!(parsed instanceof ObjectNode widget)) {
            throw invalid("The widget document is invalid.");
        }
        String title = textOf(widget.path("title")).trim();
        if (!widget.path("schemaVersion").isInt() || widget.path("schemaVersion").intValue() != 1
                || title.isEmpty() || title.length() > MAX_TITLE_CHARS
                || !MARKS.contains(textOf(widget.path("mark")))
                || !"shared".equals(textOf(widget.path("layer")))) {
            throw invalid("The widget document is invalid.");
        }
        JsonNode query = widget.path("query");
        JsonNode selector = query.path("selector");
        JsonNode selected = selector.path("instanceIds");
        // The selector first: a widget naming another instance, or two, is refused whatever its metric.
        if (!input.entityType().equals(textOf(selector.path("entityType")))
                || !selected.isArray() || selected.size() != 1
                || !instanceId.toString().equals(textOf(selected.path(0)))) {
            throw invalid("The widget must target this instance.");
        }
        String metric = textOf(query.path("metric"));
        if (declaredNames(input).stream().noneMatch(metric::equals)) {
            throw invalid("The widget names a metric this instance's package version does not declare.");
        }
        // OR-75 (M-Q6): a histogram is skipped by the collector, so a series for it will never exist
        if (input.declared().stream().anyMatch(m -> m.name().equals(metric) && "histogram".equals(m.metricType()))) {
            throw invalid("The widget names a histogram metric, which is not collected and cannot be charted.");
        }
        if (!widget.path("options").isObject() || !widget.path("axes").isObject()
                || !widget.path("legend").isObject() || !widget.path("series").isObject()
                || !widget.path("thresholds").isArray() || !widget.path("refresh").isObject()) {
            throw invalid("The widget document is incomplete.");
        }
        ObjectNode shared = widget.deepCopy();
        shared.put("title", title);
        shared.put("layer", "shared");
        shared.remove("hidden");
        shared.remove("id");
        return shared;
    }

    /**
     * Parses and validates a layout: every widget the dashboard renders (default and shared), placed exactly once,
     * on a 12-column grid. Returns the placements normalized to their six keys.
     */
    public ArrayNode layout(String submittedJson, Set<String> widgetIds) {
        JsonNode parsed = parse(submittedJson, "The dashboard layout is invalid.");
        if (!(parsed instanceof ArrayNode submitted) || submitted.size() != widgetIds.size()) {
            throw invalid("The dashboard layout must place every widget exactly once.");
        }
        Set<String> seen = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        ArrayNode normalized = STRICT.createArrayNode();
        for (JsonNode placement : submitted) {
            String widgetId = textOf(placement.path("widgetId"));
            if (!placement.isObject() || !widgetIds.contains(widgetId) || !seen.add(widgetId)
                    || !isInt(placement, "x") || !isInt(placement, "y") || !isInt(placement, "w")
                    || !isInt(placement, "h") || !isInt(placement, "order")) {
                throw invalid("The dashboard layout is invalid.");
            }
            int x = placement.path("x").intValue();
            int y = placement.path("y").intValue();
            int width = placement.path("w").intValue();
            int height = placement.path("h").intValue();
            int order = placement.path("order").intValue();
            if (!orders.add(order) || x < 0 || width < 1 || x + width > GRID_COLUMNS || y < 0 || y > 10_000
                    || height < 1 || height > 20 || order < 0) {
                throw invalid("The dashboard layout is invalid.");
            }
            ObjectNode placed = normalized.addObject();
            placed.put("widgetId", widgetId);
            placed.put("x", x);
            placed.put("y", y);
            placed.put("w", width);
            placed.put("h", height);
            placed.put("order", order);
        }
        return normalized;
    }

    private static Set<String> declaredNames(DefaultLayerInput input) {
        Set<String> names = new HashSet<>();
        input.declared().forEach(metric -> names.add(metric.name()));
        return names;
    }

    private static JsonNode parse(String json, String message) {
        try {
            return STRICT.readTree(json);
        } catch (JsonProcessingException notJson) {
            throw invalid(message);
        }
    }

    private static boolean isInt(JsonNode placement, String key) {
        return placement.path(key).isInt();
    }

    /** The node's text when it IS text; a number or object is not silently stringified into a match. */
    private static String textOf(JsonNode node) {
        return node.isTextual() ? node.textValue() : "";
    }

    private static InvalidDashboardException invalid(String message) {
        return new InvalidDashboardException(Kind.INVALID, message);
    }
}
