package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T10.5: {@code getHomeSummary} and {@code getHomeAttention} are SERVED in the production boot - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, the real catalog (control plane), the real
 * persisted rows (data plane) and the real reach authorization. The poller is off (the launcher default), so the
 * health and apply rows are seeded and what is proven is the composition end to end: the catalog row, the counts, the
 * per-instance and per-reason filtering by the caller's real grants, and the wire.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HomeEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final String SUMMARY = "/api/cp/home/summary";
    private static final String ATTENTION = "/api/cp/home/attention";

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instance;
    private static UUID placement;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private JsonNode get(String path, String user) throws Exception {
        HttpResponse<String> response = client().get(path, client().login(user));
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private static JsonNode rowOf(JsonNode summary, UUID solution) {
        for (JsonNode row : summary.get("solutions")) {
            if (row.get("solutionId").asText().equals(solution.toString())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + solution + " in " + summary);
    }

    @Test
    @Order(1)
    void bothOperationsAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        assertEquals(401, client().anonymous("GET", SUMMARY).statusCode());
        assertEquals(401, client().anonymous("GET", ATTENTION).statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredAndGivenAHealthStateAndADivergedRequest() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());

        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", 9090));
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode json = MAPPER.readTree(declared.body());
        instance = UUID.fromString(json.get("instanceId").asText());
        placement = UUID.fromString(json.at("/placements/0/placementId").asText());

        Map<String, Object> ids = Map.of("t", world.organization(), "i", instance, "p", placement);
        ownerJdbc.update("INSERT INTO sm_data.placement_health (tenant_id, placement_id, instance_id, state,"
                + " checked_at, changed_at) VALUES (:t, :p, :i, 'RED', now(), now())", ids);
        ownerJdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                + " ON CONFLICT DO NOTHING", ids);
        ownerJdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id,"
                + " operation, generation, strategy, requested_by, requested_at, state, terminal_at)"
                + " VALUES (gen_random_uuid(), :t, :i, :p, 'apply', 1, 'manual', 'operator-1', now(),"
                + " 'CONFIG_DIVERGED', now())", ids);
    }

    @Test
    @Order(3)
    void theAdministratorSeesTheCatalogRowItsCountAndItsHealthBucket() throws Exception {
        JsonNode summary = get(SUMMARY, DeclareFixtures.ADMIN);

        JsonNode row = rowOf(summary, solutionId);
        assertEquals(1, row.get("instanceCount").asInt());
        assertEquals(1, row.at("/byHealth/RED").asInt());
        assertTrue(row.get("stateVersion").asLong() > 0, "the catalog row's own stamp");
        assertFalse(summary.has("stateVersion"), "OR-55: no top-level stateVersion");
    }

    @Test
    @Order(4)
    void aSiblingEnvironmentsOperatorGetsItsOwnCountsNot403() throws Exception {
        // OR-51: the operator holds the verbs in another environment only. It reaches the handler and counts nothing
        // of the first environment's instance - and the catalog row is still listed.
        JsonNode summary = get(SUMMARY, DeclareFixtures.OPERATOR_TWO);

        JsonNode row = rowOf(summary, solutionId);
        assertEquals(0, row.get("instanceCount").asInt(), "an instance the caller cannot see is not counted");
        assertFalse(row.has("byHealth"));
    }

    @Test
    @Order(5)
    void aForeignOrganizationSeesNoneOfIt() throws Exception {
        JsonNode summary = get(SUMMARY, DeclareFixtures.FOREIGN);

        assertEquals(0, summary.get("solutions").size(), "I5: the catalog and the instances are the session's own");
        assertEquals(0, get(ATTENTION, DeclareFixtures.FOREIGN).get("items").size());
    }

    @Test
    @Order(6)
    void theAdministratorsAttentionListHasTheUnhealthyPlacementAndTheDivergedRequestEachWithItsOwnVersion()
            throws Exception {
        JsonNode attention = get(ATTENTION, DeclareFixtures.ADMIN);

        JsonNode items = attention.get("items");
        assertEquals(new TreeSet<>(java.util.Set.of("health", "config-diverged")), reasons(items), attention.toString());
        for (JsonNode item : items) {
            assertEquals(instance.toString(), item.get("instanceId").asText());
            assertEquals(placement.toString(), item.get("placementId").asText());
            assertTrue(item.get("stateVersion").asLong() > 0, "each item carries its own version (OR-60)");
            if (item.get("reason").asText().equals("health")) {
                assertEquals("RED", item.get("state").asText());
            } else {
                assertFalse(item.has("state"));
            }
        }
        assertFalse(attention.has("stateVersion"));
    }

    @Test
    @Order(7)
    void aSiblingEnvironmentsOperatorSeesNoAttentionItemForAnInstanceItCannotView() throws Exception {
        assertEquals(0, get(ATTENTION, DeclareFixtures.OPERATOR_TWO).get("items").size());
    }

    private static TreeSet<String> reasons(JsonNode items) {
        TreeSet<String> out = new TreeSet<>();
        items.forEach(item -> out.add(item.get("reason").asText()));
        return out;
    }
}
