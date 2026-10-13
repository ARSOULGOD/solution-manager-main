package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
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
 * Plan 010 T9.1: {@code readLogs} is SERVED in the production boot - the real {@code SolutionManagerApplication} over
 * real HTTP against PostgreSQL, the real {@code LogService} and the real SSH transport. The target's registered address
 * is loopback on a port nothing listens on, so the transport's own connect is refused: this proves what a request to an
 * unreachable host looks like END TO END (a declared degraded page, never an empty one and never a {@code 500}), and
 * the routing, authorization and 404/400 rules around it. The served-lines path against a real host is plan 010 T6.6's.
 */
// plan 013 T1.2: the target is a loopback stub, which the strict address policy refuses; the test wires the
// permissive one (a test-sources bean, never reachable in production)
@Import(LoopbackTargetAddressConfiguration.class)
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogsEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static World world;
    private static UUID instance;
    private static UUID placement;

    @Value("${local.server.port}")
    private int port;

    @Autowired(required = false)
    private LogService logService;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        NamedParameterJdbcTemplate ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        // the registered address is loopback on a closed port: nothing leaves this machine
        ownerJdbc.update("UPDATE sm_data.target_connection SET host = '127.0.0.1', port = :p"
                        + " WHERE tenant_id = :t AND target_id = :g",
                Map.of("p", closedPort, "t", world.organization(), "g", world.targetOne()));
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static String logsPath(UUID id, String component, String query) {
        return "/api/dp/instances/" + id + "/logs/" + component + query;
    }

    @Test
    @Order(1)
    void readLogsIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        assertNotNull(logService, "the log service is live in the production boot");
        assertEquals(401, client().anonymous("GET", logsPath(UUID.randomUUID(), "cache-engine", "")).statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredOnTheLoopbackTarget() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        UUID solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());

        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", 8081));
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode json = MAPPER.readTree(declared.body());
        instance = UUID.fromString(json.get("instanceId").asText());
        placement = UUID.fromString(json.at("/placements/0/placementId").asText());
    }

    @Test
    @Order(3)
    void anUnreachableHostIsADeclaredUnavailablePageNotAnEmptyOneAndNotAnError() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.get(logsPath(instance, "cache-engine", "?limit=50"),
                http.login(DeclareFixtures.OPERATOR_ONE));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("unavailable", body.at("/availability/state").asText());
        String reason = body.at("/availability/reason").asText();
        assertFalse(reason.isBlank(), "never an empty page presented as 'no logs'");
        assertFalse(reason.contains("127.0.0.1") || reason.contains("sm-orders") || reason.contains("/srv/sm"),
                "I6: the reason names no host, container or path: " + reason);
        assertTrue(body.get("lines").isArray() && body.get("lines").isEmpty());
        assertFalse(body.has("stateVersion"), "OR-38");
    }

    @Test
    @Order(4)
    void theNamedPlacementIsAcceptedAndAForeignOneIsNotFound() throws Exception {
        Client http = client();
        DeclareFixtures.Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        assertEquals(200, http.get(logsPath(instance, "cache-engine", "?placementId=" + placement), operator).statusCode());
        assertEquals(404, http.get(logsPath(instance, "cache-engine", "?placementId=" + UUID.randomUUID()), operator)
                .statusCode());
    }

    @Test
    @Order(5)
    void aComponentThePackageDoesNotDeclareIs404() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.get(logsPath(instance, "file-mesh", ""), http.login(DeclareFixtures.ADMIN));

        assertEquals(404, response.statusCode());
        assertEquals("NOT_FOUND", MAPPER.readTree(response.body()).get("code").asText());
    }

    @Test
    @Order(6)
    void aBadBoundIs400() throws Exception {
        Client http = client();
        DeclareFixtures.Login admin = http.login(DeclareFixtures.ADMIN);

        for (String bad : new String[] {"?limit=0", "?limit=1001", "?after=nonsense",
                "?after=2026-10-06T06:25:09Z&before=2026-10-06T06:25:01Z"}) {
            assertEquals(400, http.get(logsPath(instance, "cache-engine", bad), admin).statusCode(), bad);
        }
    }

    @Test
    @Order(7)
    void aSiblingEnvironmentsOperatorAndAForeignAdministratorSeeNoneOfItAs404ButAViewerMayRead() throws Exception {
        Client http = client();

        assertEquals(404, http.get(logsPath(instance, "cache-engine", ""), http.login(DeclareFixtures.OPERATOR_TWO))
                .statusCode());
        assertEquals(404, http.get(logsPath(instance, "cache-engine", ""), http.login(DeclareFixtures.FOREIGN))
                .statusCode());
        assertEquals(200, http.get(logsPath(instance, "cache-engine", ""), http.login(DeclareFixtures.VIEWER))
                .statusCode(), "a Viewer at the organization holds logs.read");
    }
}
