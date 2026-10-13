package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.EnrollmentIssuance;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentResult;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * Plan 015 T3.5 (BE-D71, BE-D32's order): the agent target is created in BOTH planes at enrolment, and an enrolment interrupted
 * between them is listed degraded, completed by the agent's retry of the same token and CSR, and otherwise retired by the lazy
 * sweep. The real application over real HTTP against PostgreSQL 14, with the launcher's three adapters imported directly (the
 * gateway's gRPC path is T3.2's and is exercised in T4.4 and G-A1); the enrolment is driven through {@code AgentEnrollmentPort}
 * with a gateway-minted {@code AgentRequestOrigin}.
 *
 * <p>"The control plane's half only" is the crash window: {@link EnrollmentCertificateIssuer#issue} is exactly step (1), so
 * calling it alone leaves what a process death between the planes leaves.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentGatewayControlPlaneAdapterConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentEnrollmentTargetIT {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID admin;
    private static UUID solutionId;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AgentEnrolment enrolment;
    @Autowired
    private AgentEnrollmentPort enrollments;
    @Autowired
    private EnrollmentCertificateIssuer issuer;
    @Autowired
    private com.solace.sm.gateway.http.AgentEnrolmentSweepJob sweepJob;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws Exception {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        Path keystore = AgentGatewayTestPki.keystore();
        registry.add("sm.control.agent-gateway.ca.key-store-file", keystore::toString);
        registry.add("sm.control.agent-gateway.ca.key-store-password", () -> AgentGatewayTestPki.PASSWORD);
        registry.add("sm.control.agent-gateway.ca.key-alias", () -> AgentGatewayTestPki.CA_ALIAS);
        registry.add("sm.control.agent-gateway.ca.connect-endpoint", () -> "agents.example.test:8443");
        // OR-140: the sweep is a scheduled job; the test drives its passes by hand so no second pass races an assertion
        registry.add("sm.gateway.agent-sweep.enabled", () -> "false");
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        admin = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o AND username = :u",
                Map.of("o", world.organization(), "u", DeclareFixtures.ADMIN), UUID.class);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private UUID solution() throws Exception {
        if (solutionId == null) {
            Client http = client();
            ObjectNode load = MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
            assertEquals(201, loaded.statusCode(), loaded.body());
            solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());
        }
        return solutionId;
    }

    private AgentEnrolment.IssuedToken issue(String name) throws Exception {
        return enrolment.issue(new PrincipalRef(admin), world.organization(), new AgentEnrolment.Request(
                ScopePath.environment(world.organization(), world.unit(), world.envOne()), solution(), "1", name, "/opt/sm/config",
                "sm-", null), new RequestOrigin("203.0.113.40", UUID.randomUUID()));
    }

    private static final byte[] CSR_UNUSED = new byte[0];

    private static EnrollmentRequest request(String token, byte[] csr, AgentRequestOrigin origin) {
        return new EnrollmentRequest("enroll-" + UUID.randomUUID(), token, csr, "0.1.0", List.of(1), "linux", "arm64", "docker",
                origin);
    }

    private static AgentRequestOrigin origin() {
        return new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77");
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long n = ownerJdbc.queryForObject(sql, parameters, Long.class);
        return n == null ? 0 : n;
    }

    private JsonNode targets() throws Exception {
        Client http = client();
        HttpResponse<String> response = http.get("/api/cp/targets", http.login(DeclareFixtures.ADMIN));
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1).build();

    private HttpResponse<String> call(String method, String path, Login login) throws Exception {
        return HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(java.time.Duration.ofSeconds(30)).method(method, java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static JsonNode item(JsonNode list, String name) {
        for (JsonNode node : list.get("items")) {
            if (node.get("name").asText().equals(name)) {
                return node;
            }
        }
        return null;
    }

    // ---- the happy path: both planes, one enrolment ---------------------------------------------------------------

    @Test
    @Order(1)
    void anEnrolmentCreatesOneTargetInEachPlaneWithTheTokensEnvelopeAndTheIssuingPrincipal() throws Exception {
        AgentEnrolment.IssuedToken issued = issue("edge-host-1");
        AgentRequestOrigin origin = origin();
        byte[] csr = AgentGatewayTestPki.csr();

        EnrollmentResult result = enrollments.enroll(request(issued.token(), csr, origin));

        assertEquals(issued.agentId(), result.agentId());
        assertEquals(world.envOne(), result.environmentId());
        Map<String, Object> cp = ownerJdbc.queryForMap("SELECT name, kind, transport, created_by FROM sm_control.target"
                + " WHERE target_id = :t", Map.of("t", result.targetId()));
        assertEquals("edge-host-1", cp.get("name"));
        assertEquals("docker", cp.get("kind"));
        assertEquals("agent", cp.get("transport"));
        assertEquals(admin, cp.get("created_by"));
        List<Map<String, Object>> audit = ownerJdbc.queryForList("SELECT audit_operation, actor_agent_id, actor_principal_id,"
                + " client_address FROM sm_control.change_audit WHERE table_name = 'target' AND correlation_id = :c",
                Map.of("c", origin.correlationId()));
        assertEquals(1, audit.size());
        assertEquals("AGENT_ENROLLED", audit.get(0).get("audit_operation"));
        assertEquals(issued.agentId(), audit.get(0).get("actor_agent_id"));
        assertNull(audit.get(0).get("actor_principal_id"));
        assertEquals("198.51.100.77", audit.get(0).get("client_address"));

        Map<String, Object> dp = ownerJdbc.queryForMap("SELECT transport, agent_id, allowed_config_root, allowed_container_prefix,"
                + " created_by, host, ssh_user, credential_ref FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", result.targetId()));
        assertEquals("agent", dp.get("transport"));
        assertEquals(issued.agentId(), dp.get("agent_id"));
        assertEquals("/opt/sm/config", dp.get("allowed_config_root"), "the envelope is the TOKEN's, never the agent's");
        assertEquals("sm-", dp.get("allowed_container_prefix"));
        assertEquals(admin, dp.get("created_by"), "created_by is the principal who issued the token");
        assertNull(dp.get("host"));
        assertNull(dp.get("ssh_user"));
        assertNull(dp.get("credential_ref"));
        String thumbprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.certificateChainDer().get(0)));
        assertEquals(thumbprint, ownerJdbc.queryForObject("SELECT certificate_thumbprint FROM sm_data.agent_identity"
                + " WHERE tenant_id = :t AND agent_id = :a", Map.of("t", world.organization(), "a", issued.agentId()), String.class));
        Map<String, Object> event = ownerJdbc.queryForMap("SELECT kind, actor_kind, actor, correlation_id, client_address"
                + " FROM sm_data.target_connection_event WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", result.targetId()));
        assertEquals("enrolled", event.get("kind"));
        assertEquals("agent", event.get("actor_kind"));
        assertEquals(issued.agentId(), event.get("actor"));
        assertEquals(origin.correlationId(), event.get("correlation_id"));

        // the listing shows it as an agent target with its envelope and nothing degraded
        JsonNode list = targets();
        JsonNode listed = item(list, "edge-host-1");
        assertNotNull(listed);
        assertEquals("agent", listed.get("transport").asText());
        assertEquals("/opt/sm/config", listed.get("allowedConfigRoot").asText());
        assertTrue(list.path("availability").isMissingNode() || list.path("availability").isNull()
                || !"degraded".equals(list.at("/availability/state").asText()), list.toString());

        // a replay of the same token and CSR answers the same ids and writes nothing
        long events = count("SELECT count(*) FROM sm_data.target_connection_event WHERE tenant_id = :t",
                Map.of("t", world.organization()));
        EnrollmentResult replay = enrollments.enroll(request(issued.token(), csr, origin()));
        assertEquals(result.targetId(), replay.targetId());
        assertEquals(events, count("SELECT count(*) FROM sm_data.target_connection_event WHERE tenant_id = :t",
                Map.of("t", world.organization())));
        assertEquals(1, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND agent_id = :a",
                Map.of("t", world.organization(), "a", issued.agentId())));
    }

    @Test
    @Order(2)
    void aPlacementOutsideTheTokensEnvelopeOnTheAgentTargetIsRefusedByTheDatabase() throws Exception {
        UUID target = ownerJdbc.queryForObject("SELECT target_id FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND transport = 'agent' ORDER BY created_at LIMIT 1", Map.of("t", world.organization()), UUID.class);
        UUID instance = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, 'active', 'via-ssh', :u, now())",
                Map.of("t", world.organization(), "i", instance, "e", world.envOne(), "c", UUID.randomUUID(),
                        "n", "env-" + instance, "u", UUID.randomUUID()));
        for (String[] bad : new String[][] {{"other-container", "/opt/sm/config/x"}, {"sm-ok", "/etc/outside"}}) {
            assertThrows(RuntimeException.class, () -> ownerJdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id,"
                    + " instance_id, target_id, container_name, remote_config_directory, container_config_path, file_name,"
                    + " mechanism, retained_generations, created_at) VALUES (:t, :p, :i, :x, :c, :d,"
                    + " '/app/external/spring/config', 'application.yml', 'ADDITIONAL_LOCATION', 10, now())",
                    new MapSqlParameterSource().addValue("t", world.organization()).addValue("p", UUID.randomUUID())
                            .addValue("i", instance).addValue("x", target).addValue("c", bad[0]).addValue("d", bad[1])),
                    "the envelope the token fixed bounds every placement on the agent's target: " + bad[0] + " " + bad[1]);
        }
    }

    // ---- the crash window --------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void anEnrolmentInterruptedBetweenThePlanesIsListedDegradedAndTheRetryCompletesItWithTheSameIds() throws Exception {
        AgentEnrolment.IssuedToken issued = issue("edge-half");
        byte[] csr = AgentGatewayTestPki.csr();
        AgentRequestOrigin first = origin();

        // step (1) alone: the control plane's target and certificate, then "the process dies"
        EnrollmentIssuance half = issuer.issue(request(issued.token(), csr, first));
        assertEquals(1, count("SELECT count(*) FROM sm_control.target WHERE target_id = :t", Map.of("t", half.targetId())));
        assertEquals(0, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", half.targetId())), "no orphan data-plane row exists");

        JsonNode list = targets();
        JsonNode listed = item(list, "edge-half");
        assertNotNull(listed, "never hidden");
        assertEquals("agent", listed.get("transport").asText());
        assertNull(listed.get("allowedConfigRoot"), "no envelope: the data plane holds no connection record");
        assertEquals("degraded", list.at("/availability/state").asText());

        // the agent's retry of the SAME token and CSR replays step (1) to the same ids and completes step (2)
        EnrollmentResult done = enrollments.enroll(request(issued.token(), csr, origin()));
        assertEquals(half.targetId(), done.targetId());
        assertEquals(half.agentId(), done.agentId());
        assertEquals(1, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", half.targetId())));
        assertEquals(1, count("SELECT count(*) FROM sm_data.agent_identity WHERE tenant_id = :t AND agent_id = :a",
                Map.of("t", world.organization(), "a", half.agentId())));
        JsonNode after = targets();
        assertEquals("/opt/sm/config", item(after, "edge-half").get("allowedConfigRoot").asText());
    }

    @Test
    @Order(4)
    void anInterruptedEnrolmentTheAgentNeverRetriesIsRetiredByTheSweepOnlyAfterTheWindowAndOnlyWhileDegraded() throws Exception {
        AgentEnrolment.IssuedToken fresh = issue("edge-young");
        AgentEnrolment.IssuedToken old = issue("edge-old");
        EnrollmentIssuance youngHalf = issuer.issue(request(fresh.token(), AgentGatewayTestPki.csr(), origin()));
        EnrollmentIssuance oldHalf = issuer.issue(request(old.token(), AgentGatewayTestPki.csr(), origin()));
        // the young token expired a minute ago (inside the ten-minute window); the old one an hour ago
        ownerJdbc.update("UPDATE sm_control.agent_registration SET expires_at = now() - interval '1 minute' WHERE agent_id = :a",
                Map.of("a", youngHalf.agentId()));
        ownerJdbc.update("UPDATE sm_control.agent_registration SET expires_at = now() - interval '1 hour', created_at ="
                + " now() - interval '2 hours' WHERE agent_id = :a", Map.of("a", oldHalf.agentId()));

        // OR-140 (Q-C2): a reach-level GET writes nothing to the control plane. The old target is listed degraded, still active.
        JsonNode read = targets();
        assertNotNull(item(read, "edge-old"), "a GET no longer sweeps: the abandoned target is still listed");
        assertEquals("active", ownerJdbc.queryForObject("SELECT status FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", oldHalf.agentId()), String.class));
        assertEquals(0, count("SELECT count(*) FROM sm_control.change_audit WHERE audit_operation IN ('AGENT_REVOKED', 'TARGET_RETIRED')"
                + " AND (row_key::text LIKE :agent OR row_key::text LIKE :target)",
                Map.of("agent", "%" + oldHalf.agentId() + "%", "target", "%" + oldHalf.targetId() + "%")),
                "no revocation was triggered by the read");

        sweepJob.runOnce();                  // the scheduled job

        JsonNode list = targets();
        assertNotNull(item(list, "edge-young"), "inside the window the agent may still be about to retry: left alone");
        assertNull(item(list, "edge-old"), "a retired target is not listed");
        assertTrue(ownerJdbc.queryForObject("SELECT retired_at IS NOT NULL FROM sm_control.target WHERE target_id = :t",
                Map.of("t", oldHalf.targetId()), Boolean.class));
        assertEquals("revoked", ownerJdbc.queryForObject("SELECT status FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", oldHalf.agentId()), String.class), "revoked first, so a late replay cannot resurrect it");
        // attribution (OR-140): the SYSTEM ACTOR, never the token's issuer, never a requester, never the agent; one row per write
        List<Map<String, Object>> revoked = ownerJdbc.queryForList("SELECT actor_principal_id, actor_agent_id, actor_system_id,"
                + " client_address FROM sm_control.change_audit WHERE table_name = 'agent_registration'"
                + " AND audit_operation = 'AGENT_REVOKED' AND row_key::text LIKE :agent",
                Map.of("agent", "%" + oldHalf.agentId() + "%"));
        List<Map<String, Object>> retired = ownerJdbc.queryForList("SELECT actor_principal_id, actor_agent_id, actor_system_id,"
                + " client_address FROM sm_control.change_audit WHERE table_name = 'target'"
                + " AND audit_operation = 'TARGET_RETIRED' AND row_key::text LIKE :target",
                Map.of("target", "%" + oldHalf.targetId() + "%"));
        assertEquals(1, revoked.size(), "one AGENT_REVOKED row on the registration");
        assertEquals(1, retired.size(), "one TARGET_RETIRED row on the target");
        for (Map<String, Object> row : List.of(revoked.get(0), retired.get(0))) {
            assertEquals(com.solace.sm.cp.identity.SystemActors.AGENT_JOBS, row.get("actor_system_id"), row.toString());
            assertNull(row.get("actor_principal_id"), "never the issuer (" + admin + ") nor a requester: " + row);
            assertNull(row.get("actor_agent_id"));
            assertEquals("system-job", row.get("client_address"));
        }
        assertEquals(0, count("SELECT count(*) FROM sm_control.change_audit WHERE actor_principal_id = :admin"
                + " AND audit_operation IN ('AGENT_REVOKED', 'TARGET_RETIRED') AND (row_key::text LIKE :agent"
                + " OR row_key::text LIKE :target)", Map.of("admin", admin, "agent", "%" + oldHalf.agentId() + "%",
                "target", "%" + oldHalf.targetId() + "%")), "the issuer never appears as the actor of the sweep's writes");
        // a second pass is a no-op: the conditional writes change nothing and write no further audit row
        sweepJob.runOnce();
        assertEquals(1, ownerJdbc.queryForList("SELECT 1 FROM sm_control.change_audit WHERE table_name = 'target'"
                + " AND audit_operation = 'TARGET_RETIRED' AND row_key::text LIKE :target",
                Map.of("target", "%" + oldHalf.targetId() + "%")).size());
        assertEquals(0, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", oldHalf.targetId())));
        // the late replay is refused (a revoked registration holds no active token) and creates no data-plane row
        assertThrows(RuntimeException.class, () -> enrollments.enroll(request(old.token(), AgentGatewayTestPki.csr(), origin())));
        assertEquals(0, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", oldHalf.targetId())));
    }

    @Test
    @Order(5)
    void aCompletedEnrolmentIsNeverSweptHoweverOldItsTokenIs() throws Exception {
        AgentEnrolment.IssuedToken issued = issue("edge-complete");
        EnrollmentResult result = enrollments.enroll(request(issued.token(), AgentGatewayTestPki.csr(), origin()));
        ownerJdbc.update("UPDATE sm_control.agent_registration SET expires_at = now() - interval '5 hours',"
                + " created_at = now() - interval '6 hours' WHERE agent_id = :a", Map.of("a", issued.agentId()));

        sweepJob.runOnce();
        JsonNode list = targets();

        assertNotNull(item(list, "edge-complete"));
        assertFalse(ownerJdbc.queryForObject("SELECT retired_at IS NOT NULL FROM sm_control.target WHERE target_id = :t",
                Map.of("t", result.targetId()), Boolean.class));
    }

    @Test
    @Order(6)
    void aTargetNameAPendingTokenHoldsCannotBeReservedByASecondToken() throws Exception {
        issue("edge-reserved");
        assertThrows(IllegalStateException.class, () -> issue("edge-reserved"));
    }

    // ---- fix round --------------------------------------------------------------------------------------------------------

    @Test
    @Order(7)
    void retiringADegradedAgentTargetIs409WhileItsRegistrationIsActiveAndAReplayIsThenRefused() throws Exception {
        AgentEnrolment.IssuedToken issued = issue("edge-degraded-retire");
        byte[] csr = AgentGatewayTestPki.csr();
        EnrollmentIssuance half = issuer.issue(request(issued.token(), csr, origin()));
        Login admin = client().login(DeclareFixtures.ADMIN);
        String path = "/api/cp/targets/" + half.targetId() + "/retire";

        HttpResponse<String> refused = call("POST", path, admin);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("AGENT_STILL_ENROLLED", json(refused).get("code").asText());
        assertNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_control.target WHERE target_id = :t",
                Map.of("t", half.targetId()), java.time.OffsetDateTime.class), "the control plane is untouched");
        assertEquals("active", ownerJdbc.queryForObject("SELECT status FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", half.agentId()), String.class));
        // the agent's retry still completes the enrolment: the refusal destroyed nothing
        assertEquals(half.targetId(), enrollments.enroll(request(issued.token(), csr, origin())).targetId());
        assertEquals(1, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", half.targetId())));

        // a second, never-completed enrolment: once the operator REVOKES the registration the retire is allowed, and a replay is refused
        AgentEnrolment.IssuedToken second = issue("edge-degraded-retire-2");
        byte[] csr2 = AgentGatewayTestPki.csr();
        EnrollmentIssuance half2 = issuer.issue(request(second.token(), csr2, origin()));
        ownerJdbc.update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now() WHERE agent_id = :a",
                Map.of("a", half2.agentId()));
        assertEquals(200, call("POST", "/api/cp/targets/" + half2.targetId() + "/retire", admin).statusCode());
        assertThrows(RuntimeException.class, () -> enrollments.enroll(request(second.token(), csr2, origin())));
        assertEquals(0, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", half2.targetId())), "a revoked registration writes no data plane row");
    }

    @Test
    @Order(8)
    void aReplayWhoseTargetTheControlPlaneRetiredIsRefusedEvenWhileTheRegistrationIsStillActive() throws Exception {
        AgentEnrolment.IssuedToken issued = issue("edge-retired-replay");
        byte[] csr = AgentGatewayTestPki.csr();
        EnrollmentIssuance half = issuer.issue(request(issued.token(), csr, origin()));
        ownerJdbc.update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = :t", Map.of("t", half.targetId()));

        assertThrows(RuntimeException.class, () -> enrollments.enroll(request(issued.token(), csr, origin())));

        assertEquals(0, count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                Map.of("t", world.organization(), "x", half.targetId())), "no live data plane row under a retired control plane target");
    }

    @Test
    @Order(9)
    void aHealthyMajorityDoesNotStarveTheSweepOfADegradedTargetBehindIt() throws Exception {
        for (int i = 0; i < 21; i++) {
            AgentEnrolment.IssuedToken issued = issue("edge-healthy-" + i);
            EnrollmentResult done = enrollments.enroll(request(issued.token(), AgentGatewayTestPki.csr(), origin()));
            // their tokens expired long ago, the OLDEST in the organization, so they fill the first pages
            ownerJdbc.update("UPDATE sm_control.agent_registration SET expires_at = now() - interval '1 day' - (:i * interval '1 minute'),"
                    + " created_at = now() - interval '2 days' WHERE agent_id = :a", Map.of("a", done.agentId(), "i", i));
        }
        AgentEnrolment.IssuedToken late = issue("edge-behind-the-healthy");
        EnrollmentIssuance degraded = issuer.issue(request(late.token(), AgentGatewayTestPki.csr(), origin()));
        ownerJdbc.update("UPDATE sm_control.agent_registration SET expires_at = now() - interval '30 minutes' WHERE agent_id = :a",
                Map.of("a", degraded.agentId()));

        sweepJob.runOnce();             // one pass
        JsonNode list = targets();

        assertNull(item(list, "edge-behind-the-healthy"), "the degraded target was reached and retired");
        for (int i = 0; i < 21; i++) {
            assertNotNull(item(list, "edge-healthy-" + i), "a completed enrolment is never touched: " + i);
        }
        assertEquals(1, count("SELECT count(*) FROM sm_control.agent_registration WHERE organization_id = :o AND status = 'revoked'"
                + " AND agent_id = :a", Map.of("o", world.organization(), "a", degraded.agentId())));
    }
}
