package com.solace.sm.cp.dashboard;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The product-default layer (SM-D31, PROPOSED; M-Q23): derived on every read, never stored. One widget for each
 * metric that is
 * <ul>
 *   <li>declared by the instance's package version with {@code defaultChart} (OQ-7 (b): {@code false} gets none),</li>
 *   <li>active, that is, currently collected for the instance, and</li>
 *   <li>not a histogram (a histogram is ingested as its {@code _count} and {@code _sum} counters, which are
 *       themselves metrics).</li>
 * </ul>
 * A counter and a gauge both draw as {@code line}: the rate a counter needs is decided by the query service from the
 * metric's type ({@code render.transform}), not by the widget.
 *
 * <p>Because the layer is derived, a package upgrade replaces it wholesale and an operator's hide choice (stored as
 * an override by id) survives it, which is the whole point of hide-not-delete.
 */
public final class DefaultLayerDeriver {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The widgets, in the order the package declares their metrics. */
    public List<ObjectNode> derive(UUID instanceId, DefaultLayerInput input) {
        List<ObjectNode> widgets = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (DefaultLayerInput.DeclaredMetric metric : input.declared()) {
            if (metric.defaultChart() && !"histogram".equals(metric.metricType())
                    && input.activeMetrics().contains(metric.name()) && placed.add(metric.name())) {
                widgets.add(widget(instanceId, input.entityType(), metric));
            }
        }
        return widgets;
    }

    /**
     * The widget id for a metric: a function of the metric name alone, so the id survives a package upgrade, a
     * restart and a re-derivation, and a hide override recorded under it keeps applying.
     */
    public static String widgetId(String metricName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(metricName.getBytes(StandardCharsets.UTF_8));
            return "d_" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    private static ObjectNode widget(UUID instanceId, String entityType, DefaultLayerInput.DeclaredMetric metric) {
        ObjectNode widget = JSON.createObjectNode();
        widget.put("schemaVersion", 1);
        widget.put("id", widgetId(metric.name()));
        widget.put("title", metric.name());
        widget.put("mark", "line");
        widget.put("layer", "product-default");
        widget.putObject("options");
        ObjectNode query = widget.putObject("query");
        query.put("metric", metric.name());
        ObjectNode selector = query.putObject("selector");
        selector.put("entityType", entityType);
        selector.putArray("instanceIds").add(instanceId.toString());
        selector.putObject("labels");
        query.put("seriesBy", "instance");
        ObjectNode aggregation = query.putObject("aggregation");
        aggregation.put("acrossReplicas", "auto");
        aggregation.put("acrossInstances", "none");
        query.putArray("groupBy");
        ObjectNode range = widget.putObject("range");
        range.put("kind", "preset");
        range.put("preset", "24h");
        widget.put("resolution", "auto");
        ObjectNode y = widget.putObject("axes").putObject("y");
        y.put("label", metric.unit());
        y.put("unitFormat", "auto");
        y.putNull("min");
        y.putNull("max");
        ObjectNode legend = widget.putObject("legend");
        legend.put("visible", true);
        legend.put("position", "bottom");
        legend.put("maxHeightRows", 4);
        ObjectNode series = widget.putObject("series");
        series.put("colorStrategy", "palette");
        series.putObject("colors");
        widget.putArray("thresholds");
        widget.putObject("refresh").put("intervalSeconds", 60);
        return widget;
    }
}
