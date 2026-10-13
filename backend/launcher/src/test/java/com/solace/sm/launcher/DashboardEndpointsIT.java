package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.solace.sm.launcher.MonitoringItSupport.Http;
import com.solace.sm.launcher.MonitoringItSupport.Seeded;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 011 P11-7.2: rows 62-66, the per-instance shared dashboard, over the REAL boot - the real store (P11-7.1) and its
 * audit, the real reach and instance-scope authorization, the real data plane's active-metrics list and PostgreSQL. State
 * carries from test to test (ordered), because a dashboard is a revision chain.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardEndpointsIT {

    private static final String V1 = "/api/console/v1/dashboards/";
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static Seeded seeded;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TelemetryIngestPort ingest;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        MonitoringItSupport.entitle(world, provisioned, 9);
    }

    private Http http() {
        return new Http(port);
    }

    private Seeded seeded() throws Exception {
        if (seeded == null) {
            seeded = MonitoringItSupport.seedInstances(http(), ingest, ownerJdbc, world, 10, 9311);
        }
        return seeded;
    }

    private String dashboard() throws Exception {
        return V1 + "db_" + seeded().a();
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MonitoringItSupport.json(response);
    }

    private static Map<String, String> rev(long revision) {
        return Map.of("X-SM-Dashboard-Revision", Long.toString(revision));
    }

    private ObjectNode widgetBody(String title, String metric) throws Exception {
        ObjectNode widget = MonitoringItSupport.MAPPER.createObjectNode();
        widget.put("schemaVersion", 1).put("title", title).put("mark", "line").put("layer", "shared");
        widget.putObject("options");
        ObjectNode query = widget.putObject("query");
        query.put("metric", metric);
        ObjectNode selector = query.putObject("selector");
        selector.put("entityType", "cache-engine");
        selector.putArray("instanceIds").add(seeded().a().toString());
        selector.putObject("labels");
        query.put("seriesBy", "instance");
        query.putObject("aggregation").put("acrossReplicas", "auto").put("acrossInstances", "none");
        query.putArray("groupBy");
        widget.putObject("range").put("preset", "24h");
        widget.put("resolution", "auto");
        widget.putObject("axes");
        widget.putObject("legend");
        widget.putObject("series");
        widget.putArray("thresholds");
        widget.putObject("refresh");
        return widget;
    }

    // ------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void dashboardRoutesUseTheDemoWireIdAndDocumentShape() throws Exception {
        Http http = http();
        HttpResponse<String> response = http.get(dashboard(), http.login(DeclareFixtures.VIEWER));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode document = json(response);
        assertEquals("db_" + seeded().a(), document.get("id").asText());
        assertEquals(1, document.get("schemaVersion").asInt());
        assertEquals(0, document.get("revision").asLong(), "nothing is stored yet");
        assertTrue(document.get("widgets").isArray());
        assertTrue(document.get("layout").isArray());
        assertEquals("24h", document.at("/range/preset").asText());
        assertTrue(document.has("refresh"));
        for (JsonNode widget : document.get("widgets")) {
            assertEquals("product-default", widget.get("layer").asText());
            assertTrue(widget.get("id").asText().matches("d_[0-9a-f]{12}"));
        }
        assertEquals(document.get("widgets").size(), document.get("layout").size(), "every widget is placed");
    }

    @Test
    @Order(2)
    void aWriteMustPresentTheRevisionItReadAndAStaleOneIs409() throws Exception {
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        String body = widgetBody("Cache size", "cache.size").toString();
        HttpResponse<String> missing = http.send("POST", dashboard() + "/widgets", operator, body, Map.of());
        assertEquals(400, missing.statusCode(), "no last-writer-wins form: " + missing.body());
        assertEquals(400, http.send("POST", dashboard() + "/widgets", operator, body, Map.of("X-SM-Dashboard-Revision", "abc"))
                .statusCode());
        assertEquals(400, http.send("POST", dashboard() + "/widgets", operator, body, Map.of("X-SM-Dashboard-Revision", "-1"))
                .statusCode());
        HttpResponse<String> saved = http.send("POST", dashboard() + "/widgets", operator, body, rev(0));
        assertEquals(200, saved.statusCode(), saved.body());
        JsonNode widget = json(saved);
        assertTrue(widget.get("id").asText().matches("w_[0-9a-f]{12}"), "the server assigns the id");
        assertEquals("shared", widget.get("layer").asText());
        assertEquals("Cache size", widget.get("title").asText());
        HttpResponse<String> stale = http.send("POST", dashboard() + "/widgets", operator, body, rev(0));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("CONFLICT", json(stale).get("code").asText());
        JsonNode now = json(http.get(dashboard(), operator));
        assertEquals(1, now.get("revision").asLong());
        assertTrue(StreamSupport(now.get("widgets")).anyMatch(w -> w.get("id").equals(widget.get("id"))));
    }

    private static java.util.stream.Stream<JsonNode> StreamSupport(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    @Test
    @Order(3)
    void aWidgetOfAnotherInstanceOrAnUndeclaredMetricIsRefused() throws Exception {
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode other = widgetBody("Other", "cache.size");
        ((ArrayNode) other.at("/query/selector/instanceIds")).removeAll().add(seeded().b().toString());
        assertEquals(400, http.send("POST", dashboard() + "/widgets", operator, other.toString(), rev(1)).statusCode());
        assertEquals(400, http.send("POST", dashboard() + "/widgets", operator,
                widgetBody("Undeclared", "cache.no_such").toString(), rev(1)).statusCode());
        assertEquals(1, json(http.get(dashboard(), operator)).get("revision").asLong(), "a refused write changes nothing");
    }

    @Test
    @Order(4)
    void theLayoutMustPlaceEveryWidgetExactlyOnce() throws Exception {
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        JsonNode current = json(http.get(dashboard(), operator));
        ObjectNode layout = MonitoringItSupport.MAPPER.createObjectNode();
        ArrayNode cells = layout.putArray("layout");
        int order = 0;
        for (JsonNode widget : current.get("widgets")) {
            cells.addObject().put("widgetId", widget.get("id").asText()).put("x", 0).put("y", order * 2).put("w", 12)
                    .put("h", 2).put("order", order++);
        }
        long revision = current.get("revision").asLong();
        HttpResponse<String> ok = http.send("PUT", dashboard() + "/layout", operator, layout.toString(), rev(revision));
        assertEquals(204, ok.statusCode(), ok.body());
        ObjectNode partial = layout.deepCopy();
        ((ArrayNode) partial.get("layout")).remove(0);
        assertEquals(400, http.send("PUT", dashboard() + "/layout", operator, partial.toString(), rev(revision + 1))
                .statusCode());
        assertEquals(400, http.send("PUT", dashboard() + "/layout", operator, "{\"layout\":[],\"extra\":1}",
                rev(revision + 1)).statusCode(), "additionalProperties: false");
        assertEquals(409, http.send("PUT", dashboard() + "/layout", operator, layout.toString(), rev(revision)).statusCode());
        JsonNode after = json(http.get(dashboard(), operator));
        assertEquals(12, after.at("/layout/0/w").asInt());
    }

    @Test
    @Order(5)
    void aProductDefaultWidgetIsHiddenNeverDeletedAndASharedOneIs404() throws Exception {
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        JsonNode current = json(http.get(dashboard(), operator));
        String defaultId = null;
        String sharedId = null;
        for (JsonNode widget : current.get("widgets")) {
            if ("product-default".equals(widget.get("layer").asText())) {
                defaultId = defaultId == null ? widget.get("id").asText() : defaultId;
            } else {
                sharedId = widget.get("id").asText();
            }
        }
        assertNotNull(sharedId);
        long revision = current.get("revision").asLong();
        if (defaultId != null) {
            HttpResponse<String> hidden = http.send("PATCH", dashboard() + "/widgets/" + defaultId, operator,
                    "{\"hidden\":true}", rev(revision));
            assertEquals(204, hidden.statusCode(), hidden.body());
            JsonNode after = json(http.get(dashboard(), operator));
            assertTrue(StreamSupport(after.get("widgets")).anyMatch(
                    w -> w.get("id").asText().equals(current.get("widgets").get(0).get("id").asText())), "never deleted");
            revision = after.get("revision").asLong();
        }
        assertEquals(404, http.send("PATCH", dashboard() + "/widgets/" + sharedId, operator, "{\"hidden\":true}", rev(revision))
                .statusCode(), "only a product-default widget can be hidden");
        assertEquals(404, http.send("PATCH", dashboard() + "/widgets/d_ffffffffffff", operator, "{\"hidden\":true}",
                rev(revision)).statusCode());
        assertEquals(400, http.send("PATCH", dashboard() + "/widgets/" + sharedId, operator, "{\"hidden\":true,\"x\":1}",
                rev(revision)).statusCode());
    }

    @Test
    @Order(6)
    void resetKeepsEverySharedWidgetAndReturnsTheDashboard() throws Exception {
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        JsonNode current = json(http.get(dashboard(), operator));
        HttpResponse<String> reset = http.send("POST", dashboard() + "/reset", operator, null,
                rev(current.get("revision").asLong()));
        assertEquals(200, reset.statusCode(), reset.body());
        JsonNode after = json(reset);
        assertEquals(current.get("revision").asLong() + 1, after.get("revision").asLong());
        long shared = StreamSupport(after.get("widgets")).filter(w -> "shared".equals(w.get("layer").asText())).count();
        assertEquals(1, shared, "OQ-8 (a): reset keeps every shared widget");
        assertTrue(StreamSupport(after.get("widgets")).noneMatch(w -> w.path("hidden").asBoolean(false)));
    }

    @Test
    @Order(7)
    void aDashboardIsReachableOnlyThroughItsOwnInstancesScope() throws Exception {
        Http http = http();
        Login operatorOne = http.login(DeclareFixtures.OPERATOR_ONE);
        // The other environment's instance: out of operator one's scope, indistinguishable from nonexistent.
        HttpResponse<String> other = http.get(V1 + "db_" + seeded().b(), operatorOne);
        HttpResponse<String> missing = http.get(V1 + "db_" + UUID.randomUUID(), operatorOne);
        assertEquals(404, other.statusCode());
        assertEquals(missing.statusCode(), other.statusCode());
        assertEquals(404, http.send("POST", V1 + "db_" + seeded().b() + "/reset", operatorOne, null, rev(0)).statusCode());
        // Another organization's administrator never reads this organization's dashboard.
        assertEquals(404, http.get(dashboard(), http.login(DeclareFixtures.FOREIGN)).statusCode());
        // A malformed id is the same 404, whatever the case or the shape.
        for (String bad : List.of("db_nope", "x_" + seeded().a(), "db_" + seeded().a().toString().toUpperCase(),
                "db_" + seeded().a() + "0", "db_")) {
            assertEquals(404, http.get(V1 + bad, operatorOne).statusCode(), bad);
        }
        assertFalse(other.body().contains(seeded().b().toString()));
        // Nothing was written for b or for any foreign id.
        assertEquals(0L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.dashboard WHERE instance_id = :i",
                Map.of("i", seeded().b()), Long.class));
    }

    @Test
    @Order(8)
    void everyDashboardWriteIsAuditedWithItsRevisionChain() throws Exception {
        seeded();
        Set<String> operations = new java.util.TreeSet<>(ownerJdbc.queryForList(
                "SELECT DISTINCT audit_operation FROM sm_control.change_audit WHERE table_name = 'dashboard'", Map.of(), String.class));
        assertTrue(operations.containsAll(Set.of("DASHBOARD_WIDGET_SAVED", "DASHBOARD_LAYOUT_SET", "DASHBOARD_WIDGET_HIDDEN", "DASHBOARD_RESET")),
                operations.toString());
    }
}
