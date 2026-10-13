package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.solace.sm.launcher.MonitoringItSupport.Http;
import com.solace.sm.launcher.MonitoringItSupport.Seeded;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * Plan 011 P11-7.2 over the REAL boot: the discovery, catalog, query and refusal routes of {@code /api/console/v1}, served
 * (before this task every one of them answered {@code 404}, measured by the Postman lane). Real HTTP, the real catalog, real
 * declare, the real reach authorization over real role bindings, the real ingest seam and real stored samples, PostgreSQL.
 * Only the {@code Origin} header is the test's.
 *
 * <p>State carries from test to test (ordered). Two instances: {@code a} in the first environment (its operator's reach),
 * {@code b} in the second.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsEndpointsIT {

    private static final String V1 = "/api/console/v1";
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static Seeded seeded;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TelemetryIngestPort ingest;

    @Autowired
    private com.solace.sm.dp.metrics.query.QueryProperties dataPlaneLimits;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        // Non-default limits, so a gateway that stated its own would disagree with the data plane's.
        registry.add("sm.dp.metrics.query.series-cap", () -> 7);
        registry.add("sm.dp.metrics.query.max-points-per-series", () -> 900);
        registry.add("sm.dp.metrics.query.raw-retention", () -> "100d");
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        MonitoringItSupport.seedNobody(ownerJdbc, world);
        MonitoringItSupport.entitle(world, provisioned, 9);
    }

    private Http http() {
        return new Http(port);
    }

    private Seeded seeded() throws Exception {
        if (seeded == null) {
            seeded = MonitoringItSupport.seedInstances(http(), ingest, ownerJdbc, world, 40, 9301);
        }
        return seeded;
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MonitoringItSupport.json(response);
    }

    private static ObjectNode query(Seeded s, String metric, String resolution, String across, UUID... ids) {
        ObjectNode request = MonitoringItSupport.MAPPER.createObjectNode();
        request.put("queryVersion", 1);
        request.put("metric", metric);
        ObjectNode selector = request.putObject("selector");
        selector.put("entityType", "cache-engine");
        for (UUID id : ids) {
            selector.withArray("instanceIds").add(id.toString());
        }
        selector.putObject("labels");
        request.putObject("range").put("from", s.start().minusSeconds(60).toString()).put("to",
                Instant.now().toString());
        request.put("resolution", resolution);
        request.put("seriesBy", "instance");
        request.putObject("aggregation").put("acrossReplicas", "auto").put("acrossInstances", across);
        return request;
    }

    // ------------------------------------------------------------------------------------------------- the routes

    private record Route(String operation, String method, String path, int expectedWithNoSession) {
    }

    private List<Route> routes(Seeded s) {
        String db = "db_" + s.a();
        List<Route> out = new ArrayList<>();
        out.add(new Route("getCapabilities", "GET", V1 + "/capabilities", 401));
        out.add(new Route("listEntityTypes", "GET", V1 + "/entity-types", 401));
        out.add(new Route("listMetricInstances", "GET", V1 + "/entity-types/cache-engine/instances", 401));
        out.add(new Route("getMetricsCatalog", "GET", V1 + "/metrics/catalog", 401));
        out.add(new Route("getLabelValues", "GET", V1 + "/labels/region/values", 401));
        out.add(new Route("queryRange", "POST", V1 + "/query/range", 401));
        out.add(new Route("queryInstant", "POST", V1 + "/query/instant", 401));
        out.add(new Route("exportRange", "GET", V1 + "/export/range?metric=cache.size&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z", 401));
        out.add(new Route("declareMetricType", "POST", V1 + "/metrics/catalog/cache.size/type", 401));
        out.add(new Route("getDashboard", "GET", V1 + "/dashboards/" + db, 401));
        out.add(new Route("saveWidget", "POST", V1 + "/dashboards/" + db + "/widgets", 401));
        out.add(new Route("setDashboardLayout", "PUT", V1 + "/dashboards/" + db + "/layout", 401));
        out.add(new Route("setWidgetHidden", "PATCH", V1 + "/dashboards/" + db + "/widgets/d_000000000000", 401));
        out.add(new Route("resetDashboard", "POST", V1 + "/dashboards/" + db + "/reset", 401));
        return out;
    }

    @Test
    @Order(1)
    void noMonitoringRouteIsUnmappedAndNoneServesWithoutASession() throws Exception {
        Seeded s = seeded();
        List<String> wrong = new ArrayList<>();
        for (Route route : routes(s)) {
            int status = http().anonymous(route.method(), route.path()).statusCode();
            if (status != route.expectedWithNoSession()) {
                wrong.add(route.operation() + " answered " + status + " with no session");
            }
        }
        assertEquals(List.of(), wrong, "each of the 14 contracted routes is mapped (404 is the unmapped answer)");
    }

    @Test
    @Order(2)
    void aPathNoContractNamesIsTheGatewaysOwn404NeverTheDemo() throws Exception {
        Login admin = http().login(DeclareFixtures.ADMIN);
        HttpResponse<String> response = http().get(V1 + "/no-such-route", admin);
        assertEquals(404, response.statusCode());
        assertEquals("NOT_FOUND", body(response).get("code").asText());
    }

    @Test
    @Order(3)
    void eachOfTheFourteenOperationsRequiresItsMappedVerbAndScope() throws Exception {
        Seeded s = seeded();
        Http http = http();
        // A principal bound to nothing holds no verb anywhere.
        Login nobody = http.login(MonitoringItSupport.NOBODY);
        assertEquals(200, http.get(V1 + "/capabilities", nobody).statusCode(), "row 53 is authentication only");
        for (String path : List.of("/entity-types", "/entity-types/cache-engine/instances", "/metrics/catalog",
                "/labels/region/values")) {
            assertEquals(403, http.get(V1 + path, nobody).statusCode(), path + " needs metrics.query somewhere");
        }
        assertEquals(403, http.post(V1 + "/query/range", nobody, query(s, "cache.size", "auto", "none", s.a())).statusCode());
        assertEquals(403, http.post(V1 + "/query/instant", nobody, query(s, "cache.size", "auto", "sum", s.a())).statusCode());
        assertEquals(403, http.get(V1 + "/export/range?metric=cache.size&from=" + s.start() + "&to=" + Instant.now(), nobody)
                .statusCode());
        ObjectNode type = MonitoringItSupport.MAPPER.createObjectNode().put("metricType", "gauge").put("unit", "count");
        assertTrue(Set.of(403, 404).contains(http.post(V1 + "/metrics/catalog/cache.size/type", nobody, type).statusCode()),
                "metric.declare is held at the organization; a principal with no binding there does not even see it");
        assertTrue(Set.of(403, 404).contains(http.get(V1 + "/dashboards/db_" + s.a(), nobody).statusCode()),
                "a dashboard is authorized at its instance");

        // A viewer may look, never export, never force raw, never edit, never declare.
        Login viewer = http.login(DeclareFixtures.VIEWER);
        assertEquals(200, http.get(V1 + "/entity-types", viewer).statusCode());
        assertEquals(200, http.post(V1 + "/query/range", viewer, query(s, "cache.size", "auto", "none", s.a())).statusCode());
        assertEquals(403, http.post(V1 + "/query/range", viewer, query(s, "cache.size", "raw", "none", s.a())).statusCode(),
                "raw also needs metrics.forceRaw");
        assertEquals(403, http.get(V1 + "/export/range?metric=cache.size&from=" + s.start() + "&to=" + Instant.now(), viewer)
                .statusCode());
        assertEquals(403, http.post(V1 + "/metrics/catalog/cache.size/type", viewer, type).statusCode());
        assertEquals(200, http.get(V1 + "/dashboards/db_" + s.a(), viewer).statusCode());
        Map<String, String> revision = Map.of("X-SM-Dashboard-Revision", "0");
        assertEquals(403, http.send("POST", V1 + "/dashboards/db_" + s.a() + "/widgets", viewer, "{}", revision).statusCode());
        assertEquals(403, http.send("PUT", V1 + "/dashboards/db_" + s.a() + "/layout", viewer, "{\"layout\":[]}", revision)
                .statusCode());
        assertEquals(403, http.send("POST", V1 + "/dashboards/db_" + s.a() + "/reset", viewer, null, revision).statusCode());
    }

    @Test
    @Order(4)
    void anOperatorMayExportAndForceRaw() throws Exception {
        // plan 009's test, INVERTED (M-Q16): the Operator holds metrics.export and metrics.forceRaw.
        Seeded s = seeded();
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> raw = http.post(V1 + "/query/range", operator, query(s, "cache.size", "raw", "none", s.a()));
        assertEquals(200, raw.statusCode(), raw.body());
        assertEquals("raw", body(raw).at("/resolution/served").asText());
        HttpResponse<String> export = http.get(V1 + "/export/range?metric=cache.size&from=" + s.start() + "&to="
                + Instant.now() + "&instanceId=" + s.a(), operator);
        assertEquals(200, export.statusCode(), export.body());
    }

    // -------------------------------------------------------------------------------------------------- discovery

    @Test
    @Order(5)
    void capabilitiesCarryTheCollectorBlockAndNoByteFigure() throws Exception {
        seeded();
        Http http = http();
        JsonNode capabilities = body(http.get(V1 + "/capabilities", http.login(DeclareFixtures.VIEWER)));
        assertEquals(1, capabilities.get("queryVersion").asInt());
        assertEquals(Set.of("raw", "hourly", "daily"), names(capabilities.get("tiers")));
        assertTrue(capabilities.get("tiers").get(2).get("retention").isNull(), "the daily tier is unlimited");
        JsonNode collector = capabilities.get("collector");
        String state = collector.get("state").asText();
        assertTrue(Set.of("live", "degraded", "unknown").contains(state));
        // OR-112 (6), W4-05: what is unmeasured is ABSENT (never a stand-in), and "live" is only ever claimed over both
        if (state.equals("live")) {
            assertTrue(collector.has("runwayEnd") && collector.has("disk"), "live is claimed only over a full measurement");
        }
        if (state.equals("unknown")) {
            assertFalse(collector.has("runwayEnd") && collector.has("disk"), "unknown means something is unmeasured");
        }
        if (collector.has("runwayEnd")) {
            assertNotNull(java.time.LocalDate.parse(collector.get("runwayEnd").asText()));
        }
        assertTrue(collector.get("reasons").isArray());
        if (collector.has("disk")) {
            assertTrue(Set.of("ok", "warning", "critical", "unconfigured").contains(collector.at("/disk/state").asText()));
        }
        String text = capabilities.toString().toLowerCase();
        for (String forbidden : List.of("bytes", "size", "budget")) {
            assertFalse(text.contains(forbidden), "no byte figure may appear in capabilities: " + forbidden);
        }
        assertEquals(Set.of("state"), fieldNames(collector.get("disk")), "disk is a state and nothing else");
    }

    @Test
    @Order(14)
    void capabilitiesAdvertiseTheLimitsTheDataPlaneEnforces() throws Exception {
        seeded();
        Http http = http();
        JsonNode capabilities = body(http.get(V1 + "/capabilities", http.login(DeclareFixtures.VIEWER)));
        assertEquals(dataPlaneLimits.seriesCap(), capabilities.get("maxSeriesPerWidget").asInt());
        assertEquals(dataPlaneLimits.maxPointsPerSeries(), capabilities.get("maxPointsPerSeries").asInt());
        assertEquals("P" + dataPlaneLimits.rawRetention().toDays() + "D", capabilities.at("/tiers/0/retention").asText());
        assertEquals(7, capabilities.get("maxSeriesPerWidget").asInt(), "the configured value, not a literal");
        assertEquals("P100D", capabilities.at("/tiers/0/retention").asText());
    }

    @Test
    @Order(6)
    void entityTypesAndInstancesAreReachFiltered() throws Exception {
        Seeded s = seeded();
        Http http = http();
        Login operatorOne = http.login(DeclareFixtures.OPERATOR_ONE);
        JsonNode types = body(http.get(V1 + "/entity-types", operatorOne));
        assertEquals(1, types.size());
        assertEquals("cache-engine", types.get(0).get("id").asText());
        JsonNode mine = body(http.get(V1 + "/entity-types/cache-engine/instances", operatorOne));
        assertEquals(1, mine.get("total").asInt(), "an operator bound to one environment reaches one instance, never a 403");
        assertEquals(s.a().toString(), mine.at("/items/0/id").asText());
        assertEquals(1, mine.at("/items/0/replicaCount").asInt());
        JsonNode all = body(http.get(V1 + "/entity-types/cache-engine/instances", http.login(DeclareFixtures.VIEWER)));
        assertEquals(2, all.get("total").asInt());
        assertEquals(1, body(http.get(V1 + "/entity-types/cache-engine/instances?search=MON-B",
                http.login(DeclareFixtures.VIEWER))).get("total").asInt(), "search is a case-insensitive substring");
        assertEquals(0, body(http.get(V1 + "/entity-types/nothing/instances", operatorOne)).get("total").asInt(),
                "an entity type nobody can see is an empty list, never a 404");
        // The other organization's administrator reaches nothing of this one.
        JsonNode foreign = body(http.get(V1 + "/entity-types", http.login(DeclareFixtures.FOREIGN)));
        assertEquals(0, foreign.size());
        assertEquals(200, http.get(V1 + "/labels/region/values", operatorOne).statusCode());
        assertEquals("[]", http.get(V1 + "/labels/region/values", operatorOne).body(), "the v1 label allowlist is empty");
    }

    @Test
    @Order(7)
    void theCatalogListsDeclaredMetricsWithWhatIsObserved() throws Exception {
        Seeded s = seeded();
        Http http = http();
        JsonNode catalog = body(http.get(V1 + "/metrics/catalog?entityType=cache-engine", http.login(DeclareFixtures.OPERATOR_ONE)));
        assertTrue(catalog.isArray(), "a bare array, as the demo serves it");
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        catalog.forEach(entry -> byName.put(entry.get("name").asText(), entry));
        assertEquals(new TreeSet<>(List.of("cache.eviction_count", "cache.hit_count", "cache.hit_rate", "cache.miss_count",
                "cache.size")), new TreeSet<>(byName.keySet()));
        JsonNode size = byName.get("cache.size");
        assertEquals("plottable", size.get("state").asText());
        assertEquals("package-baseline", size.get("layer").asText());
        assertEquals("live", size.at("/observedAvailability/state").asText());
        assertTrue(size.get("lastSeen").asLong() > s.start().toEpochMilli() - 1, "epoch milliseconds of the newest sample");
        assertEquals("placements-serving-this-metric", size.at("/collector/scope").asText());
        assertEquals("durable", size.get("durability").asText());
        assertEquals(0, size.get("labelKeys").size());
        JsonNode hits = byName.get("cache.hit_count");
        assertTrue(Set.of("declared", "docker", "inferred").contains(hits.get("restartDetection").asText()));
        assertFalse(size.has("restartDetection"), "restart detection is a counter's");
        JsonNode neverSeen = byName.get("cache.miss_count");
        assertFalse(neverSeen.has("lastSeen"), "never observed: absent, never zero");
        assertFalse(neverSeen.has("collector"));
        assertEquals("live", neverSeen.at("/observedAvailability/state").asText(), "never observed is live and empty");
    }

    // ----------------------------------------------------------------------------------------------------- queries

    @Test
    @Order(8)
    void aRangeQueryAnswersTheContractsShape() throws Exception {
        Seeded s = seeded();
        Http http = http();
        HttpResponse<String> response = http.post(V1 + "/query/range", http.login(DeclareFixtures.VIEWER),
                query(s, "cache.size", "auto", "none", s.a(), s.b()));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = body(response);
        assertEquals(1, result.get("queryVersion").asInt());
        assertEquals("cache.size", result.at("/metric/name").asText());
        assertEquals("gauge", result.at("/metric/metricType").asText());
        assertEquals("none", result.at("/render/transform").asText());
        assertEquals("raw", result.at("/resolution/served").asText());
        assertEquals(2, result.get("series").size());
        JsonNode first = result.at("/series/0");
        assertEquals(4, first.at("/points/0").size(), "[timestamp, value, sampleCount, expectedCount]");
        assertTrue(first.at("/points").size() >= 30);
        assertTrue(first.at("/seriesRef/placementId").isNull() || first.at("/seriesRef/placementId").isTextual());
        assertTrue(result.at("/partial/unavailable").isArray());
    }

    @Test
    @Order(9)
    void anInstantQueryAnswersTheLatestPointPerSeries() throws Exception {
        Seeded s = seeded();
        Http http = http();
        HttpResponse<String> response = http.post(V1 + "/query/instant", http.login(DeclareFixtures.VIEWER),
                query(s, "cache.size", "auto", "sum", s.a(), s.b()));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode series = body(response).get("series");
        assertEquals(1, series.size(), "an aggregation across instances is one line");
        assertEquals(1, series.get(0).get("points").size());
        // OR-112 (6), W4-06: the aggregate belongs to no instance: null (present, like placementId), never the nil UUID
        assertTrue(series.get(0).at("/seriesRef").has("instanceId"));
        assertTrue(series.get(0).at("/seriesRef/instanceId").isNull(), series.get(0).at("/seriesRef").toString());
        assertTrue(series.get(0).at("/seriesRef/placementId").isNull());
    }

    @Test
    @Order(10)
    void unauthorizedInstancesAreOmittedLikeNonexistentOnes() throws Exception {
        // Review Focus 4 (M-Q17, OR-79): operator one reaches instance a only. Asking for a and b, or a and an id that
        // does not exist, must be the same answer to the byte (but for the correlation id): never a 403, never a hint.
        Seeded s = seeded();
        Http http = http();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> withForeign = http.post(V1 + "/query/range", operator, query(s, "cache.size", "auto", "none", s.a(), s.b()));
        HttpResponse<String> withMissing = http.post(V1 + "/query/range", operator,
                query(s, "cache.size", "auto", "none", s.a(), UUID.randomUUID()));
        assertEquals(200, withForeign.statusCode(), withForeign.body());
        assertEquals(body(withMissing), body(withForeign));
        assertEquals(1, body(withForeign).get("series").size());
        assertFalse(withForeign.body().contains(s.b().toString()), "an instance out of reach is never named");
        // Only ids out of reach: the same refusal as only ids that do not exist.
        HttpResponse<String> onlyForeign = http.post(V1 + "/query/range", operator, query(s, "cache.size", "auto", "none", s.b()));
        HttpResponse<String> onlyMissing = http.post(V1 + "/query/range", operator,
                query(s, "cache.size", "auto", "none", UUID.randomUUID()));
        assertEquals(onlyMissing.statusCode(), onlyForeign.statusCode(), onlyForeign.body());
        assertEquals(body(onlyMissing).get("code"), body(onlyForeign).get("code"));
        assertFalse(onlyForeign.body().contains(s.b().toString()));
        // Another organization's administrator holds the verb everywhere in ITS organization and reaches none of ours.
        HttpResponse<String> foreign = http.post(V1 + "/query/range", http.login(DeclareFixtures.FOREIGN),
                query(s, "cache.size", "auto", "none", s.a()));
        assertEquals(onlyMissing.statusCode(), foreign.statusCode());
    }

    @Test
    @Order(11)
    void theRefusalsAreTheContractsCodes() throws Exception {
        Seeded s = seeded();
        Http http = http();
        Login viewer = http.login(DeclareFixtures.VIEWER);
        // groupBy without aggregation is 400 in v1 (the label allowlist is empty), a list of metrics is 400.
        ObjectNode twoMetrics = query(s, "cache.size", "auto", "none", s.a());
        twoMetrics.putArray("metric").add("cache.size").add("cache.hit_count");
        assertEquals(400, http.post(V1 + "/query/range", viewer, twoMetrics).statusCode());
        ObjectNode strayKey = query(s, "cache.size", "auto", "none", s.a());
        strayKey.put("tenant", world.organization().toString());
        assertEquals(400, http.post(V1 + "/query/range", viewer, strayKey).statusCode(), "no organization, no stray key");
        ObjectNode label = query(s, "cache.size", "auto", "none", s.a());
        ((ObjectNode) label.get("selector").get("labels")).put("region", "eu");
        assertEquals(400, http.post(V1 + "/query/range", viewer, label).statusCode());
        // A single value over more than one instance with no aggregation: 422 STAT_REQUIRES_AGGREGATION.
        HttpResponse<String> stat = http.post(V1 + "/query/instant", http.login(DeclareFixtures.ADMIN),
                query(s, "cache.size", "auto", "none", s.a(), s.b()));
        assertEquals(422, stat.statusCode(), stat.body());
        assertEquals("STAT_REQUIRES_AGGREGATION", body(stat).get("code").asText());
        assertEquals("Single-stat requires an aggregation", body(stat).get("title").asText());
    }

    @Test
    @Order(12)
    void metricDeclareIs501ForEveryCallerWhoHoldsTheVerb() throws Exception {
        seeded();
        Http http = http();
        ObjectNode type = MonitoringItSupport.MAPPER.createObjectNode().put("metricType", "gauge").put("unit", "count");
        // metric.declare is held AT THE ORGANIZATION: an operator bound to one environment does not hold it (403, never 501).
        assertEquals(403, http.post(V1 + "/metrics/catalog/cache.size/type", http.login(DeclareFixtures.OPERATOR_ONE), type)
                .statusCode());
        HttpResponse<String> response = http.post(V1 + "/metrics/catalog/cache.size/type", http.login(DeclareFixtures.ADMIN), type);
        assertEquals(501, response.statusCode(), response.body());
        assertEquals("NOT_IMPLEMENTED", body(response).get("code").asText());
        assertEquals("metric-declare-deferred", body(response).get("reason").asText());
        ObjectNode malformed = MonitoringItSupport.MAPPER.createObjectNode().put("metricType", "histogram").put("unit", "count");
        assertEquals(400, http.post(V1 + "/metrics/catalog/cache.size/type", http.login(DeclareFixtures.ADMIN), malformed)
                .statusCode());
    }

    @Test
    @Order(13)
    void theQueryRateLimitIs429WithRetryAfterAndItsReason() throws Exception {
        Seeded s = seeded();
        Http http = http();
        Login session = http.login(DeclareFixtures.VIEWER);
        HttpResponse<String> limited = null;
        for (int i = 0; i < 60 && limited == null; i++) {
            HttpResponse<String> response = http.post(V1 + "/query/range", session, query(s, "cache.size", "auto", "none", s.a()));
            if (response.statusCode() == 429) {
                limited = response;
            }
        }
        assertNotNull(limited, "60 requests in a burst exceed 10 per second");
        assertEquals("RATE_LIMITED", body(limited).get("code").asText());
        assertEquals("query-rate", body(limited).get("reason").asText());
        assertEquals("1", limited.headers().firstValue("Retry-After").orElseThrow());
        // Another session is not limited by this one.
        assertEquals(200, http.post(V1 + "/query/range", http.login(DeclareFixtures.OPERATOR_ONE),
                query(s, "cache.size", "auto", "none", s.a())).statusCode());
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(node -> out.add(node.get("name").asText()));
        return out;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
