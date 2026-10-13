package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.3 / plan 007 Task 6b: {@code stopInstance} is SERVED, held by the Administrator and (OR-121, plan 012 T1) the Operator, idempotent, and its own
 * request is observable by {@code applyId} (the declared ~2 s browser poll). Real HTTP, real PostgreSQL, the real
 * worker; the host is the local counting adapter of {@link ApplyItSupport}.
 *
 * <p>The audit record of a stop is its journal row ({@code apply_request}: operation, actor, scope, time), which the
 * terminal guard makes append-only; there is no separate audit log to write to yet (D10 amendment, open item 24).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StopEndpointIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static Declared first;
    private static Declared untouched;

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

    private static String stop(UUID instance) {
        return "/api/dp/instances/" + instance + "/stop";
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private long stops(UUID instance) {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i AND operation = 'stop'", Map.of("t", world.organization(), "i", instance),
                Long.class);
        return n == null ? 0 : n;
    }

    @Test
    @Order(1)
    void aStopIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        assertEquals(401, client().anonymous("POST", stop(UUID.randomUUID())).statusCode());
    }

    @Test
    @Order(2)
    void twoInstancesAreDeclaredAndTheFirstHasBeenApplied() throws Exception {
        Client http = client();
        first = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);
        untouched = ApplyItSupport.declare(http, world, "billing-cache", "sm-billing", 9102);
        long generation = ApplyItSupport.stage(http, first.instance(), first.version(), "orders");
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode apply = MAPPER.createObjectNode();
        apply.put("generation", generation).put("strategy", "manual").put("placementId", first.placement().toString());
        HttpResponse<String> accepted = http.post("/api/dp/instances/" + first.instance() + "/apply", operator, apply);
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, first.instance(),
                body(accepted).get("applyId").asText(), Duration.ofSeconds(20));
        assertEquals("STAGED", landed.get("state").asText());
    }

    @Test
    @Order(3)
    void aViewerIsRefusedStopWith403AndNothingIsAdmitted() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(stop(first.instance()), http.login(DeclareFixtures.VIEWER),
                MAPPER.createObjectNode());
        assertEquals(403, response.statusCode(), response.body());
        assertEquals("FORBIDDEN", body(response).get("code").asText());
        assertEquals(0, stops(first.instance()));
    }

    @Test
    @Order(4)
    void aSiblingEnvironmentsOperatorAndAForeignAdministratorSeeNoneOfItAs404() throws Exception {
        Client http = client();

        for (String who : java.util.List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            assertEquals(404, http.post(stop(first.instance()), http.login(who), MAPPER.createObjectNode())
                    .statusCode(), who);
        }
        assertEquals(0, stops(first.instance()));
    }

    @Test
    @Order(5)
    void anAdministratorStopsTheWholeInstanceAndTheStopsOwnRequestIsObservableByApplyId() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);

        HttpResponse<String> response = http.post(stop(first.instance()), admin, MAPPER.createObjectNode());

        assertEquals(202, response.statusCode(), response.body());
        String applyId = body(response).get("applyId").asText();
        assertEquals("/api/dp/instances/" + first.instance() + "/apply/observe?applyId=" + applyId,
                response.headers().firstValue("Location").orElseThrow());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, admin, first.instance(), applyId, Duration.ofSeconds(20));
        assertEquals("stop", landed.get("operation").asText());
        assertEquals("STOPPED", landed.get("state").asText(), landed.toString());
        assertFalse(landed.has("generation"), "a stop has no generation: " + landed);
        assertFalse(landed.has("placementId"), "instance-wide: " + landed);
        assertTrue(landed.get("steps").size() >= 1, landed.toString());
    }

    @Test
    @Order(6)
    void stoppingAnAlreadyStoppedInstanceIsAnother202AndAnotherJournalRow() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);

        HttpResponse<String> again = http.post(stop(first.instance()), admin, MAPPER.createObjectNode());

        assertEquals(202, again.statusCode(), again.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, admin, first.instance(),
                body(again).get("applyId").asText(), Duration.ofSeconds(20));
        assertEquals("STOPPED", landed.get("state").asText());
        assertEquals(2, stops(first.instance()), "each call is one append-only journal row: its audit record");
    }

    @Test
    @Order(7)
    void aPlacementTargetedStopAddressesThatPlacementAndAForeignOneIs404() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        ObjectNode targeted = MAPPER.createObjectNode().put("placementId", first.placement().toString());

        HttpResponse<String> response = http.post(stop(first.instance()), admin, targeted);

        assertEquals(202, response.statusCode(), response.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, admin, first.instance(),
                body(response).get("applyId").asText(), Duration.ofSeconds(20));
        assertEquals(first.placement().toString(), landed.get("placementId").asText());
        assertEquals("STOPPED", landed.get("state").asText());
        // another instance's placement is not this instance's
        assertEquals(404, http.post(stop(first.instance()), admin,
                MAPPER.createObjectNode().put("placementId", untouched.placement().toString())).statusCode());
        assertEquals(404, http.post(stop(first.instance()), admin,
                MAPPER.createObjectNode().put("placementId", UUID.randomUUID().toString())).statusCode());
    }

    @Test
    @Order(8)
    void aStopRefusesAGenerationAStrategyOrAnOutageConfirmationWith400() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        long before = stops(first.instance());

        for (ObjectNode bad : java.util.List.of(MAPPER.createObjectNode().put("generation", 1),
                MAPPER.createObjectNode().put("strategy", "manual"),
                MAPPER.createObjectNode().put("outageConfirmedBy", "me"))) {
            assertEquals(400, http.post(stop(first.instance()), admin, bad).statusCode(), bad.toString());
        }
        assertEquals(before, stops(first.instance()));
    }

    @Test
    @Order(9)
    void anInstanceWideStopWithNoLivePlacementIs202ThenLandsStopFailedNotStopped() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        // Plan 008 S3 (BE-D46): the stop addresses the registry's LIVE placements, and a declared instance always has one,
        // so "no live placement" is arranged the only way it can arise before plan 012's undeclare: the owner credential
        // retires the row (the runtime role may too; retirePlacement refuses the last one, by design).
        ownerJdbc.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND instance_id = :i"
                + " AND retired_at IS NULL", Map.of("t", world.organization(), "i", untouched.instance()));

        HttpResponse<String> response = http.post(stop(untouched.instance()), admin, MAPPER.createObjectNode());

        assertEquals(202, response.statusCode(), "the failure lands AFTER admission: " + response.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, admin, untouched.instance(),
                body(response).get("applyId").asText(), Duration.ofSeconds(20));
        assertEquals("STOP_FAILED", landed.get("state").asText(),
                "reporting STOPPED would say the instance is down when nothing was looked at");
    }

    @Test
    @Order(10)
    void aSecondStopInFlightOnTheSameScopeIs409() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        adapter.gate = new CountDownLatch(1);
        try {
            HttpResponse<String> held = http.post(stop(first.instance()), admin, MAPPER.createObjectNode());
            assertEquals(202, held.statusCode(), held.body());

            HttpResponse<String> second = http.post(stop(first.instance()), admin, MAPPER.createObjectNode());

            assertEquals(409, second.statusCode(), second.body());
            assertEquals("CONFLICT", body(second).get("code").asText());
        } finally {
            adapter.gate.countDown();
            adapter.gate = null;
        }
    }

    @Test
    @Order(11)
    void anOperatorNowStopsAnInstanceBecauseOr121GaveItInstanceStop() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = stops(first.instance());

        // Order(10)'s held stop may still be draining; a 409 there is that, not a refusal of the Operator.
        HttpResponse<String> response = http.post(stop(first.instance()), operator, MAPPER.createObjectNode());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (response.statusCode() == 409 && System.nanoTime() < deadline) {
            Thread.sleep(200);
            response = http.post(stop(first.instance()), operator, MAPPER.createObjectNode());
        }

        assertEquals(202, response.statusCode(), "was 403 before OR-121 (instance.stop was Administrator-only): "
                + response.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, first.instance(),
                body(response).get("applyId").asText(), Duration.ofSeconds(20));
        assertEquals("STOPPED", landed.get("state").asText(), landed.toString());
        assertEquals(before + 1, stops(first.instance()));
    }
}
