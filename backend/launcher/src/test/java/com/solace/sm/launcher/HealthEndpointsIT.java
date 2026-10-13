package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.health.HealthPoller;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
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
 * Plan 010 T7.2: {@code getHealth} is SERVED and the poller is LIVE in the production boot - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, the real poller, the real direct-HTTP probe.
 * The managed integration is a JDK {@link HttpServer} on loopback standing in for a Cache actuator: the instance is
 * declared {@code direct-http}, its target's registered address is {@code 127.0.0.1} and its health port is the stub's,
 * exactly what {@code OR-82} describes. There is no SSH here (T7.1's session pool is exercised by its own test and, against
 * a host, by plan 010 T6.6); what is proven end to end is declare, then poll, then persist-on-change, then serve.
 */
// plan 013 T1.2: the target is a loopback stub, which the strict address policy refuses; the test wires the
// permissive one (a test-sources bean, never reachable in production)
@Import(LoopbackTargetAddressConfiguration.class)
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static volatile int answerStatus = 200;
    private static volatile String answerBody = "{\"status\":\"UP\"}";
    private static HttpServer managedIntegration;
    private static int stubPort;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID instance;
    private static UUID placement;

    @Value("${local.server.port}")
    private int port;

    @Autowired(required = false)
    private HealthPoller poller;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws IOException {
        managedIntegration = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        managedIntegration.createContext("/actuator/health", exchange -> {
            byte[] body = answerBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answerStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        managedIntegration.start();
        stubPort = managedIntegration.getAddress().getPort();

        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add(TwoSchemaTestDatabase.HEALTH_POLLER, () -> "true");
        registry.add("sm.dp.health.interval", () -> "2s");
        registry.add("sm.dp.health.timeout", () -> "1s");
        registry.add("sm.dp.health.jitter", () -> "0");
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        // the target's REGISTERED address (OR-82) is the stub's: this is the only thing the direct probe may use
        ownerJdbc.update("UPDATE sm_data.target_connection SET host = '127.0.0.1' WHERE tenant_id = :t AND target_id = :g",
                java.util.Map.of("t", world.organization(), "g", world.targetOne()));
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    @AfterAll
    static void stopStub() {
        managedIntegration.stop(0);
    }

    private Client client() {
        return new Client(port);
    }

    private static String healthPath(UUID id) {
        return "/api/dp/instances/" + id + "/health";
    }

    private JsonNode placementOf(Login caller) throws Exception {
        HttpResponse<String> response = client().get(healthPath(instance), caller);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode items = MAPPER.readTree(response.body()).get("placements");
        return items.size() == 0 ? null : items.get(0);
    }

    /** Polls getHealth until the first placement satisfies {@code until}; the poller runs every 2 s. */
    private JsonNode await(Login caller, Predicate<JsonNode> until, String description) throws Exception {
        long deadline = System.nanoTime() + 40_000_000_000L;
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = placementOf(caller);
            if (last != null && until.test(last)) {
                return last;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("timed out waiting for " + description + "; last seen: " + last);
    }

    @Test
    @Order(1)
    void getHealthIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        assertNotNull(poller, "the poller is live in the production boot when it is not withdrawn");
        assertTrue(poller.isRunning());
        assertEquals(401, client().anonymous("GET", healthPath(UUID.randomUUID())).statusCode());
    }

    @Test
    @Order(2)
    void aDirectHttpInstanceIsDeclaredAgainstTheStub() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        UUID solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());

        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", stubPort));
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode instanceJson = MAPPER.readTree(declared.body());
        instance = UUID.fromString(instanceJson.get("instanceId").asText());
        placement = UUID.fromString(instanceJson.at("/placements/0/placementId").asText());
        assertEquals("direct-http", instanceJson.at("/acquisition/mode").asText());
    }

    @Test
    @Order(3)
    void thePollerReadsTheStubGreenAndGetHealthServesItWithAPlacementVersion() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);

        JsonNode green = await(admin, p -> "GREEN".equals(p.get("state").asText()), "GREEN");

        assertEquals(placement.toString(), green.get("placementId").asText());
        assertTrue(green.get("stateVersion").asLong() > 0);
        assertNotNull(Instant.parse(green.get("checkedAt").asText()));
        JsonNode body = MAPPER.readTree(client().get(healthPath(instance), admin).body());
        assertFalse(body.has("state") || body.has("stateVersion"), "OR-39/OR-55: no top-level state or version");
    }

    @Test
    @Order(4)
    void aConfirmedStateMovesCheckedAtButNotTheVersion() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        JsonNode before = placementOf(admin);
        Instant checkedBefore = Instant.parse(before.get("checkedAt").asText());

        JsonNode after = await(admin, p -> Instant.parse(p.get("checkedAt").asText()).isAfter(checkedBefore),
                "a later check");

        assertEquals("GREEN", after.get("state").asText());
        assertEquals(before.get("stateVersion").asLong(), after.get("stateVersion").asLong(),
                "OR-39: only changes are persisted - the version does not move on a confirmation");
    }

    @Test
    @Order(5)
    void aDownAnswerIsRedAndOnlyThenTheVersionMoves() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        long greenVersion = placementOf(admin).get("stateVersion").asLong();
        answerStatus = 503;
        answerBody = "{\"status\":\"DOWN\"}";

        JsonNode red = await(admin, p -> "RED".equals(p.get("state").asText()), "RED");

        assertTrue(red.get("stateVersion").asLong() > greenVersion);
    }

    @Test
    @Order(6)
    void aSiblingEnvironmentsOperatorAndAForeignAdministratorSeeNoneOfItAs404() throws Exception {
        Client http = client();
        assertEquals(404, http.get(healthPath(instance), http.login(DeclareFixtures.OPERATOR_TWO)).statusCode());
        assertEquals(404, http.get(healthPath(instance), http.login(DeclareFixtures.FOREIGN)).statusCode());
        assertEquals(200, http.get(healthPath(instance), http.login(DeclareFixtures.VIEWER)).statusCode(),
                "a Viewer at the organization holds health.view");
    }

    @Test
    @Order(7)
    void aClosedPortOnTheRegisteredAddressReadsNotExposed() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        managedIntegration.stop(0);

        JsonNode closed = await(admin, p -> "not_exposed".equals(p.get("state").asText()), "not_exposed");

        assertEquals(placement.toString(), closed.get("placementId").asText());
    }

    @Test
    @Order(8)
    void thePersistedRowsAreTheOnlyThingRead() throws Exception {
        // a request never waits on a probe: with the stub gone and the poller still running, getHealth answers at once
        // The login (a password hash, slow under a loaded build) is NOT part of what is timed: only the read is.
        Login admin = client().login(DeclareFixtures.ADMIN);
        long start = System.nanoTime();
        HttpResponse<String> response = client().get(healthPath(instance), admin);
        assertEquals(200, response.statusCode());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "getHealth reads persisted rows only");
    }
}
