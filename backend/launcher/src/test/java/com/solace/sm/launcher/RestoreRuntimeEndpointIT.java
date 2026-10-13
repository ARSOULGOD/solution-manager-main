package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * Plan 010 T10.2: {@code restoreRuntimeConfig} is SERVED (OR-45, {@code B/D12}'s manual drift reconcile) - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, orchestration, data
 * plane service, config of record and durable worker. Only the host is local (an in-memory adapter, whose file the test
 * can alter to stand in for drift).
 *
 * <p>A restore is a copy-forward of the CURRENT DESIRED generation as a NEW higher one, then a normal apply. State carries
 * from test to test (ordered).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestoreRuntimeEndpointIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final String DRIFT = "hmacsha256v1:" + "d".repeat(64);

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

    private static String path(UUID of, String suffix) {
        return "/api/dp/instances/" + of + suffix;
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static ObjectNode restoreBody(UUID onPlacement) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("placementId", onPlacement.toString());
        return body;
    }

    private static ObjectNode applyBody(long generation) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation);
        body.put("strategy", "manual");
        body.put("placementId", placement.toString());
        return body;
    }

    private Map<String, Object> key() {
        return Map.of("t", world.organization(), "i", instance);
    }

    private long generations() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private long version(Client http, Login login) throws Exception {
        return body(http.get(path(instance, "/designer"), login)).get("stateVersion").asLong();
    }

    private long stage(Client http, String namespace) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        return ApplyItSupport.stage(http, instance, version(http, operator), namespace);
    }

    private JsonNode apply(Client http, long generation) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> response = http.post(path(instance, "/apply"), operator, applyBody(generation));
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

    private String column(String column, long generation) {
        return ownerJdbc.queryForObject("SELECT " + column + "::text FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i AND generation = :g", new MapSqlParameterSource(key()).addValue("g", generation),
                String.class);
    }

    private JsonNode ladder(Client http) throws Exception {
        HttpResponse<String> response = http.get(path(instance, "/config-ladder"),
                http.login(DeclareFixtures.OPERATOR_ONE));
        assertEquals(200, response.statusCode(), response.body());
        return body(response);
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void restoreIsReachableInTheProductionBoot() throws Exception {
        UUID any = UUID.randomUUID();

        assertEquals(401, client().anonymous("POST", path(any, "/config/restore-runtime")).statusCode(),
                "a mapped, authenticated route: 401 with no session, not the 404 of an unserved path");
    }

    @Test
    @Order(2)
    void anInstanceHasOneGenerationAppliedToItsPlacement() throws Exception {
        Client http = client();
        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);
        instance = declared.instance();
        placement = declared.placement();
        assertEquals(1, ApplyItSupport.stage(http, instance, declared.version(), "orders"));
        assertEquals("STAGED", apply(http, 1).get("state").asText());
        assertEquals("persisted", ladder(http).get("reached").asText());
    }

    @Test
    @Order(3)
    void requiresConfigApplyAndAForeignCallerSeesA404() throws Exception {
        Client http = client();
        long before = generations();

        assertEquals(403, http.post(path(instance, "/config/restore-runtime"), http.login(DeclareFixtures.VIEWER),
                restoreBody(placement)).statusCode(), "a viewer holds no config.apply");
        for (String who : List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            assertEquals(404, http.post(path(instance, "/config/restore-runtime"), http.login(who),
                    restoreBody(placement)).statusCode(), who);
        }
        assertEquals(before, generations(), "nothing was copied forward");
    }

    @Test
    @Order(4)
    void aMissingPlacementAnUnknownStrategyScheduledAndAForeignPlacementAreRefusedAndMintNothing() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = generations();
        ObjectNode absent = restoreBody(placement);
        absent.remove("placementId");

        assertEquals(400, http.post(path(instance, "/config/restore-runtime"), operator, absent).statusCode(),
                "v1 refuses instance-wide apply (BE-D23, OR-60)");
        HttpResponse<String> scheduled = http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement).put("strategy", "scheduled"));
        assertEquals(422, scheduled.statusCode(), "scheduled (plan 012 T7): restore-runtime has no schedule: " + scheduled.body());
        assertEquals("VALIDATION_FAILED", body(scheduled).get("code").asText());
        assertTrue(body(scheduled).get("detail").asText().contains("restore"), scheduled.body());
        // plan 012 T2: restart-all without a confirmation is a 400 and mints nothing (design 5.3, F7)
        assertEquals(400, http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement).put("strategy", "restart-all")).statusCode());
        assertEquals(400, http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement).put("strategy", "yolo")).statusCode());
        assertEquals(404, http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(UUID.randomUUID())).statusCode());
        assertEquals(before, generations());
    }

    @Test
    @Order(5)
    void anInstanceWithNothingStagedHasNoDesiredGenerationToRestore() throws Exception {
        Client http = client();
        ApplyItSupport.Declared empty = ApplyItSupport.declare(http, world, "empty-cache", "sm-empty", 9102);

        HttpResponse<String> response = http.post(path(empty.instance(), "/config/restore-runtime"),
                http.login(DeclareFixtures.OPERATOR_ONE), restoreBody(empty.placement()));

        assertEquals(422, response.statusCode(), response.body());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
    }

    @Test
    @Order(6)
    void restoreReappliesTheDesiredContentUnderANewGenerationAndAMissingStrategyIsManual() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long desired = stage(http, "billing");          // staged, never applied: desired is 2, the host holds 1
        assertEquals(2, desired);

        HttpResponse<String> response = http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement));

        assertEquals(202, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertEquals(2, accepted.size(), "stateVersion and applyId: " + accepted);
        assertEquals("/api/dp/instances/" + instance + "/apply/observe?applyId=" + accepted.get("applyId").asText(),
                response.headers().firstValue("Location").orElseThrow());
        assertEquals(3, generations(), "a new generation, not generation 2 re-admitted");
        assertTrue(sameContent(2, 3), "generation 3 carries the DESIRED generation 2's content");
        assertFalse(sameContent(1, 3), "not the latest applied generation 1's");
        assertEquals("restore-runtime", column("origin", 3));
        assertEquals("2", column("derived_from_generation", 3));
        assertEquals("manual", ownerJdbc.queryForObject("SELECT strategy FROM sm_data.apply_request WHERE apply_id = :a",
                Map.of("a", UUID.fromString(accepted.get("applyId").asText())), String.class),
                "an absent strategy is manual");

        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance, accepted.get("applyId").asText(),
                Duration.ofSeconds(20));
        assertEquals(3, landed.get("generation").asLong());
        assertEquals("STAGED", landed.get("state").asText(), landed.toString());
    }

    @Test
    @Order(7)
    void aDriftedHostIsRestoredAndThePersistedRungReturns() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        InMemoryRuntimeAdapter host = adapter.host(placement);
        TargetReplica replica = host.replicas().get(0);
        String intended = host.hashOf(replica);
        assertEquals("persisted", ladder(http).get("reached").asText());
        host.setFile(replica, DRIFT);                    // someone edits the file on the host
        assertNotEquals(intended, host.hashOf(replica));
        adapter.gate = new CountDownLatch(1);            // hold the worker so the interim state can be read
        try {
            HttpResponse<String> response = http.post(path(instance, "/config/restore-runtime"), operator,
                    restoreBody(placement));
            assertEquals(202, response.statusCode(), response.body());
            assertEquals(4, generations(), "the copy is the new head");
            assertEquals("rendered", ladder(http).get("reached").asText(),
                    "the new desired generation is not yet on the host: the persisted rung is not claimed");

            adapter.gate.countDown();
            JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance,
                    body(response).get("applyId").asText(), Duration.ofSeconds(20));
            assertEquals("STAGED", landed.get("state").asText(), landed.toString());
        } finally {
            adapter.gate.countDown();
            adapter.gate = null;
        }

        assertEquals(intended, host.hashOf(replica), "the host holds the desired content again");
        JsonNode ladder = ladder(http);
        assertEquals("persisted", ladder.get("reached").asText());
        assertEquals(4, ladder.at("/rungs/2/generation").asLong());
    }

    @Test
    @Order(8)
    void aRestoreRefusedForABusyScopeMintsNothingAndALaterOneMintsTheCopy() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        adapter.gate = new CountDownLatch(1);
        try {
            HttpResponse<String> first = http.post(path(instance, "/config/restore-runtime"), operator,
                    restoreBody(placement));
            assertEquals(202, first.statusCode(), first.body());
            assertEquals(5, generations());

            // S811-2: the scope is checked BEFORE the copy-forward, so a refusal never replaces the desired configuration
            HttpResponse<String> busy = http.post(path(instance, "/config/restore-runtime"), operator,
                    restoreBody(placement));
            assertEquals(409, busy.statusCode(), busy.body());
            assertEquals(5, generations(), "admission refused, and nothing was copied forward");
            assertEquals(409, http.post(path(instance, "/config/restore-runtime"), operator, restoreBody(placement))
                    .statusCode());
            assertEquals(5, generations(), "nor did the next refused attempt");

            adapter.gate.countDown();
            ApplyItSupport.awaitTerminal(http, operator, instance, body(first).get("applyId").asText(),
                    Duration.ofSeconds(20));
        } finally {
            adapter.gate.countDown();
            adapter.gate = null;
        }
        HttpResponse<String> retry = http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement));
        assertEquals(202, retry.statusCode(), retry.body());
        assertEquals(6, generations(), "the retry, once the scope was free, copied the current content forward as generation 6");
        ApplyItSupport.awaitTerminal(http, operator, instance, body(retry).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    // ------------------------------------------------------ plan 012 T2: the two ungated strategies (OR-121)

    @Test
    @Order(9)
    void aRestartAllRestoreStoresThePrincipalAndARollingNoValidationRestoreStoresNone() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = generations();
        assertEquals(403, http.post(path(instance, "/config/restore-runtime"), http.login(DeclareFixtures.VIEWER),
                restoreBody(placement).put("strategy", "restart-all").put("outageConfirmedBy", "x")).statusCode());
        assertEquals(before, generations(), "a refused restart-all restore mints no generation");
        String principal = ownerJdbc.queryForObject("SELECT id::text FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.OPERATOR_ONE), String.class);

        HttpResponse<String> all = http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement).put("strategy", "restart-all").put("outageConfirmedBy", "SENTINEL-typed-name"));
        assertEquals(202, all.statusCode(), all.body());
        String allId = body(all).get("applyId").asText();
        assertEquals(principal, ownerJdbc.queryForObject("SELECT outage_confirmed_by FROM sm_data.apply_request"
                + " WHERE apply_id = :a", Map.of("a", UUID.fromString(allId)), String.class),
                "the principal, never the typed string (OPS-Q7 C-2)");
        ApplyItSupport.awaitTerminal(http, operator, instance, allId, Duration.ofSeconds(20));

        HttpResponse<String> none = http.post(path(instance, "/config/restore-runtime"), operator,
                restoreBody(placement).put("strategy", "rolling-no-validation").put("outageConfirmedBy", "SENTINEL"));
        assertEquals(202, none.statusCode(), none.body());
        String noneId = body(none).get("applyId").asText();
        assertEquals("rolling-no-validation", ownerJdbc.queryForObject("SELECT strategy FROM sm_data.apply_request"
                + " WHERE apply_id = :a", Map.of("a", UUID.fromString(noneId)), String.class));
        assertEquals(null, ownerJdbc.queryForObject("SELECT outage_confirmed_by FROM sm_data.apply_request"
                + " WHERE apply_id = :a", Map.of("a", UUID.fromString(noneId)), String.class));
        ApplyItSupport.awaitTerminal(http, operator, instance, noneId, Duration.ofSeconds(20));
    }
}
