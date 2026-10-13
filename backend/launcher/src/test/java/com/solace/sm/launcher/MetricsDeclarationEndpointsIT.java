package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Plan 011 P11-4.1 over the REAL boot: a declare carries the loaded package's metrics declaration to the data plane, and a
 * stage records the metric names its draft makes collectable. Real HTTP, the real catalog, the real projector, the real
 * control-plane evaluator, the real inventory and config of record, PostgreSQL. Nothing is faked.
 *
 * <p>State carries from test to test (ordered). The package is the Cache fixture with its restart signal moved to port
 * 9091, so that port is declared ONLY as the signal's (no metric reads it): the case where a direct-mode fetch of the
 * signal would otherwise have no host port.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsDeclarationEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final List<String> FIVE = List.of("cache.eviction_count", "cache.hit_count", "cache.hit_rate",
            "cache.miss_count", "cache.size");

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instanceA;
    private static long versionAfterDeclare;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SolutionCatalog catalog;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 9,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    private static String environmentPath(UUID environment) {
        return "/api/dp/environments/" + environment + "/instances";
    }

    private static ObjectNode declareBody(UUID target, String name, int healthPort, Map<String, Integer> metricsPorts) {
        ObjectNode body = DeclareFixtures.declareBody(solutionId, target, name, "sm-" + name, healthPort);
        // the shared fixture maps 8081 for every declare; this test says what it maps, or that it maps nothing
        ((ObjectNode) body.get("placement")).remove("metricsPorts");
        if (metricsPorts != null) {
            ObjectNode ports = ((ObjectNode) body.get("placement")).putObject("metricsPorts");
            metricsPorts.forEach(ports::put);
        }
        return body;
    }

    private static long count(String sql) {
        Long n = ownerJdbc.queryForObject(sql, Map.of("t", world.organization()), Long.class);
        return n == null ? 0 : n;
    }

    /** Every row either plane holds about this organization's declares and their metrics. */
    private static List<Long> everything() {
        List<Long> out = new ArrayList<>();
        for (String sql : List.of(
                "SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_data.package_version_digest WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_data.metric_definition WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_data.metric_restart_signal WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_data.placement_metrics_endpoint WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_data.generation_active_metrics WHERE tenant_id = :t",
                "SELECT count(*) FROM sm_control.instance WHERE organization_id = :t")) {
            out.add(count(sql));
        }
        return out;
    }

    private static Map<Integer, Integer> endpointsOf(UUID instance) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        ownerJdbc.query("SELECT e.source_port, e.host_port FROM sm_data.placement_metrics_endpoint e"
                + " JOIN sm_data.placement p ON p.tenant_id = e.tenant_id AND p.placement_id = e.placement_id"
                + " WHERE e.tenant_id = :t AND p.instance_id = :i ORDER BY e.source_port",
                Map.of("t", world.organization(), "i", instance), rs -> {
                    out.put(rs.getInt(1), rs.getInt(2));
                });
        return out;
    }

    private static List<String> activeNames(UUID instance, long generation) {
        String[] names = ownerJdbc.queryForObject("SELECT metric_names FROM sm_data.generation_active_metrics"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = :g",
                Map.of("t", world.organization(), "i", instance, "g", generation), (rs, n) -> {
                    try {
                        return (String[]) rs.getArray(1).getArray();
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
        return List.of(names);
    }

    // ------------------------------------------------------------------------------- the stage body

    private static ObjectNode stageBody(long base, String provider) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseStateVersion", base);
        body.putObject("componentSelection").putArray("instances").addObject().put("instanceKey", "i1")
                .put("type", "cache-engine");
        ObjectNode draft = body.putObject("draftValues").putObject("i1");
        draft.put("namespace", "orders").put("key-source", "TOPIC").put("max-size", 1000).put("provider", provider);
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("mode", "READ");
        rendered.put("provider", provider);
        rendered.put("namespace", "orders");
        rendered.put("key-source", "TOPIC");
        rendered.put("max-size", 1000);
        rendered.put("solace.enabled", false);
        for (Map.Entry<String, Object> entry : rendered.entrySet()) {
            body.withArray("rendered").addObject().put("instanceKey", "i1")
                    .put("renderedPath", "cache-engine." + entry.getKey()).set("value", MAPPER.valueToTree(entry.getValue()));
        }
        return body;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void declareAndStageAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        Client http = client();
        assertEquals(401, http.anonymous("POST", environmentPath(UUID.randomUUID())).statusCode());
        assertEquals(401, http.anonymous("POST", "/api/dp/instances/" + UUID.randomUUID() + "/config").statusCode());
    }

    @Test
    @Order(2)
    void aDeclareRecordsTheDefinitionsTheEndpointsAndGenerationZeroAndPassesTheCpsStoredDigestThrough() throws Exception {
        Client http = client();
        ObjectNode pkg = DeclareFixtures.cachePackage();
        ((ObjectNode) pkg.at("/components/0/restartSignal")).put("port", 9091);
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", pkg);
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        solutionId = UUID.fromString(body(loaded).get("solutionId").asText());
        String storedDigest = catalog.find(world.organization(), solutionId, "1").orElseThrow().digest();

        HttpResponse<String> declared = http.post(environmentPath(world.envOne()),
                http.login(DeclareFixtures.OPERATOR_ONE),
                declareBody(world.targetOne(), "metrics-a", 9101, Map.of("8081", 28081)));

        assertEquals(201, declared.statusCode(), declared.body());
        instanceA = UUID.fromString(body(declared).get("instanceId").asText());
        versionAfterDeclare = body(declared).get("stateVersion").asLong();
        assertEquals(storedDigest, ownerJdbc.queryForObject("SELECT package_digest FROM sm_data.package_version_digest"
                + " WHERE tenant_id = :t", Map.of("t", world.organization()), String.class),
                "the data plane holds the control plane's stored digest, which the gateway passed through and never computed");
        assertEquals(5, count("SELECT count(*) FROM sm_data.metric_definition WHERE tenant_id = :t"));
        assertEquals(1, count("SELECT count(*) FROM sm_data.metric_restart_signal WHERE tenant_id = :t"));
        assertEquals(Map.of(8081, 28081, 9091, 9091), endpointsOf(instanceA),
                "8081 is mapped; 9091 is the restart signal's alone and still has its row, defaulting to itself");
        assertEquals(FIVE, activeNames(instanceA, 0),
                "generation 0: provider defaults to caffeine, so the five caffeine-only metrics are active");
    }

    @Test
    @Order(3)
    void everyKindOfMetricsPortCollisionIs409WithItsOwnCodeAndNeitherPlaneIsWritten() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        List<Long> before = everything();

        // another live placement on the target maps this metrics host port
        HttpResponse<String> inUse = http.post(environmentPath(world.envOne()), operator,
                declareBody(world.targetOne(), "metrics-b1", 9102, Map.of("8081", 28081)));
        // this declare's health port is the metrics host port the first placement maps
        HttpResponse<String> reverse = http.post(environmentPath(world.envOne()), operator,
                declareBody(world.targetOne(), "metrics-b2", 28081, Map.of("8081", 28082)));
        // a metrics host port that is the first placement's health port
        HttpResponse<String> health = http.post(environmentPath(world.envOne()), operator,
                declareBody(world.targetOne(), "metrics-b3", 9103, Map.of("8081", 9101)));
        // two source ports to one host port
        HttpResponse<String> twice = http.post(environmentPath(world.envOne()), operator,
                declareBody(world.targetOne(), "metrics-b4", 9104, Map.of("8081", 29999, "9091", 29999)));

        for (HttpResponse<String> refused : List.of(inUse, reverse, health, twice)) {
            assertEquals(409, refused.statusCode(), refused.body());
            assertEquals("METRICS_PORT_CONFLICT", body(refused).get("code").asText(), refused.body());
            assertFalse(refused.body().contains("28081") || refused.body().contains("29999"),
                    "the class is named, never the port: " + refused.body());
        }
        assertEquals(before, everything(),
                "nothing on either plane: no instance, placement, definition, endpoint or list, and no control-plane node");
    }

    @Test
    @Order(4)
    void aMetricsPortKeyNoSourceUsesIs422AndWritesNothing() throws Exception {
        Client http = client();
        List<Long> before = everything();

        HttpResponse<String> refused = http.post(environmentPath(world.envOne()), http.login(DeclareFixtures.OPERATOR_ONE),
                declareBody(world.targetOne(), "metrics-c", 9105, Map.of("8083", 28083)));

        assertEquals(422, refused.statusCode(), refused.body());
        assertTrue(body(refused).get("detail").asText().contains("placement.metricsPorts"), refused.body());
        assertEquals(before, everything());
    }

    @Test
    @Order(5)
    void aSecondInstanceWithNoMetricsPortsTakesEverySourcePortAsItsOwnOnAnotherTarget() throws Exception {
        Client http = client();

        HttpResponse<String> declared = http.post(environmentPath(world.envTwo()),
                http.login(DeclareFixtures.OPERATOR_TWO), declareBody(world.targetTwo(), "metrics-d", 9106, null));

        assertEquals(201, declared.statusCode(), declared.body());
        UUID instanceD = UUID.fromString(body(declared).get("instanceId").asText());
        assertEquals(Map.of(8081, 8081, 9091, 9091), endpointsOf(instanceD));
        assertEquals(5, count("SELECT count(*) FROM sm_data.metric_definition WHERE tenant_id = :t"),
                "the version's definitions are copied once, whichever instance declares it");
        assertEquals(1, count("SELECT count(*) FROM sm_data.package_version_digest WHERE tenant_id = :t"));
        assertEquals(FIVE, activeNames(instanceD, 0));
    }

    @Test
    @Order(6)
    void aStageStoresTheNamesItsDraftMakesCollectableWithItsGenerationAndNeverEditsAnOlderList() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        // the draft says redis: none of the caffeine-only metrics applies to it
        HttpResponse<String> toRedis = http.post("/api/dp/instances/" + instanceA + "/config", operator,
                stageBody(versionAfterDeclare, "redis"));
        assertEquals(200, toRedis.statusCode(), toRedis.body());
        long generationOne = body(toRedis).get("generation").asLong();
        assertEquals(1, generationOne);
        assertEquals(List.of(), activeNames(instanceA, 1));

        // ... and back: the list is evaluated afresh over THIS draft, not carried from the previous generation
        HttpResponse<String> toCaffeine = http.post("/api/dp/instances/" + instanceA + "/config", operator,
                stageBody(body(toRedis).get("stateVersion").asLong(), "caffeine"));
        assertEquals(200, toCaffeine.statusCode(), toCaffeine.body());
        assertEquals(2, body(toCaffeine).get("generation").asLong());
        assertEquals(FIVE, activeNames(instanceA, 2));

        assertEquals(List.of(), activeNames(instanceA, 1), "an older generation's list is never edited");
        assertEquals(FIVE, activeNames(instanceA, 0), "and neither is the declare's");
    }
}
