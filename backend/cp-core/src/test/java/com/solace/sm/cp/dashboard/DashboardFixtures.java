package com.solace.sm.cp.dashboard;

import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput.DeclaredMetric;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared test inputs. Deliberately NOT a Cache: the dashboard code must be generic (plan 011 Global Constraints). */
final class DashboardFixtures {

    static final String ENTITY_TYPE = "widget-engine";

    private DashboardFixtures() {
    }

    /**
     * Declares five metrics: two that get a default widget ({@code m.requests}, a counter, and {@code m.depth}, a
     * gauge), a histogram that never does, a gauge whose author set {@code defaultChart: false}, and a gauge that
     * wants one but is not active.
     */
    static DefaultLayerInput input() {
        return new DefaultLayerInput(ENTITY_TYPE, List.of(
                new DeclaredMetric("m.requests", "counter", "count", true),
                new DeclaredMetric("m.depth", "gauge", "items", true),
                new DeclaredMetric("m.latency", "histogram", "ms", true),
                new DeclaredMetric("m.quiet", "gauge", "items", false),
                new DeclaredMetric("m.inactive", "gauge", "items", true)),
                Set.of("m.requests", "m.depth", "m.latency", "m.quiet"));
    }

    static String widget(UUID instanceId, String metric) {
        return widget(ENTITY_TYPE, "[\"" + instanceId + "\"]", metric, "  " + metric + " over time  ", "{}");
    }

    static String widget(String entityType, String instanceIdsJson, String metric, String title, String options) {
        return """
                {
                  "schemaVersion": 1,
                  "id": "w_client_value",
                  "title": "%s",
                  "mark": "line",
                  "layer": "shared",
                  "hidden": true,
                  "options": %s,
                  "query": {
                    "metric": "%s",
                    "selector": {"entityType": "%s", "instanceIds": %s},
                    "seriesBy": "instance",
                    "aggregation": {"acrossReplicas": "auto", "acrossInstances": "none"}
                  },
                  "range": {"kind": "preset", "preset": "24h"},
                  "resolution": "auto",
                  "axes": {"y": {"label": "", "unitFormat": "auto", "min": null, "max": null}},
                  "legend": {"visible": true, "position": "bottom", "maxHeightRows": 4},
                  "series": {"colorStrategy": "palette", "colors": {}},
                  "thresholds": [],
                  "refresh": {"intervalSeconds": 60}
                }
                """.formatted(title, options, metric, entityType, instanceIdsJson);
    }
}
