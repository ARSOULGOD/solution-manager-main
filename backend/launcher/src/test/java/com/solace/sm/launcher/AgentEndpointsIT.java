package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.agw.AgentDrainPort;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Plan 015 T4.3 (D10 rows 44-47): the five agent operations through the real application over real HTTP against PostgreSQL 14.
 *
 * <p>The gateway's gRPC listener is not composed here (its sessions are proven in {@code AgentGatewayServiceTest}, and
 * {@code AgentDrainCompositionIT} proves the composed bean is the real one): the drain request is answered by a recording double
 * that stands in for "this agent has a live session on the gateway". An enrolled agent is made through the data plane's own
 * {@code AgentEnrollmentPort}, as {@code AgentEnrollmentTargetIT} does, and a session through {@code AgentSessionPort}, so the
 * revocation is observed on the real reconciliation path the gateway runs every interval.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({AgentGatewayControlPlaneAdapterConfiguration.class, AgentEndpointsIT.Doubles.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentEndpointsIT {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    /** The agents the "gateway" double holds a live session for; every drain request it receives is recorded. */
    static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();
    static final java.util.List<String> REASONS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.List<UUID> DRAINED = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final Duration RECONNECT_AFTER = Duration.ofSeconds(7);

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID adminId;
    private static UUID solutionId;
    private static JsonNode issued;                 // the first issue's 201 body, kept for later steps
    private static UUID enrolledAgent;
    private static String enrolledThumbprint;

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private AgentEnrollmentPort enrollments;
    @Autowired
    private AgentSessionPort sessions;
    @Autowired
    private AgentIdentityPort identities;

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
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        adminId = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o AND username = :u",
                Map.of("o", world.organization(), "u", DeclareFixtures.ADMIN), UUID.class);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        /** Stands in for the gateway's live session map: the one thing this IT does not compose. */
        @Bean
        @Primary
        AgentDrainPort recordingGateway() {
            return (agentId, reason) -> {
                DRAINED.add(agentId);
                REASONS.add(reason);
                return LIVE.contains(agentId) ? Optional.of(RECONNECT_AFTER) : Optional.empty();
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
                .timeout(Duration.ofSeconds(30));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).get("code").asText(), response.body());
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long n = ownerJdbc.queryForObject(sql, parameters, Long.class);
        return n == null ? 0 : n;
    }

    private long registrations() {
        return count("SELECT count(*) FROM sm_control.agent_registration WHERE organization_id = :o", Map.of("o", world.organization()));
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

    private ObjectNode issueBody(String name) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("targetName", name).put("allowedConfigRoot", "/srv/sm").put("allowedContainerPrefix", "sm-")
                .put("solutionId", solution().toString()).put("packageVersion", 1);
        return body;
    }

    private String issuePath(UUID environment) {
        return "/api/cp/environments/" + environment + "/agent-enrolments";
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- issue --------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void anAdministratorIssuesATokenShownOnceWithTheEnvelopeAndNoTokenIsStoredOrAudited() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        Instant before = Instant.now();
        HttpResponse<String> response = call("POST", issuePath(world.envOne()), admin, issueBody("edge-issued"));
        assertEquals(201, response.statusCode(), response.body());
        issued = json(response);
        String token = issued.get("enrollmentToken").asText();
        assertTrue(token.length() >= 43, "256 random bits, base64url");
        assertEquals(world.envOne().toString(), issued.get("environmentId").asText());
        assertEquals("edge-issued", issued.get("targetName").asText());
        assertEquals("/srv/sm", issued.get("allowedConfigRoot").asText());
        assertEquals("sm-", issued.get("allowedContainerPrefix").asText());
        assertEquals(solution().toString(), issued.get("solutionId").asText());
        assertEquals(1, issued.get("packageVersion").asInt());
        // OR-136 (1): the default lifetime is 24 hours
        Instant expires = Instant.parse(issued.get("expiresAt").asText());
        assertTrue(expires.isAfter(before.plus(Duration.ofHours(24)).minusSeconds(60))
                && expires.isBefore(Instant.now().plus(Duration.ofHours(24)).plusSeconds(60)), expires.toString());
        assertEquals(Set.of("agentId", "enrollmentToken", "expiresAt", "environmentId", "targetName", "allowedConfigRoot",
                "allowedContainerPrefix", "solutionId", "packageVersion"), fieldNames(issued));

        // I6: only the digest of the token is stored; the token appears in no row of either table the call touched
        UUID agent = UUID.fromString(issued.get("agentId").asText());
        assertEquals(sha256Hex(token), ownerJdbc.queryForObject("SELECT encode(enrollment_token_digest, 'hex') FROM"
                + " sm_control.agent_registration WHERE agent_id = :a", Map.of("a", agent), String.class));
        assertEquals(0, count("SELECT count(*) FROM sm_control.agent_registration r WHERE r::text LIKE '%' || :t || '%'", Map.of("t", token)));
        assertEquals(0, count("SELECT count(*) FROM sm_control.change_audit a WHERE a::text LIKE '%' || :t || '%'", Map.of("t", token)));
        List<Map<String, Object>> audit = ownerJdbc.queryForList("SELECT actor_principal_id, actor_agent_id, audit_operation FROM"
                + " sm_control.change_audit WHERE table_name = 'agent_registration' AND row_key::text LIKE :a",
                Map.of("a", "%" + agent + "%"));
        assertEquals(1, audit.size());
        assertEquals("AGENT_ENROLMENT_ISSUED", audit.get(0).get("audit_operation"));
        assertEquals(adminId, audit.get(0).get("actor_principal_id"));
        assertNull(audit.get(0).get("actor_agent_id"));

        // shown once: neither the single read nor the list ever returns it again
        HttpResponse<String> get = call("GET", "/api/cp/agents/" + agent, admin, null);
        assertEquals(200, get.statusCode(), get.body());
        assertFalse(get.body().contains(token), "a second GET never returns the token");
        assertFalse(call("GET", "/api/cp/agents", admin, null).body().contains(token), "nor does the list");
        JsonNode read = json(get);
        assertEquals("pending", read.get("status").asText());
        assertNull(read.get("session"), "never connected");
        assertNull(read.get("targetId"), "its target is made at enrolment");
        assertEquals(Set.of("agentId", "environmentId", "targetName", "status", "allowedConfigRoot",
                "allowedContainerPrefix", "solutionId", "packageVersion", "capabilities", "createdAt"), fieldNames(read));
    }

    @Test
    @Order(2)
    void aMalformedBodyAnUnloadedSolutionAndATakenNameAreRefusedAndNothingIsWritten() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        long before = registrations();
        // structure is 400; an envelope VALUE outside its grammar is 422, as registerTarget's is (W4 lane B review, MINOR 4)
        for (String[] bad : new String[][] {{"targetName", ""}, {"packageVersion", "0"}, {"allowedConfigRoot", ""}}) {
            ObjectNode body = issueBody("edge-bad");
            if (bad[0].equals("packageVersion")) {
                body.put("packageVersion", 0);
            } else {
                body.put(bad[0], bad[1]);
            }
            assertCode(call("POST", issuePath(world.envOne()), admin, body), 400, "BAD_REQUEST");
        }
        ObjectNode missing = issueBody("edge-bad");
        missing.remove("solutionId");
        assertCode(call("POST", issuePath(world.envOne()), admin, missing), 400, "BAD_REQUEST");
        for (String[] bad : new String[][] {{"allowedConfigRoot", "relative/path"}, {"allowedConfigRoot", "/srv/../etc"},
                {"allowedContainerPrefix", "-bad"}}) {
            ObjectNode body = issueBody("edge-bad");
            body.put(bad[0], bad[1]);
            HttpResponse<String> refused = call("POST", issuePath(world.envOne()), admin, body);
            assertCode(refused, 422, "VALIDATION_FAILED");
            assertFalse(refused.body().contains(bad[1]), "the value is never echoed");
        }
        ObjectNode unknown = issueBody("edge-bad");
        unknown.put("organizationId", UUID.randomUUID().toString());
        assertCode(call("POST", issuePath(world.envOne()), admin, unknown), 400, "BAD_REQUEST");
        ObjectNode capabilities = issueBody("edge-bad");
        capabilities.putArray("capabilities").add("report-only");
        assertCode(call("POST", issuePath(world.envOne()), admin, capabilities), 400, "BAD_REQUEST");

        ObjectNode unloaded = issueBody("edge-unloaded");
        unloaded.put("solutionId", UUID.randomUUID().toString());
        assertCode(call("POST", issuePath(world.envOne()), admin, unloaded), 422, "VALIDATION_FAILED");
        ObjectNode otherVersion = issueBody("edge-unloaded");
        otherVersion.put("packageVersion", 99);
        assertCode(call("POST", issuePath(world.envOne()), admin, otherVersion), 422, "VALIDATION_FAILED");

        HttpResponse<String> taken = call("POST", issuePath(world.envOne()), admin, issueBody("edge-issued"));
        assertCode(taken, 409, "CONFLICT");
        assertFalse(taken.body().contains("edge-issued"), "the refusal echoes nothing the request carried");
        HttpResponse<String> heldByTarget = call("POST", issuePath(world.envOne()), admin, issueBody("target-one"));
        assertCode(heldByTarget, 409, "CONFLICT");
        assertEquals(before, registrations(), "none of the refusals left a row");
    }

    // ---- who may do what ----------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void aViewerOnlyViewsAnOperatorViewsAndDrainsNeitherIssuesNorRevokes() throws Exception {
        Client http = client();
        Login viewer = http.login(DeclareFixtures.VIEWER);
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        String agent = issued.get("agentId").asText();
        long before = registrations();
        int drainedBefore = DRAINED.size();

        for (Login reader : List.of(viewer, operator)) {
            assertEquals(200, call("GET", "/api/cp/agents/" + agent, reader, null).statusCode());
            assertTrue(json(call("GET", "/api/cp/agents", reader, null)).get("items").size() >= 1);
        }
        for (Login denied : List.of(viewer, operator)) {
            assertCode(call("POST", issuePath(world.envOne()), denied, issueBody("edge-denied")), 403, "FORBIDDEN");
            assertCode(call("POST", "/api/cp/agents/" + agent + "/revoke", denied, null), 403, "FORBIDDEN");
        }
        assertCode(call("POST", "/api/dp/agents/" + agent + "/drain", viewer, null), 403, "FORBIDDEN");
        assertEquals(before, registrations());
        assertEquals("active", ownerJdbc.queryForObject("SELECT status FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", UUID.fromString(agent)), String.class), "a denied revoke changed nothing");
        assertEquals(drainedBefore, DRAINED.size(), "a denied drain reached nobody");
        // the Operator may drain (no live session, so the honest answer is the 409, not a 403)
        assertCode(call("POST", "/api/dp/agents/" + agent + "/drain", operator, null), 409, "AGENT_NOT_CONNECTED");
    }

    @Test
    @Order(4)
    void anOperatorOfAnotherEnvironmentAndAnotherOrganizationSeeNothingOfTheAgentAndChangeNothing() throws Exception {
        Client http = client();
        String agent = issued.get("agentId").asText();
        int drainedBefore = DRAINED.size();
        long before = registrations();

        // I5: another organization's administrator: absent, exactly as an agent that does not exist
        Login foreign = http.login(DeclareFixtures.FOREIGN);
        assertCode(call("GET", "/api/cp/agents/" + agent, foreign, null), 404, "NOT_FOUND");
        assertCode(call("POST", "/api/cp/agents/" + agent + "/revoke", foreign, null), 404, "NOT_FOUND");
        assertCode(call("POST", "/api/dp/agents/" + agent + "/drain", foreign, null), 404, "NOT_FOUND");
        assertCode(call("GET", "/api/cp/agents/" + UUID.randomUUID(), foreign, null), 404, "NOT_FOUND");
        assertCode(call("POST", issuePath(world.envOne()), foreign, issueBody("edge-foreign")), 404, "NOT_FOUND");
        assertFalse(call("GET", "/api/cp/agents", foreign, null).body().contains(agent), "and it is not listed");
        assertEquals(drainedBefore, DRAINED.size(), "a foreign drain never reached the gateway");
        assertEquals(before, registrations());

        // an Operator bound at the OTHER environment of the same organization reaches neither the agent nor its list entry
        Login elsewhere = http.login(DeclareFixtures.OPERATOR_TWO);
        assertEquals(404, call("GET", "/api/cp/agents/" + agent, elsewhere, null).statusCode());
        assertEquals(404, call("POST", "/api/dp/agents/" + agent + "/drain", elsewhere, null).statusCode());
        assertFalse(call("GET", "/api/cp/agents", elsewhere, null).body().contains(agent));
        assertEquals(drainedBefore, DRAINED.size());
    }

    // ---- an enrolled agent: view, drain, revoke ----------------------------------------------------------------------------

    private void enrolAgent() throws Exception {
        if (enrolledAgent != null) {
            return;
        }
        Login admin = client().login(DeclareFixtures.ADMIN);
        JsonNode token = json(call("POST", issuePath(world.envOne()), admin, issueBody("edge-enrolled")));
        EnrollmentResult result = enrollments.enroll(new EnrollmentRequest("enroll-" + UUID.randomUUID(),
                token.get("enrollmentToken").asText(), AgentGatewayTestPki.csr(), "0.1.0", List.of(1), "linux", "arm64", "docker",
                new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77")));
        enrolledAgent = result.agentId();
        enrolledThumbprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(result.certificateChainDer().get(0)));
    }

    @Test
    @Order(5)
    void anEnrolledAgentIsActiveWithItsTargetThenConnectedOnceItHasASession() throws Exception {
        enrolAgent();
        Login admin = client().login(DeclareFixtures.ADMIN);
        JsonNode active = json(call("GET", "/api/cp/agents/" + enrolledAgent, admin, null));
        assertEquals("active", active.get("status").asText());
        assertNotNull(active.get("targetId"));
        assertNotNull(active.get("enrolledAt"));
        assertNull(active.get("session"), "enrolled, never connected");
        assertEquals(List.of("apply-config", "lifecycle"), texts(active.get("capabilities")));

        AgentSessionRef session = sessions.claim(enrolledThumbprint, new SessionOpening(1, "0.1.0-it", Set.of(), Instant.now()),
                new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77"));
        assertNotNull(session);
        JsonNode connected = json(call("GET", "/api/cp/agents/" + enrolledAgent, admin, null));
        assertEquals("connected", connected.at("/session/state").asText());
        assertEquals("0.1.0-it", connected.at("/session/agentVersion").asText());
        assertNotNull(connected.at("/session/lastSeenAt").textValue());
        JsonNode listed = null;
        for (JsonNode item : json(call("GET", "/api/cp/agents", admin, null)).get("items")) {
            if (item.get("agentId").asText().equals(enrolledAgent.toString())) {
                listed = item;
            }
        }
        assertNotNull(listed, "an enrolled agent is listed");
        assertEquals("connected", listed.at("/session/state").asText());
    }

    @Test
    @Order(6)
    void drainAnswers202WhenTheGatewayHoldsTheSessionAnd409WhenItDoesNotAndAnOperatorMayDoIt() throws Exception {
        enrolAgent();
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        DRAINED.clear();
        REASONS.clear();

        LIVE.add(enrolledAgent);
        HttpResponse<String> accepted = call("POST", "/api/dp/agents/" + enrolledAgent + "/drain", operator, null);
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode body = json(accepted);
        assertEquals(enrolledAgent.toString(), body.get("agentId").asText());
        assertEquals(Set.of("agentId", "requestedAt"), fieldNames(body));
        assertNotNull(Instant.parse(body.get("requestedAt").asText()));
        assertEquals(List.of(enrolledAgent), DRAINED);
        assertEquals(List.of("operator-drain"), REASONS, "the reason says an operator asked, not a planned restart");

        LIVE.remove(enrolledAgent);
        assertCode(call("POST", "/api/dp/agents/" + enrolledAgent + "/drain", operator, null), 409, "AGENT_NOT_CONNECTED");
    }

    @Test
    @Order(7)
    void revokeRaisesTheEpochAuditsThePrincipalIsIdempotentAndTheSessionEndsOnTheNextReconciliation() throws Exception {
        enrolAgent();
        Login admin = client().login(DeclareFixtures.ADMIN);
        AgentSessionRef live = sessions.claim(enrolledThumbprint, new SessionOpening(1, "0.1.0-it", Set.of(), Instant.now()),
                new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77"));
        long epochBefore = ownerJdbc.queryForObject("SELECT agent_revocation_epoch FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", enrolledAgent), Long.class);
        // before the revocation the session is in good standing: the gateway's reconciliation changes nothing
        assertEquals(IdentityReconciliation.Status.UNCHANGED, reconcile(live).status());

        HttpResponse<String> revoked = call("POST", "/api/cp/agents/" + enrolledAgent + "/revoke", admin, null);
        assertEquals(200, revoked.statusCode(), revoked.body());
        JsonNode body = json(revoked);
        assertEquals("revoked", body.get("status").asText());
        assertNotNull(body.get("revokedAt"));
        assertEquals(epochBefore + 1, ownerJdbc.queryForObject("SELECT agent_revocation_epoch FROM sm_control.agent_registration"
                + " WHERE agent_id = :a", Map.of("a", enrolledAgent), Long.class), "the epoch rose");
        List<Map<String, Object>> audit = ownerJdbc.queryForList("SELECT actor_principal_id, actor_agent_id FROM sm_control.change_audit"
                + " WHERE table_name = 'agent_registration' AND audit_operation = 'AGENT_REVOKED' AND row_key::text LIKE :a",
                Map.of("a", "%" + enrolledAgent + "%"));
        assertEquals(1, audit.size());
        assertEquals(adminId, audit.get(0).get("actor_principal_id"));

        // the data plane's next reconciliation (the gateway runs it every revocation-check-interval) says REVOKED, which closes
        // the session (AgentGatewayServiceTest); the agent's next connection is refused
        assertEquals(IdentityReconciliation.Status.REVOKED, reconcile(live).status());
        GatewayPortException refused = org.junit.jupiter.api.Assertions.assertThrows(GatewayPortException.class,
                () -> sessions.claim(enrolledThumbprint, new SessionOpening(1, "0.1.0-it", Set.of(), Instant.now()),
                        new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77")));
        assertEquals(GatewayCode.IDENTITY_REVOKED, refused.code());

        // idempotent: 200 again, the epoch stays, no second audit row
        HttpResponse<String> again = call("POST", "/api/cp/agents/" + enrolledAgent + "/revoke", admin, null);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals("revoked", json(again).get("status").asText());
        assertEquals(json(revoked).get("revokedAt").asText(), json(again).get("revokedAt").asText(), "revoked_at never moves");
        assertEquals(epochBefore + 1, ownerJdbc.queryForObject("SELECT agent_revocation_epoch FROM sm_control.agent_registration"
                + " WHERE agent_id = :a", Map.of("a", enrolledAgent), Long.class));
        assertEquals(1, count("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'agent_registration'"
                + " AND audit_operation = 'AGENT_REVOKED' AND row_key::text LIKE :a", Map.of("a", "%" + enrolledAgent + "%")));
        // a revoked agent has nothing to drain
        assertCode(call("POST", "/api/dp/agents/" + enrolledAgent + "/drain", admin, null), 409, "AGENT_NOT_CONNECTED");
    }

    private IdentityReconciliation reconcile(AgentSessionRef session) {
        return identities.reconcile(List.of(session)).get(0);
    }

    @Test
    @Order(8)
    void revokingATokenNeverUsedReleasesItsTargetName() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        JsonNode pending = json(call("POST", issuePath(world.envOne()), admin, issueBody("edge-released")));
        assertCode(call("POST", issuePath(world.envOne()), admin, issueBody("edge-released")), 409, "CONFLICT");

        HttpResponse<String> revoked = call("POST", "/api/cp/agents/" + pending.get("agentId").asText() + "/revoke", admin, null);
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals("revoked", json(revoked).get("status").asText());
        assertNull(json(revoked).get("enrolledAt"));

        assertEquals(201, call("POST", issuePath(world.envOne()), admin, issueBody("edge-released")).statusCode(),
                "the name is free again");
        // the revoked token can no longer enrol
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> enrollments.enroll(new EnrollmentRequest(
                "enroll-" + UUID.randomUUID(), pending.get("enrollmentToken").asText(), AgentGatewayTestPki.csr(), "0.1.0",
                List.of(1), "linux", "arm64", "docker", new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77"))));
    }

    @Test
    @Order(9)
    void anAgentIdThatIsNotAUuidAndAMissingSessionCookieAreRefused() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        assertEquals(404, call("GET", "/api/cp/agents/not-a-uuid", admin, null).statusCode());
        assertEquals(401, http.anonymous("GET", "/api/cp/agents").statusCode());
        assertEquals(401, http.anonymous("POST", "/api/cp/agents/" + enrolledAgent + "/revoke").statusCode());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }
}
