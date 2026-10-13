package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.launcher.AgentTargetFixtures.AgentTarget;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 015 T3.5: every target operation on an AGENT target answers what the plan's table says, through the real application over
 * real HTTP. The proof that no SSH was attempted is a counting double at the two places a connection starts, the host-key scan and
 * the connection test: zero calls for the agent target, while the same operations on the SSH target of the same organization still
 * reach them (so the counters are known to see).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentTargetEndpointIT.Doubles.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentTargetEndpointIT {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    static final AtomicInteger SCANS = new AtomicInteger();
    static final AtomicInteger TESTS = new AtomicInteger();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static AgentTarget agent;
    private static UUID instance;
    private static UUID loadedSolution;                  // loaded by the Order(4) case, enrolled into by the retire case

    @Value("${local.server.port}")
    private int port;
    @org.springframework.beans.factory.annotation.Autowired
    private com.solace.sm.dp.api.agw.AgentEnrollmentPort enrollments;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws Exception {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        // the enrolment CA, as AgentEndpointsIT composes it: the retire case enrols a real agent
        java.nio.file.Path keystore = AgentGatewayTestPki.keystore();
        registry.add("sm.control.agent-gateway.ca.key-store-file", keystore::toString);
        registry.add("sm.control.agent-gateway.ca.key-store-password", () -> AgentGatewayTestPki.PASSWORD);
        registry.add("sm.control.agent-gateway.ca.key-alias", () -> AgentGatewayTestPki.CA_ALIAS);
        registry.add("sm.control.agent-gateway.ca.connect-endpoint", () -> "agents.example.test:8443");
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        agent = AgentTargetFixtures.seed(ownerJdbc, world, "edge-endpoints");
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean
        @Primary
        HostKeyScanService countingScan() {
            return (host, port) -> {
                SCANS.incrementAndGet();
                throw new com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException();
            };
        }

        @Bean
        @Primary
        TargetConnectionTester countingTester() {
            return (organizationId, targetId) -> {
                TESTS.incrementAndGet();
                return new TargetConnectionTestResult(true, true, true, "27.0", java.time.Instant.now());
            };
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private Client client() {
        return new Client(port);
    }

    private HttpResponse<String> call(String method, String path, Login login, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(java.time.Duration.ofSeconds(30));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new TreeSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long n = ownerJdbc.queryForObject(sql, parameters, Long.class);
        return n == null ? 0 : n;
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).get("code").asText(), response.body());
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void listedWithTransportAgentAndTheConnectionIsTheAgentVariantWhileAnSshTargetIsStillTheSshOne() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);

        JsonNode list = json(http.get("/api/cp/targets", admin));
        JsonNode listed = null;
        JsonNode ssh = null;
        for (JsonNode item : list.get("items")) {
            if (item.get("targetId").asText().equals(agent.targetId().toString())) {
                listed = item;
            }
            if (item.get("targetId").asText().equals(world.targetOne().toString())) {
                ssh = item;
            }
        }
        assertNotNull(listed);
        assertEquals("agent", listed.get("transport").asText());
        assertEquals("docker", listed.get("kind").asText());
        assertEquals("/srv/sm", listed.get("allowedConfigRoot").asText());
        assertEquals("ssh-docker", ssh.get("transport").asText());

        JsonNode agentConnection = json(http.get("/api/dp/targets/" + agent.targetId() + "/connection", admin));
        assertEquals(Set.of("targetId", "transport", "agentId", "allowedConfigRoot", "allowedContainerPrefix"), fields(agentConnection),
                "exactly the AgentTargetConnection schema: no address, user, pin or credential");
        assertEquals("agent", agentConnection.get("transport").asText());
        assertEquals(agent.agentId().toString(), agentConnection.get("agentId").asText());
        JsonNode sshConnection = json(http.get("/api/dp/targets/" + world.targetOne() + "/connection", admin));
        assertEquals("ssh-docker", sshConnection.get("transport").asText());
        assertTrue(sshConnection.has("host") && sshConnection.has("credential") && !sshConnection.has("agentId"));
    }

    @Test
    @Order(2)
    void registerTargetCannotCreateAnAgentTarget() throws Exception {
        Client http = client();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", "sneaky").put("kind", "docker").put("transport", "agent");
        body.set("ownershipScope", MAPPER.createObjectNode().put("environmentId", world.envOne().toString()));
        body.putObject("connection").put("host", "h.example.test").put("sshUser", "sm")
                .put("hostKeyFingerprint", "SHA256:" + "A".repeat(43)).put("allowedConfigRoot", "/srv/sm")
                .put("allowedContainerPrefix", "sm-");
        body.put("privateKey", "k");
        HttpResponse<String> response = call("POST", "/api/cp/targets", http.login(DeclareFixtures.ADMIN), body);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(0, SCANS.get(), "refused before any scan");
    }

    // ---- the operations that address an SSH record -----------------------------------------------------------------------

    @Test
    @Order(3)
    void editRotateAndTestAnAgentTargetAre409TargetTransportUnsupportedAndNothingIsScannedOrTested() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCANS.set(0);
        TESTS.set(0);
        long events = count("SELECT count(*) FROM sm_data.target_connection_event WHERE tenant_id = :t", Map.of("t", world.organization()));
        long secrets = count("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t", Map.of("t", world.organization()));

        assertCode(call("PATCH", "/api/dp/targets/" + agent.targetId() + "/connection", admin,
                MAPPER.createObjectNode().put("host", "elsewhere.example.test")), 409, "TARGET_TRANSPORT_UNSUPPORTED");
        assertCode(call("PUT", "/api/dp/targets/" + agent.targetId() + "/credential", admin,
                MAPPER.createObjectNode().put("privateKey", "definitely not a key")), 409, "TARGET_TRANSPORT_UNSUPPORTED");
        // many tests: the per-session probe budget is never drawn, so the answer never becomes a 429
        for (int i = 0; i < 12; i++) {
            assertCode(call("POST", "/api/dp/targets/" + agent.targetId() + "/connection/test", admin, null), 409,
                    "TARGET_TRANSPORT_UNSUPPORTED");
        }

        assertEquals(0, SCANS.get(), "no host-key scan was made");
        assertEquals(0, TESTS.get(), "no connection test was made");
        assertEquals(events, count("SELECT count(*) FROM sm_data.target_connection_event WHERE tenant_id = :t",
                Map.of("t", world.organization())), "no event");
        assertEquals(secrets, count("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t", Map.of("t", world.organization())),
                "no secret stored");
        // the counter does see: the SSH target of the same organization reaches the tester
        assertEquals(200, call("POST", "/api/dp/targets/" + world.targetOne() + "/connection/test", admin, null).statusCode());
        assertEquals(1, TESTS.get());
    }

    // ---- placements come only from discovery -----------------------------------------------------------------------------

    @Test
    @Order(4)
    void aDeclareOrAnAddPlacementNamingTheAgentTargetIs409TargetIsAgentManagedAndWritesNothing() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        UUID solutionId = UUID.fromString(json(loaded).get("solutionId").asText());
        loadedSolution = solutionId;
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long instances = count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", Map.of("t", world.organization()));

        HttpResponse<String> declare = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, agent.targetId(), "on-agent", "sm-on-agent", 8085));
        assertCode(declare, 409, "TARGET_IS_AGENT_MANAGED");
        assertEquals(instances, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t",
                Map.of("t", world.organization())), "nothing was reserved, so nothing is left to sweep");

        HttpResponse<String> onSsh = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "on-ssh", "sm-on-ssh", 8086));
        assertEquals(201, onSsh.statusCode(), onSsh.body());
        instance = UUID.fromString(json(onSsh).get("instanceId").asText());
        long placements = count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", Map.of("t", world.organization()));
        ObjectNode add = MAPPER.createObjectNode().put("targetId", agent.targetId().toString());
        ObjectNode placement = add.putObject("placement");
        placement.put("containerName", "sm-second").put("configDirectory", "/srv/sm/sm-second")
                .put("containerConfigPath", "/app/external/spring/config").put("fileName", "application.yml")
                .put("healthPort", 8087);
        placement.putObject("metricsPorts").put("8081", 38087);
        assertCode(http.post("/api/dp/instances/" + instance + "/placements", operator, add), 409, "TARGET_IS_AGENT_MANAGED");
        assertEquals(placements, count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t",
                Map.of("t", world.organization())));
    }

    // ---- retirement ---------------------------------------------------------------------------------------------------------

    @Test
    @Order(5)
    void retireIs409AgentStillEnrolledUntilTheAgentIsRevokedThenRetiresDataPlaneFirstAndAnswersAReplay() throws Exception {
        // A REAL enrolment (W4 integration review F1): issued through issueAgentEnrolment and consumed through the enrolment port, so
        // the control plane holds the registration that revokeAgent revokes. The agent never opens a session: it is OFFLINE, so no
        // reconciliation pass will tell the data plane, which is the case the retire gate must handle itself.
        Login admin = client().login(DeclareFixtures.ADMIN);
        ObjectNode issue = MAPPER.createObjectNode();
        issue.put("targetName", "edge-retire").put("allowedConfigRoot", "/srv/sm").put("allowedContainerPrefix", "sm-")
                .put("solutionId", loadedSolution.toString()).put("packageVersion", 1);
        HttpResponse<String> issued = call("POST", "/api/cp/environments/" + world.envOne() + "/agent-enrolments", admin, issue);
        assertEquals(201, issued.statusCode(), issued.body());
        com.solace.sm.dp.api.agw.EnrollmentResult enrolled = enrollments.enroll(new com.solace.sm.dp.api.agw.EnrollmentRequest(
                "enroll-" + UUID.randomUUID(), json(issued).get("enrollmentToken").asText(), AgentGatewayTestPki.csr(), "0.1.0",
                java.util.List.of(1), "linux", "arm64", "docker",
                new com.solace.sm.dp.api.agw.AgentRequestOrigin(UUID.randomUUID(), "198.51.100.78")));
        UUID agentId = enrolled.agentId();
        UUID targetId = ownerJdbc.queryForObject("SELECT target_id FROM sm_data.agent_identity WHERE tenant_id = :t AND agent_id = :a",
                Map.of("t", world.organization(), "a", agentId), UUID.class);
        String path = "/api/cp/targets/" + targetId + "/retire";

        assertCode(call("POST", path, admin, null), 409, "AGENT_STILL_ENROLLED");
        assertNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", targetId), java.time.OffsetDateTime.class), "the data plane is untouched");
        assertNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_control.target WHERE target_id = :x",
                Map.of("x", targetId), java.time.OffsetDateTime.class), "so is the governance row");

        HttpResponse<String> revoked = call("POST", "/api/cp/agents/" + agentId + "/revoke", admin, null);
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertTrue(ownerJdbc.queryForObject("SELECT revoked_at IS NULL FROM sm_data.agent_identity WHERE tenant_id = :t"
                + " AND agent_id = :a", Map.of("t", world.organization(), "a", agentId), Boolean.class),
                "precondition: revokeAgent wrote the control plane only; the data plane has not been told");

        HttpResponse<String> retired = call("POST", path, admin, null);
        assertEquals(200, retired.statusCode(), retired.body());
        assertNotNull(json(retired).get("retiredAt"));
        assertFalse(ownerJdbc.queryForObject("SELECT retired_at IS NULL FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :x", Map.of("t", world.organization(), "x", targetId), Boolean.class));
        assertEquals(200, call("POST", path, admin, null).statusCode(), "the same call again is a replay");
        assertFalse(ownerJdbc.queryForObject("SELECT revoked_at IS NULL FROM sm_data.agent_identity WHERE tenant_id = :t"
                + " AND agent_id = :a", Map.of("t", world.organization(), "a", agentId), Boolean.class),
                "the retire gate applied the control plane's revocation to the data plane's identity");
    }
}
