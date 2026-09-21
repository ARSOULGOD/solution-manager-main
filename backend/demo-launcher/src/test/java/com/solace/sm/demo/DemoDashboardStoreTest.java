package com.solace.sm.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoDashboardStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savedWidgetReceivesAServerIdAndVisiblePlacement() throws Exception {
        UUID instanceId = UUID.randomUUID();
        ObjectNode dashboard = DemoDashboardStore.emptyDashboard("db_" + instanceId);
        JsonNode submitted = JSON.readTree("""
                {
                  "schemaVersion": 1,
                  "id": "w_client_value",
                  "title": "JVM memory",
                  "mark": "line",
                  "options": {},
                  "query": {
                    "metric": "jvm.memory.used",
                    "selector": {
                      "entityType": "cache-engine",
                      "instanceIds": ["%s"]
                    },
                    "seriesBy": "instance",
                    "aggregation": {
                      "acrossReplicas": "auto",
                      "acrossInstances": "none"
                    }
                  },
                  "range": {"kind": "preset", "preset": "24h"},
                  "resolution": "auto",
                  "axes": {"y": {"label": "", "unitFormat": "auto", "min": null, "max": null}},
                  "legend": {"visible": true, "position": "bottom", "maxHeightRows": 4},
                  "series": {"colorStrategy": "palette", "colors": {}},
                  "thresholds": [],
                  "refresh": {"intervalSeconds": 60},
                  "layer": "shared"
                }
                """.formatted(instanceId));

        JsonNode saved = DemoDashboardStore.appendWidget(dashboard, submitted, instanceId);

        assertNotEquals("w_client_value", saved.path("id").asText());
        assertEquals(saved, dashboard.path("widgets").path(0));
        assertEquals(saved.path("id").asText(), dashboard.path("layout").path(0).path("widgetId").asText());
        assertEquals(6, dashboard.path("layout").path(0).path("w").asInt());
        assertEquals(2, dashboard.path("layout").path(0).path("h").asInt());
        assertFalse(saved.has("hidden"));
    }
}
