package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.fleet.FleetScheduler;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T11.2 (REQ-013, OR-47): {@code submitFleetApply} and {@code observeFleetApply} are SERVED - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, the real gateway
 * orchestration, the real data-plane service, the real {@code FleetScheduler} and the real durable worker. Only the host
 * is local ({@link ApplyItSupport}): an in-memory adapter takes the production SSH adapter's place.
 *
 * <p>The scenario is {@code fleet-apply.md} section 1: {@code operator-one} holds {@code Operator} at environment one
 * only, so {@code cache-1} and {@code cache-2} (environment one) are his and {@code cache-eu} (environment two) is not
 * even visible to him. State carries from test to test (ordered).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FleetEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solution;
    private static Declared cache1;
    private static Declared cache2;
    private static Declared cacheEu;
    private static UUID operatorOne;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @Autowired
    private FleetScheduler scheduler;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        registry.add("sm.dp.fleet.advance-interval", () -> "200ms");
        // plan 012 T6: a rolling-no-validation fleet waits this long between batches (default 30 s); the two-batch test
        // below awaits its landing, so the test run keeps the delay short without withdrawing it
        registry.add("sm.dp.fleet.no-validation.settle", () -> "1s");
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    /** {@code cache-1}, {@code cache-2}, {@code cache-eu}: the scenario's order. */
    private static ObjectNode fleetBody(String strategy, long g1, long g2, long gEu, boolean proceed) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("solutionId", solution.toString());
        ArrayNode members = body.putArray("members");
        for (Object[] m : new Object[][] {{cache1, g1}, {cache2, g2}, {cacheEu, gEu}}) {
            Declared d = (Declared) m[0];
            members.addObject().put("instanceId", d.instance().toString()).put("placementId", d.placement().toString())
                    .put("generation", (long) m[1]);
        }
        body.put("strategy", strategy);
        body.put("batchSize", 1);
        if (proceed) {
            body.put("proceedWithAuthorized", true);
        }
        return body;
    }

    private long fleets() {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.fleet_action WHERE tenant_id = :t",
                Map.of("t", world.organization()), Long.class);
        return n == null ? 0 : n;
    }

    private long requests() {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t",
                Map.of("t", world.organization()), Long.class);
        return n == null ? 0 : n;
    }

    /** Polls {@code observeFleetApply} until the fleet has left {@code RUNNING}. */
    private JsonNode awaitFleet(Client http, Login login, String fleetId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.get("/api/dp/fleet-applies/" + fleetId, login);
            assertEquals(200, response.statusCode(), response.body());
            last = body(response);
            if (!last.get("state").asText().equals("RUNNING")) {
                return last;
            }
            Thread.sleep(100);
        }
        fail("fleet " + fleetId + " never left RUNNING within " + timeout + "; last seen " + last);
        throw new AssertionError("unreachable");
    }

    private long version(Client http, Login login, UUID instance) throws Exception {
        return body(http.get("/api/dp/instances/" + instance + "/designer", login)).get("stateVersion").asLong();
    }

    private static List<String> dispositions(JsonNode fleet) {
        List<String> out = new ArrayList<>();
        fleet.get("members").forEach(m -> out.add(m.get("disposition").asText()));
        return out;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void fleetApplyAndObserveAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        Client http = client();
        assertEquals(401, http.anonymous("POST", "/api/dp/fleet-applies").statusCode());
        assertEquals(401, http.anonymous("GET", "/api/dp/fleet-applies/" + UUID.randomUUID()).statusCode());
        assertTrue(scheduler.isRunning(), "the one FleetScheduler is wired into the production boot and running");
    }

    @Test
    @Order(2)
    void threeInstancesAreDeclaredAndAGenerationStagedOnEach() throws Exception {
        Client http = client();
        cache1 = ApplyItSupport.declare(http, world, "fleet-cache-1", "sm-fleet-1", 9201);
        cache2 = ApplyItSupport.declare(http, world, "fleet-cache-2", "sm-fleet-2", 9202);
        solution = UUID.fromString(body(http.get("/api/dp/instances/" + cache1.instance(),
                http.login(DeclareFixtures.OPERATOR_ONE))).get("solutionId").asText());
        assertEquals(1, ApplyItSupport.stage(http, cache1.instance(), cache1.version(), "orders-1"));
        assertEquals(1, ApplyItSupport.stage(http, cache2.instance(), cache2.version(), "orders-2"));

        // cache-eu lives in the SIBLING environment, under operator two
        Login operatorTwo = http.login(DeclareFixtures.OPERATOR_TWO);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envTwo() + "/instances", operatorTwo,
                DeclareFixtures.declareBody(solution, world.targetTwo(), "fleet-cache-eu", "sm-fleet-eu", 9203));
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode eu = body(declared);
        cacheEu = new Declared(UUID.fromString(eu.get("instanceId").asText()),
                UUID.fromString(eu.get("placements").get(0).get("placementId").asText()),
                eu.get("stateVersion").asLong());
        HttpResponse<String> staged = http.post("/api/dp/instances/" + cacheEu.instance() + "/config", operatorTwo,
                ApplyItSupport.stageBody(cacheEu.version(), "orders-eu"));
        assertEquals(200, staged.statusCode(), staged.body());
        assertEquals(1, body(staged).get("generation").asLong());
        operatorOne = UUID.fromString(ownerJdbc.queryForObject("SELECT id::text FROM sm_control.principal"
                + " WHERE username = :u", Map.of("u", DeclareFixtures.OPERATOR_ONE), String.class));
        assertEquals(0, fleets());
    }

    @Test
    @Order(3)
    void aViewerIsRefusedByThePreCheckAndAForeignAdministratorSeesAllThreeAsNotFound() throws Exception {
        Client http = client();

        HttpResponse<String> viewer = http.post("/api/dp/fleet-applies", http.login(DeclareFixtures.VIEWER),
                fleetBody("manual", 1, 1, 1, true));
        assertEquals(403, viewer.statusCode(), viewer.body());
        assertFalse(body(viewer).has("refusedMembers"), "the reach pre-check refused: no member was evaluated");

        // another organization's administrator HOLDS config.apply (in his own org), so the pre-check passes; but not one
        // of these instances is his, so every member is NOT_FOUND - and no member is applied
        HttpResponse<String> foreign = http.post("/api/dp/fleet-applies", http.login(DeclareFixtures.FOREIGN),
                fleetBody("manual", 1, 1, 1, true));
        assertEquals(403, foreign.statusCode(), foreign.body());
        JsonNode refused = body(foreign).get("refusedMembers");
        assertEquals(3, refused.size());
        for (JsonNode member : refused) {
            assertEquals("NOT_FOUND", member.get("code").asText());
        }
        assertEquals(0, fleets());
        assertEquals(0, requests());
    }

    @Test
    @Order(4)
    void aFleetWithAMemberTheOperatorCannotSeeIsRefusedWholeByDefaultAndNothingIsWritten() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post("/api/dp/fleet-applies", http.login(DeclareFixtures.OPERATOR_ONE),
                fleetBody("manual", 1, 1, 1, false));

        assertEquals(403, response.statusCode(), response.body());
        JsonNode problem = body(response);
        assertEquals("FORBIDDEN", problem.get("code").asText());
        assertEquals(1, problem.get("refusedMembers").size(), response.body());
        JsonNode refused = problem.get("refusedMembers").get(0);
        assertEquals(2, refused.get("ordinal").asInt());
        assertEquals(cacheEu.instance().toString(), refused.get("instanceId").asText());
        assertEquals("NOT_FOUND", refused.get("code").asText());
        assertEquals(0, fleets(), "refused whole: nothing is written on either plane");
        assertEquals(0, requests());
    }

    @Test
    @Order(5)
    void aFleetThatProceedsWithTheAuthorizedRunsThroughTheRealSchedulerAndWorkerAndEveryObserverSeesOnlyWhatItMay()
            throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post("/api/dp/fleet-applies", operator, fleetBody("manual", 1, 1, 1, true));

        assertEquals(202, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        String fleetId = accepted.get("fleetId").asText();
        assertEquals("/api/dp/fleet-applies/" + fleetId, response.headers().firstValue("Location").orElseThrow());
        assertEquals("RUNNING", accepted.get("state").asText());
        assertTrue(accepted.get("stateVersion").asLong() > 0);
        assertEquals(List.of("pending", "pending", "refused"), dispositions(accepted),
                "durable and admitting nothing yet: the refused member is reported in the 202");
        assertFalse(accepted.get("members").get(0).has("applyId"), "no id until the member is admitted");
        assertEquals(1, fleets());

        // the real scheduler opens batch 0, then (once it is settled) batch 1, and the real worker executes each
        JsonNode landed = awaitFleet(http, operator, fleetId, Duration.ofSeconds(30));
        assertEquals("SUCCEEDED", landed.get("state").asText(), landed.toString());
        assertNotNull(landed.get("terminalAt"));
        assertEquals(2, landed.get("members").size(), "operator one cannot see cache-eu, so it is filtered (Q-F8 (a))");
        assertEquals(List.of("admitted", "admitted"), dispositions(landed));
        for (JsonNode member : landed.get("members")) {
            assertEquals("succeeded", member.get("settled").asText());
            assertEquals("STAGED", member.get("requestState").asText(), "the manual strategy's terminal success");
            // fleet -> request: the member's own apply id, which the ordinary observeApply answers for
            HttpResponse<String> own = http.get("/api/dp/instances/" + member.get("instanceId").asText()
                    + "/apply/observe?applyId=" + member.get("applyId").asText(), operator);
            assertEquals(200, own.statusCode(), own.body());
            assertEquals("STAGED", body(own).get("items").get(0).get("state").asText());
        }
        // request -> fleet, and the audit line is the request: the submitting principal, not a display name
        assertEquals(2, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.fleet_member m JOIN"
                + " sm_data.apply_request r ON r.tenant_id = m.tenant_id AND r.apply_id = m.apply_id WHERE"
                + " m.tenant_id = :t AND r.requested_by = :p", Map.of("t", world.organization(),
                "p", operatorOne.toString()), Long.class).intValue());
        assertEquals(2, requests(), "one request per authorized member, none for the refused one");

        // every observer sees only the members at instances it can see
        Login two = http.login(DeclareFixtures.OPERATOR_TWO);
        JsonNode twoSees = body(http.get("/api/dp/fleet-applies/" + fleetId, two));
        assertEquals(List.of("refused"), dispositions(twoSees),
                "operator two sees cache-eu, and cache-eu was REFUSED: a refused member is shown to a viewer who can see it");
        assertEquals("NOT_FOUND", twoSees.get("members").get(0).get("refusalCode").asText());
        assertEquals(3, body(http.get("/api/dp/fleet-applies/" + fleetId, http.login(DeclareFixtures.ADMIN)))
                .get("members").size());
        assertEquals(3, body(http.get("/api/dp/fleet-applies/" + fleetId, http.login(DeclareFixtures.VIEWER)))
                .get("members").size());
        assertEquals(404, http.get("/api/dp/fleet-applies/" + fleetId, http.login(DeclareFixtures.FOREIGN)).statusCode(),
                "another organization's fleet is not found");
        assertEquals(404, http.get("/api/dp/fleet-applies/" + UUID.randomUUID(), operator).statusCode());
    }

    @Test
    @Order(6)
    void aPlacementReservedByARunningFleetIs409ForASecondFleetAndFreedOnceItSettles() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long generation = ApplyItSupport.stage(http, cache1.instance(), version(http, operator, cache1.instance()),
                "orders-1b");
        assertEquals(2, generation);

        adapter.gate = new CountDownLatch(1);          // hold the worker, so the first fleet stays RUNNING
        try {
            ObjectNode first = MAPPER.createObjectNode();
            first.put("solutionId", solution.toString());
            first.putArray("members").addObject().put("instanceId", cache1.instance().toString())
                    .put("placementId", cache1.placement().toString()).put("generation", generation);
            first.put("strategy", "manual");
            HttpResponse<String> accepted = http.post("/api/dp/fleet-applies", operator, first);
            assertEquals(202, accepted.statusCode(), accepted.body());
            String fleetId = body(accepted).get("fleetId").asText();

            HttpResponse<String> second = http.post("/api/dp/fleet-applies", operator, first);
            assertEquals(409, second.statusCode(), second.body());
            assertEquals("CONFLICT", body(second).get("code").asText());
            assertTrue(body(second).get("detail").asText().contains("member 0"), second.body());
            assertFalse(second.body().contains(fleetId), "the other fleet's id is never named");
            assertEquals(2, fleets(), "the refused second submit wrote nothing");

            adapter.gate.countDown();
            JsonNode landed = awaitFleet(http, operator, fleetId, Duration.ofSeconds(30));
            assertEquals("SUCCEEDED", landed.get("state").asText(), landed.toString());
        } finally {
            if (adapter.gate != null) {
                adapter.gate.countDown();
                adapter.gate = null;
            }
        }
    }

    // ------------------------------------------------------ plan 012 T2: the two ungated strategies (OR-121)

    private ObjectNode twoMemberBody(String strategy, long generation1, long generation2) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("solutionId", solution.toString());
        ArrayNode members = body.putArray("members");
        members.addObject().put("instanceId", cache1.instance().toString())
                .put("placementId", cache1.placement().toString()).put("generation", generation1);
        members.addObject().put("instanceId", cache2.instance().toString())
                .put("placementId", cache2.placement().toString()).put("generation", generation2);
        body.put("strategy", strategy);
        body.put("batchSize", 2);
        return body;
    }

    private String column(String table, String column, String idColumn, String id) {
        return ownerJdbc.queryForObject("SELECT " + column + " FROM sm_data." + table + " WHERE " + idColumn + " = :i",
                Map.of("i", UUID.fromString(id)), String.class);
    }

    @Test
    @Order(7)
    void aRestartAllFleetNeedsAConfirmationAndRecordsThePrincipalOnTheFleetAndEveryMember() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long g1 = ApplyItSupport.stage(http, cache1.instance(), version(http, operator, cache1.instance()), "orders-1c");
        long g2 = ApplyItSupport.stage(http, cache2.instance(), version(http, operator, cache2.instance()), "orders-2c");
        long fleetsBefore = fleets();

        assertEquals(400, http.post("/api/dp/fleet-applies", operator, twoMemberBody("restart-all", g1, g2))
                .statusCode(), "no confirmation");
        assertEquals(403, http.post("/api/dp/fleet-applies", http.login(DeclareFixtures.VIEWER),
                twoMemberBody("restart-all", g1, g2).put("outageConfirmedBy", "x")).statusCode());
        assertEquals(fleetsBefore, fleets(), "a refused restart-all fleet writes nothing");

        HttpResponse<String> response = http.post("/api/dp/fleet-applies", operator,
                twoMemberBody("restart-all", g1, g2).put("outageConfirmedBy", "SENTINEL-typed-name"));

        assertEquals(202, response.statusCode(), response.body());
        String fleetId = body(response).get("fleetId").asText();
        assertEquals(operatorOne.toString(), column("fleet_action", "outage_confirmed_by", "fleet_id", fleetId),
                "the principal's id, never the typed string (OPS-Q7 C-2)");
        JsonNode landed = awaitFleet(http, operator, fleetId, Duration.ofSeconds(30));
        assertEquals(List.of("admitted", "admitted"), dispositions(landed));
        for (JsonNode member : landed.get("members")) {
            String applyId = member.get("applyId").asText();
            assertEquals("restart-all", column("apply_request", "strategy", "apply_id", applyId));
            assertEquals(operatorOne.toString(), column("apply_request", "outage_confirmed_by", "apply_id", applyId));
        }
    }

    @Test
    @Order(8)
    void aRollingNoValidationFleetIsAdmittedAndStoresNoConfirmation() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long g1 = ApplyItSupport.stage(http, cache1.instance(), version(http, operator, cache1.instance()), "orders-1d");
        long g2 = ApplyItSupport.stage(http, cache2.instance(), version(http, operator, cache2.instance()), "orders-2d");

        HttpResponse<String> response = http.post("/api/dp/fleet-applies", operator,
                twoMemberBody("rolling-no-validation", g1, g2).put("batchSize", 1)
                        .put("outageConfirmedBy", "SENTINEL-typed-name"));

        assertEquals(202, response.statusCode(), response.body());
        String fleetId = body(response).get("fleetId").asText();
        assertEquals(null, column("fleet_action", "outage_confirmed_by", "fleet_id", fleetId),
                "rolling-no-validation asserts no outage: a typed name is not stored");
        assertEquals("rolling-no-validation", column("fleet_action", "strategy", "fleet_id", fleetId));
        JsonNode landed = awaitFleet(http, operator, fleetId, Duration.ofSeconds(60));
        assertEquals(List.of("admitted", "admitted"), dispositions(landed));
    }
}
