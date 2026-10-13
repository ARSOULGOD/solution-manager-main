package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T8.1: {@code rollbackApply} is SERVED (OR-42, flagged: names {@code B/D12}) - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, orchestration,
 * data plane service, config of record and durable worker. Only the host is local (an in-memory adapter).
 *
 * <p>A rollback is a copy-forward of a prior generation as a NEW higher one, then a normal apply. State carries from
 * test to test (ordered): the generations and requests an earlier test left are what a later one rolls back to.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RollbackEndpointIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID instance;
    private static UUID placement;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static String path(String suffix) {
        return "/api/dp/instances/" + instance + suffix;
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static ObjectNode applyBody(long generation) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation);
        body.put("strategy", "manual");
        body.put("placementId", placement.toString());
        return body;
    }

    private static ObjectNode rollbackBody(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("strategy", strategy);
        body.put("placementId", placement.toString());
        return body;
    }

    private static ObjectNode toGeneration(long generation) {
        return rollbackBody("manual").put("generation", generation);
    }

    private long version(Client http, Login login) throws Exception {
        return body(http.get(path("/designer"), login)).get("stateVersion").asLong();
    }

    private Map<String, Object> key() {
        return Map.of("t", world.organization(), "i", instance);
    }

    private long generations() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private long stage(Client http, String namespace) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        return ApplyItSupport.stage(http, instance, version(http, operator), namespace);
    }

    private JsonNode apply(Client http, long generation) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> response = http.post(path("/apply"), operator, applyBody(generation));
        assertEquals(202, response.statusCode(), response.body());
        return ApplyItSupport.awaitTerminal(http, operator, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    private boolean sameContent(long a, long b) {
        return ownerJdbc.queryForObject("SELECT x.component_selection = y.component_selection"
                + " AND x.draft_values = y.draft_values AND x.rendered_entries = y.rendered_entries"
                + " FROM sm_data.config_generation x, sm_data.config_generation y"
                + " WHERE x.tenant_id = :t AND x.instance_id = :i AND x.generation = :a"
                + " AND y.tenant_id = :t AND y.instance_id = :i AND y.generation = :b",
                new MapSqlParameterSource(key()).addValue("a", a).addValue("b", b), Boolean.class);
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void rollbackIsReachableInTheProductionBoot() throws Exception {
        UUID any = UUID.randomUUID();

        assertEquals(401, client().anonymous("POST", "/api/dp/instances/" + any + "/apply/rollback").statusCode(),
                "a mapped, authenticated route: 401 with no session, not the 404 of an unserved path");
    }

    @Test
    @Order(2)
    void anInstanceHasTwoGenerationsBothAppliedInOrder() throws Exception {
        Client http = client();
        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);
        instance = declared.instance();
        placement = declared.placement();
        assertEquals(1, ApplyItSupport.stage(http, instance, declared.version(), "orders"));
        assertEquals("STAGED", apply(http, 1).get("state").asText());
        assertEquals(2, stage(http, "billing"));
        assertEquals("STAGED", apply(http, 2).get("state").asText());
        assertEquals(2, generations());
    }

    @Test
    @Order(3)
    void rollbackRequiresConfigRollbackAndAForeignCallerSeesA404() throws Exception {
        Client http = client();
        long before = generations();

        assertEquals(403, http.post(path("/apply/rollback"), http.login(DeclareFixtures.VIEWER), toGeneration(1))
                .statusCode(), "a viewer holds no config.rollback");
        for (String who : List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            assertEquals(404, http.post(path("/apply/rollback"), http.login(who), toGeneration(1)).statusCode(), who);
        }
        assertEquals(before, generations(), "nothing was copied forward");
    }

    @Test
    @Order(4)
    void aRollbackWithoutAPlacementIdIsRefused() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode absent = toGeneration(1);
        absent.remove("placementId");
        long before = generations();

        HttpResponse<String> response = http.post(path("/apply/rollback"), operator, absent);

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(404, http.post(path("/apply/rollback"), operator,
                toGeneration(1).put("placementId", UUID.randomUUID().toString())).statusCode());
        assertEquals(before, generations());
    }

    @Test
    @Order(5)
    void theScheduledTokenIs422AndAnAmbiguousTargetIs400AndNothingIsCopied() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = generations();

        HttpResponse<String> scheduled = http.post(path("/apply/rollback"), operator,
                rollbackBody("scheduled").put("generation", 1));
        assertEquals(422, scheduled.statusCode(), "scheduled (plan 012 T7): " + scheduled.body());
        assertEquals("VALIDATION_FAILED", body(scheduled).get("code").asText());
        assertEquals(400, http.post(path("/apply/rollback"), operator, rollbackBody("manual")).statusCode(),
                "neither applyId nor generation");
        assertEquals(400, http.post(path("/apply/rollback"), operator,
                toGeneration(1).put("applyId", UUID.randomUUID().toString())).statusCode(), "both");
        assertEquals(before, generations());
    }

    @Test
    @Order(6)
    void rollbackMintsAHigherGenerationWithTheTargetsContent() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post(path("/apply/rollback"), operator, toGeneration(1));

        assertEquals(202, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertEquals(2, accepted.size(), "stateVersion and applyId: " + accepted);
        assertEquals("/api/dp/instances/" + instance + "/apply/observe?applyId=" + accepted.get("applyId").asText(),
                response.headers().firstValue("Location").orElseThrow());
        assertEquals(3, generations(), "a new generation, not generation 1 re-admitted");
        assertTrue(sameContent(1, 3), "generation 3 carries generation 1's content");
        assertFalse(sameContent(2, 3));
        assertEquals("rollback", ownerJdbc.queryForObject("SELECT origin FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3", key(), String.class));
        assertEquals(1L, ownerJdbc.queryForObject("SELECT derived_from_generation FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3", key(), Long.class));

        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance, accepted.get("applyId").asText(),
                Duration.ofSeconds(20));
        assertEquals(3, landed.get("generation").asLong());
        assertEquals(placement.toString(), landed.get("placementId").asText());
        assertEquals("STAGED", landed.get("state").asText(), "rollbackUnderManualEndsStaged: " + landed);
    }

    @Test
    @Order(7)
    void aGenerationNeverWrittenToThatPlacementIsNotEligible() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long never = stage(http, "never-applied");     // staged in the config of record, no request took it anywhere
        long before = generations();

        HttpResponse<String> response = http.post(path("/apply/rollback"), operator, toGeneration(never));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertFalse(body(response).get("detail").asText().matches(".*\\b" + never + "\\b.*"),
                "the generation is not echoed: " + response.body());
        assertEquals(before, generations(), "a refused rollback copies nothing forward");
    }

    @Test
    @Order(8)
    void resolvedAppliedIsAnEligibleRollbackTargetOR108() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long resolved = stage(http, "resolved-applied");
        ownerJdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at, resolved_by,"
                        + " resolution_outcome, resolution_observed)"
                        + " VALUES (:a, :t, :i, :p, 'apply', :g, 'manual', 'operator-1', :now,"
                        + " 'INDETERMINATE_RESOLVED', :now, 'operator-2', 'applied',"
                        + " jsonb_build_object('stagedGeneration', CAST(:g AS bigint), 'replicas',"
                        + " jsonb_build_array(jsonb_build_object('ref', 'r1', 'restarted', false,"
                        + " 'observedHash', NULL))))",
                new MapSqlParameterSource(key()).addValue("a", UUID.randomUUID()).addValue("p", placement)
                        .addValue("g", resolved).addValue("now", OffsetDateTime.now()));
        stage(http, "after-resolved");                 // so the target is not the latest, and a copy is minted (OR-33 guard 1)
        long before = generations();

        HttpResponse<String> response = http.post(path("/apply/rollback"), operator, toGeneration(resolved));

        // OR-108 (7): confirmed on the host, so it is a target; the rollback copies it forward like any other
        assertEquals(202, response.statusCode(), response.body());
        assertEquals(before + 1, generations());
        long minted = ownerJdbc.queryForObject("SELECT max(generation) FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
        assertTrue(sameContent(resolved, minted), "the new generation carries the resolved one's content");
        ApplyItSupport.awaitTerminal(http, operator, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    @Test
    @Order(9)
    void withAnApplyIdTheTargetIsTheHighestEligibleGenerationBelowThatApply() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        // the request for generation 3 (the rollback of 1): below it, the eligible ones are 2 and 1 - so 2
        String three = ownerJdbc.queryForObject("SELECT apply_id::text FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i AND generation = 3", key(), String.class);
        ObjectNode body = rollbackBody("manual").put("applyId", three);

        HttpResponse<String> response = http.post(path("/apply/rollback"), operator, body);

        assertEquals(202, response.statusCode(), response.body());
        long minted = ownerJdbc.queryForObject("SELECT max(generation) FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
        assertTrue(minted > 3);
        assertTrue(sameContent(2, minted), "undoing the apply of generation 3 lands generation 2's content");
        assertEquals(2L, ownerJdbc.queryForObject("SELECT derived_from_generation FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = :g",
                new MapSqlParameterSource(key()).addValue("g", minted), Long.class));
        ApplyItSupport.awaitTerminal(http, operator, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    // ------------------------------------------------------ plan 012 T2: the two ungated strategies (OR-121)

    @Test
    @Order(10)
    void aRestartAllRollbackNeedsAConfirmationMintsNothingWithoutOneAndStoresThePrincipalWithOne() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = generations();

        HttpResponse<String> unconfirmed = http.post(path("/apply/rollback"), operator,
                rollbackBody("restart-all").put("generation", 1));
        assertEquals(400, unconfirmed.statusCode(), unconfirmed.body());
        assertEquals(403, http.post(path("/apply/rollback"), http.login(DeclareFixtures.VIEWER),
                rollbackBody("restart-all").put("generation", 1).put("outageConfirmedBy", "x")).statusCode());
        assertEquals(before, generations(), "a refused restart-all rollback mints no generation (design 5.3, F7)");

        HttpResponse<String> response = http.post(path("/apply/rollback"), operator,
                rollbackBody("restart-all").put("generation", 1).put("outageConfirmedBy", "SENTINEL-typed-name"));

        assertEquals(202, response.statusCode(), response.body());
        String applyId = body(response).get("applyId").asText();
        String principal = ownerJdbc.queryForObject("SELECT id::text FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.OPERATOR_ONE), String.class);
        assertEquals("restart-all", ownerJdbc.queryForObject("SELECT strategy FROM sm_data.apply_request"
                + " WHERE apply_id = :a", Map.of("a", UUID.fromString(applyId)), String.class));
        assertEquals(principal, ownerJdbc.queryForObject("SELECT outage_confirmed_by FROM sm_data.apply_request"
                + " WHERE apply_id = :a", Map.of("a", UUID.fromString(applyId)), String.class),
                "the principal, never the typed string (OPS-Q7 C-2)");
        assertEquals(before + 1, generations(), "the rollback copied one generation forward");
        ApplyItSupport.awaitTerminal(http, operator, instance, applyId, Duration.ofSeconds(20));
    }
}
