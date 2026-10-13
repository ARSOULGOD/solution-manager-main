package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.logs.LogLevelService;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
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
 * Plan 010 T9.3 (OR-103, option Rd): {@code getLogLevel} and {@code setLogLevel} are SERVED in the production boot, the
 * real {@code SolutionManagerApplication} over real HTTP against PostgreSQL, with the real Cache package fixture (which
 * now declares {@code logs.loggers}). Two instances: a direct-HTTP one, which has no SSH path and says so, and a via-SSH
 * one whose host is a closed loopback port, which exercises the real data-plane read through the real SSH transport and
 * must answer {@code unavailable} (never an error, never a guess). A live change is refused; a configured change is
 * {@code LogLevelConfigEndpointIT}'s.
 *
 * <p>What a live answer looks like against a host that answers is {@code LogLevelServiceImplTest}'s (a fake host). A
 * measured read against a real Cache is the owner-gated E2E, which no test here is.
 */
// plan 013 T1.2: the target is a loopback stub, which the strict address policy refuses; the test wires the
// permissive one (a test-sources bean, never reachable in production)
@Import(LoopbackTargetAddressConfiguration.class)
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogLevelEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID directInstance;
    private static UUID directPlacement;
    private static UUID sshInstance;
    private static UUID sshPlacement;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private LogLevelService logLevels;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws IOException {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        // targetTwo's registered SSH endpoint is a loopback port nothing listens on: the connection is refused at once
        ownerJdbc.update("UPDATE sm_data.target_connection SET host = '127.0.0.1', port = :p "
                + "WHERE tenant_id = :t AND target_id = :g",
                Map.of("t", world.organization(), "g", world.targetTwo(), "p", closedPort()));
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Client client() {
        return new Client(port);
    }

    private static String levelPath(UUID instance, String component) {
        return "/api/dp/instances/" + instance + "/log-levels/" + component;
    }

    private static ObjectNode change(UUID placement, String level, String mode) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("placementId", placement.toString());
        body.put("level", level);
        body.put("mode", mode);
        return body;
    }

    @Test
    @Order(1)
    void theLogLevelOperationsAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        assertTrue(logLevels != null, "the read service is a bean in the production boot");
        assertEquals(401, client().anonymous("GET", levelPath(UUID.randomUUID(), "cache-engine")).statusCode());
        assertEquals(401, client().anonymous("POST", levelPath(UUID.randomUUID(), "cache-engine")).statusCode());
    }

    @Test
    @Order(2)
    void twoInstancesAreDeclaredOneDirectHttpAndOneViaSsh() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());

        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> direct = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "direct-cache", "sm-direct", 18081));
        assertEquals(201, direct.statusCode(), direct.body());
        directInstance = UUID.fromString(MAPPER.readTree(direct.body()).get("instanceId").asText());
        directPlacement = UUID.fromString(MAPPER.readTree(direct.body()).at("/placements/0/placementId").asText());

        ObjectNode viaSsh = DeclareFixtures.declareBody(solutionId, world.targetTwo(), "ssh-cache", "sm-ssh", 18082);
        ((ObjectNode) viaSsh.get("acquisition")).put("mode", "via-ssh");
        // targetTwo is scoped to the sibling environment, so the organization's administrator declares onto it
        HttpResponse<String> ssh = http.post("/api/dp/environments/" + world.envTwo() + "/instances",
                http.login(DeclareFixtures.ADMIN), viaSsh);
        assertEquals(201, ssh.statusCode(), ssh.body());
        sshInstance = UUID.fromString(MAPPER.readTree(ssh.body()).get("instanceId").asText());
        sshPlacement = UUID.fromString(MAPPER.readTree(ssh.body()).at("/placements/0/placementId").asText());
        assertEquals("via-ssh", MAPPER.readTree(ssh.body()).at("/acquisition/mode").asText());
    }

    @Test
    @Order(3)
    void aDirectHttpPlacementIsUnavailableWithTheReasonAndNoLiveLevel() throws Exception {
        Client http = client();
        HttpResponse<String> response = http.get(levelPath(directInstance, "cache-engine"),
                http.login(DeclareFixtures.OPERATOR_ONE));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("cache-engine", body.get("component").asText());
        assertEquals(directPlacement.toString(), body.get("placementId").asText());
        assertEquals("unavailable", body.at("/availability/state").asText());
        assertTrue(body.at("/availability/reason").asText().contains("configuration"), response.body());
        assertFalse(body.has("liveLevel"));
        assertFalse(body.get("runtimeOverride").asBoolean());
    }

    @Test
    @Order(4)
    void aViaSshPlacementWhoseHostRefusesTheConnectionIsUnavailableNotAnError() throws Exception {
        Client http = client();
        long start = System.nanoTime();
        HttpResponse<String> response = http.get(levelPath(sshInstance, "cache-engine"),
                http.login(DeclareFixtures.OPERATOR_TWO));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals(sshPlacement.toString(), body.get("placementId").asText());
        assertEquals("unavailable", body.at("/availability/state").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
        assertFalse(body.has("liveLevel"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 20_000, "the read is bounded by the SSH timeouts");
    }

    @Test
    @Order(5)
    void aComponentThePackageDoesNotHaveIs404() throws Exception {
        Client http = client();
        assertEquals(404, http.get(levelPath(sshInstance, "no-such-component"),
                http.login(DeclareFixtures.ADMIN)).statusCode());
    }

    @Test
    @Order(6)
    void aLiveChangeIsRefusedAndAConfigChangeWithNothingStagedIs422() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.ADMIN);

        for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"}) {
            HttpResponse<String> config = http.post(levelPath(sshInstance, "cache-engine"), operator,
                    change(sshPlacement, level, "config"));
            // T9.4: a config change is admitted as an apply, but these instances have nothing staged to carry the level
            // (LogLevelConfigEndpointIT is the admitted path against a staged one)
            assertEquals(422, config.statusCode(), config.body());
            assertEquals("VALIDATION_FAILED", MAPPER.readTree(config.body()).get("code").asText());
            HttpResponse<String> live = http.post(levelPath(sshInstance, "cache-engine"), operator,
                    change(sshPlacement, level, "live"));
            assertEquals(501, live.statusCode(), live.body());
            assertNotEquals(202, live.statusCode());
        }
        HttpResponse<String> directLive = http.post(levelPath(directInstance, "cache-engine"), operator,
                change(directPlacement, "DEBUG", "live"));
        assertEquals(422, directLive.statusCode(), directLive.body());
        assertEquals("VALIDATION_FAILED", MAPPER.readTree(directLive.body()).get("code").asText());
        assertEquals(400, http.post(levelPath(sshInstance, "cache-engine"), operator,
                change(sshPlacement, "LOUD", "config")).statusCode());
    }

    @Test
    @Order(7)
    void aSiblingEnvironmentsOperatorAndAForeignAdministratorSeeNoneOfItAs404() throws Exception {
        Client http = client();
        for (String who : new String[] {DeclareFixtures.OPERATOR_ONE, DeclareFixtures.FOREIGN}) {
            Login login = http.login(who);
            assertEquals(404, http.get(levelPath(sshInstance, "cache-engine"), login).statusCode(), who);
            assertEquals(404, http.post(levelPath(sshInstance, "cache-engine"), login,
                    change(sshPlacement, "DEBUG", "config")).statusCode(), who);
        }
    }
}
