package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.3: {@code submitApply} and {@code observeApply} are SERVED - the real {@code SolutionManagerApplication}
 * over real HTTP against PostgreSQL, through the real interceptor, the real gateway orchestration, the real data
 * plane service and the real durable worker. Only the host is local: the production SSH adapter is withdrawn and an
 * in-memory one (counting every call) takes its place, so nothing here opens a socket.
 *
 * <p>State carries from test to test (ordered): a stage's generation is what the next submit names.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplyEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID instance;
    private static UUID placement;
    private static long version;

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

    private static ObjectNode submitBody(long generation, String strategy, UUID placementId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation);
        body.put("strategy", strategy);
        body.put("placementId", placementId.toString());
        return body;
    }

    private long requests() {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void applyObserveAndStopAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        Client http = client();
        UUID any = UUID.randomUUID();
        assertEquals(401, http.anonymous("POST", "/api/dp/instances/" + any + "/apply").statusCode());
        assertEquals(401, http.anonymous("GET", "/api/dp/instances/" + any + "/apply/observe").statusCode());
        assertEquals(401, http.anonymous("POST", "/api/dp/instances/" + any + "/stop").statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredAndAGenerationStaged() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        UUID solution = UUID.fromString(body(http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN),
                load)).get("solutionId").asText());
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solution, world.targetOne(), "orders-cache", "sm-orders", 9101));
        assertEquals(201, declared.statusCode(), declared.body());
        instance = UUID.fromString(body(declared).get("instanceId").asText());
        placement = UUID.fromString(body(declared).get("placements").get(0).get("placementId").asText());
        version = body(declared).get("stateVersion").asLong();

        HttpResponse<String> staged = http.post(path("/config"), operator, ApplyItSupport.stageBody(version, "orders"));
        assertEquals(200, staged.statusCode(), staged.body());
        assertEquals(1, body(staged).get("generation").asLong());
    }

    @Test
    @Order(3)
    void aViewerObservesButCannotSubmitAndAForeignCallerSeesNoneOfItAs404() throws Exception {
        Client http = client();
        Login viewer = http.login(DeclareFixtures.VIEWER);
        assertEquals(200, http.get(path("/apply/observe"), viewer).statusCode());
        assertEquals(403, http.post(path("/apply"), viewer, submitBody(1, "manual", placement)).statusCode());

        for (String who : List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            Login caller = http.login(who);
            assertEquals(404, http.get(path("/apply/observe"), caller).statusCode(), who);
            assertEquals(404, http.post(path("/apply"), caller, submitBody(1, "manual", placement)).statusCode(), who);
        }
        assertEquals(0, requests(), "nothing was admitted");
    }

    @Test
    @Order(4)
    void aGenerationThatWasNeverStagedIs422AndAnAbsentOrForeignPlacementIs400Or404() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> unstaged = http.post(path("/apply"), operator, submitBody(2, "manual", placement));
        assertEquals(422, unstaged.statusCode(), unstaged.body());
        assertEquals("VALIDATION_FAILED", body(unstaged).get("code").asText());

        ObjectNode absent = submitBody(1, "manual", placement);
        absent.remove("placementId");
        assertEquals(400, http.post(path("/apply"), operator, absent).statusCode());
        assertEquals(404, http.post(path("/apply"), operator, submitBody(1, "manual", UUID.randomUUID())).statusCode());
        assertEquals(0, requests());
    }

    @Test
    @Order(5)
    void theScheduledTokenIs422NamingTheScheduleFieldAndAnUnknownStrategyIs400AndNothingIsWritten() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post(path("/apply"), operator, submitBody(1, "scheduled", placement));
        assertEquals(422, response.statusCode(), "scheduled (plan 012 T7, OR-121 naming OR-53/OR-60): " + response.body());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
        assertTrue(body(response).get("detail").asText().contains("schedule"), response.body());
        assertEquals(400, http.post(path("/apply"), operator, submitBody(1, "yolo", placement)).statusCode());
        assertEquals(0, requests());
    }

    @Test
    @Order(6)
    void the202IsReturnedWhileTheAdapterIsHeldAndTheRowIsDurableThenOneAdapterSequenceRuns() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        adapter.gate = new java.util.concurrent.CountDownLatch(1);
        int callsBefore = adapter.calls.get();

        long started = System.nanoTime();
        HttpResponse<String> response = http.post(path("/apply"), operator, submitBody(1, "manual", placement));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertEquals(202, response.statusCode(), response.body());
        assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "202 must not wait for the adapter: " + took);
        JsonNode accepted = body(response);
        String applyId = accepted.get("applyId").asText();
        assertEquals(2, accepted.size(), "stateVersion and applyId, and the outcome is not in the body: " + accepted);
        assertEquals("/api/dp/instances/" + instance + "/apply/observe?applyId=" + applyId,
                response.headers().firstValue("Location").orElseThrow());
        String state = ownerJdbc.queryForObject("SELECT state FROM sm_data.apply_request WHERE apply_id = :a",
                Map.of("a", UUID.fromString(applyId)), String.class);
        assertTrue(state.equals("REQUESTED") || state.equals("RUNNING"), "the row is durable: " + state);
        assertFalse(body(http.get(path("/apply/observe?applyId=" + applyId), operator)).get("items").get(0)
                .get("terminal").asBoolean(), "still running while the adapter is held");

        adapter.gate.countDown();
        adapter.gate = null;
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance, applyId, Duration.ofSeconds(20));
        assertEquals("STAGED", landed.get("state").asText(), landed.toString());
        assertEquals(1, landed.get("generation").asLong());
        assertEquals(placement.toString(), landed.get("placementId").asText());
        assertTrue(adapter.calls.get() > callsBefore, "the worker, not the request thread, drove the adapter");
        assertTrue(landed.get("steps").size() >= 2, landed.toString());
        for (JsonNode step : landed.get("steps")) {
            assertEquals("succeeded", step.get("outcome").asText(), step.toString());
        }
        assertEquals(1, requests());
    }

    @Test
    @Order(7)
    void aSecondApplyOfTheSameGenerationIsRefusedByTheWatermarkAs409() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post(path("/apply"), operator, submitBody(1, "manual", placement));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertEquals(1, requests());
    }

    @Test
    @Order(8)
    void rollingHealthGatedIsAdmittedAndFailsClosedWithNoEffectWhenNoSignalIsDeclared() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> staged = http.post(path("/config"), operator,
                ApplyItSupport.stageBody(version(http, operator), "billing"));
        assertEquals(200, staged.statusCode(), staged.body());
        long generation = body(staged).get("generation").asLong();
        assertEquals(2, generation);

        HttpResponse<String> response = http.post(path("/apply"), operator,
                submitBody(generation, "rolling-health-gated", placement));

        assertEquals(202, response.statusCode(), response.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance,
                body(response).get("applyId").asText(), Duration.ofSeconds(20));
        assertEquals("FAILED_NO_EFFECT", landed.get("state").asText(), landed.toString());
        boolean named = false;
        for (JsonNode step : landed.get("steps")) {
            if (step.has("reason") && step.get("reason").asText().equals("strategy.health.signal.absent")) {
                named = true;
                assertEquals("failed", step.get("outcome").asText());
                assertFalse(step.has("gateProved"), "T6.3 reports no gateProved: " + step);
            }
        }
        assertTrue(named, "the journal names the fail-closed classifier: " + landed);
    }

    @Test
    @Order(9)
    void observeAnswersWhenTheHostIsUnreachableFromTheJournalAlone() throws Exception {
        Client http = client();
        Login viewer = http.login(DeclareFixtures.VIEWER);
        adapter.unreachable = true;
        int calls = adapter.calls.get();
        try {
            HttpResponse<String> response = http.get(path("/apply/observe"), viewer);

            assertEquals(200, response.statusCode(), response.body());
            assertEquals(2, body(response).get("items").size());
            assertEquals(calls, adapter.calls.get(), "observing never reaches the adapter");
        } finally {
            adapter.unreachable = false;
        }
    }

    @Test
    @Order(10)
    void everyRequestObservedHasItsOwnStateVersionAscending() throws Exception {
        Client http = client();
        JsonNode items = body(http.get(path("/apply/observe"), http.login(DeclareFixtures.VIEWER))).get("items");

        assertEquals(2, items.size());
        assertNotNull(items.get(0).get("stateVersion"));
        assertTrue(items.get(0).get("stateVersion").asLong() < items.get(1).get("stateVersion").asLong());
    }

    // ------------------------------------------------------ plan 012 T2: the two ungated strategies (OR-121)

    private String column(String applyId, String column) {
        return ownerJdbc.queryForObject("SELECT " + column + " FROM sm_data.apply_request WHERE apply_id = :a",
                Map.of("a", UUID.fromString(applyId)), String.class);
    }

    @Test
    @Order(11)
    void restartAllNeedsAConfirmationAndTheStoredOneIsThePrincipalNeverTheTypedName() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> staged = http.post(path("/config"), operator,
                ApplyItSupport.stageBody(version(http, operator), "inventory"));
        assertEquals(200, staged.statusCode(), staged.body());
        long generation = body(staged).get("generation").asLong();
        long before = requests();

        // the Viewer holds neither config.apply nor either restart verb
        assertEquals(403, http.post(path("/apply"), http.login(DeclareFixtures.VIEWER),
                submitBody(generation, "restart-all", placement)).statusCode());
        // no confirmation: 400, and nothing is written
        HttpResponse<String> unconfirmed = http.post(path("/apply"), operator,
                submitBody(generation, "restart-all", placement));
        assertEquals(400, unconfirmed.statusCode(), unconfirmed.body());
        ObjectNode blank = submitBody(generation, "restart-all", placement);
        blank.put("outageConfirmedBy", "  ");
        assertEquals(400, http.post(path("/apply"), operator, blank).statusCode());
        assertEquals(before, requests(), "a refused restart-all writes nothing");

        ObjectNode confirmed = submitBody(generation, "restart-all", placement);
        confirmed.put("outageConfirmedBy", "SENTINEL-typed-name");
        HttpResponse<String> response = http.post(path("/apply"), operator, confirmed);

        assertEquals(202, response.statusCode(), response.body());
        String applyId = body(response).get("applyId").asText();
        assertEquals("restart-all", column(applyId, "strategy"));
        String principal = ownerJdbc.queryForObject("SELECT id::text FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.OPERATOR_ONE), String.class);
        assertEquals(principal, column(applyId, "requested_by"));
        assertEquals(principal, column(applyId, "outage_confirmed_by"),
                "the session principal's id is the confirmation, never the string the browser sent (OPS-Q7 C-2)");
        assertFalse(response.body().contains("SENTINEL"));
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance, applyId, Duration.ofSeconds(20));
        assertTrue(landed.get("terminal").asBoolean(), landed.toString());
    }

    @Test
    @Order(12)
    void rollingNoValidationIsAdmittedAndStoresNoConfirmationEvenWhenOneIsSent() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> staged = http.post(path("/config"), operator,
                ApplyItSupport.stageBody(version(http, operator), "shipping"));
        assertEquals(200, staged.statusCode(), staged.body());
        long generation = body(staged).get("generation").asLong();

        assertEquals(403, http.post(path("/apply"), http.login(DeclareFixtures.VIEWER),
                submitBody(generation, "rolling-no-validation", placement)).statusCode());
        ObjectNode typed = submitBody(generation, "rolling-no-validation", placement);
        typed.put("outageConfirmedBy", "SENTINEL-typed-name");
        HttpResponse<String> response = http.post(path("/apply"), operator, typed);

        assertEquals(202, response.statusCode(), response.body());
        String applyId = body(response).get("applyId").asText();
        assertEquals("rolling-no-validation", column(applyId, "strategy"));
        assertEquals(null, column(applyId, "outage_confirmed_by"), "rolling-no-validation asserts no outage");
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance, applyId, Duration.ofSeconds(20));
        assertTrue(landed.get("terminal").asBoolean(), landed.toString());
    }

    private long version(Client http, Login login) throws Exception {
        return body(http.get(path("/designer"), login)).get("stateVersion").asLong();
    }
}
