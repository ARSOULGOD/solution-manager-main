package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration plan 014 steps 2 and 4, SERVED: {@code declareInstance} with {@code deploymentMode: dp-remote} and a
 * remote-server {@code connectionId}, through the real {@code SolutionManagerApplication} over HTTP against PostgreSQL —
 * the real interceptor, the real scope-evaluated {@code target.use} at the Connection's ownership scope, the real
 * {@code ConnectionService.find} over {@code sm_control.connection}, and the real data-plane reserve over
 * {@code sm_data.connection_runtime} and V039.
 *
 * <p>⚠️ The DP runtime projection is seeded here as the owner: the gateway seam that populates it from the control
 * plane's Connection is not built yet (plan 014). Every refusal asserts the row counts on BOTH planes did not move.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectionBackedDeclareIT {

    private static TwoSchemaTestDatabase db;
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID envOneConnection;
    private static UUID envTwoConnection;
    private static UUID unprojectedConnection;
    private static UUID inactiveConnection;
    private static UUID brokerConnection;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        db = provisioned.db();
        ownerJdbc = db.ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", provisioned.properties().get("sm.control.runtime.password"),
                world.organization(), "cache", 10, DeclareFixtures.ADMIN);

        envOneConnection = connection(world.envOne(), "remote-one", "remote-server", "active");
        project(envOneConnection, 'D');
        envTwoConnection = connection(world.envTwo(), "remote-two", "remote-server", "active");
        project(envTwoConnection, 'E');
        unprojectedConnection = connection(world.envOne(), "remote-unprojected", "remote-server", "active");
        inactiveConnection = connection(world.envOne(), "remote-inactive", "remote-server", "inactive");
        project(inactiveConnection, 'F');
        brokerConnection = connection(world.envOne(), "broker", "solace-broker", "active");
        project(brokerConnection, 'G');
    }

    /** A plan 013 Connection owned at the environment's scope (the control plane's row; no secret value, I6). */
    private static UUID connection(UUID environment, String name, String type, String status) {
        UUID id = UUID.randomUUID();
        UUID admin = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.ADMIN), UUID.class);
        UUID scope = ownerJdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'environment'"
                + " AND environment_id = :e", Map.of("e", environment), UUID.class);
        ownerJdbc.update("INSERT INTO sm_control.connection (connection_id, organization_id, ownership_scope_id, name,"
                        + " connection_type, descriptor_version, attributes, secret_status, status, created_by, created_at,"
                        + " updated_by, updated_at) VALUES (:id, :o, :s, :n, :type, 1, '{\"host\":\"vm.example.test\"}'::jsonb,"
                        + " '{}'::jsonb, :status, :a, now(), :a, now())",
                new MapSqlParameterSource().addValue("id", id).addValue("o", world.organization()).addValue("s", scope)
                        .addValue("n", name).addValue("type", type).addValue("status", status).addValue("a", admin));
        return id;
    }

    /** What the unbuilt gateway seam would write: the DP-owned runtime projection of the Connection. */
    private static void project(UUID connection, char fp) {
        ownerJdbc.update("INSERT INTO sm_data.connection_runtime (tenant_id, connection_id, connection_type, host, port,"
                        + " ssh_user, host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_at) VALUES (:t, :c, 'remote-server', 'vm.example.test', 22, 'sm', :fp, :cred,"
                        + " '/srv/sm', 'sm-', now())",
                new MapSqlParameterSource().addValue("t", world.organization()).addValue("c", connection)
                        .addValue("fp", "SHA256:" + String.valueOf(fp).repeat(43)).addValue("cred", UUID.randomUUID()));
    }

    private Client client() {
        return new Client(port);
    }

    private String declarePath(UUID environment) {
        return "/api/dp/environments/" + environment + "/instances";
    }

    private ObjectNode body(UUID connection, String name, String container, int healthPort) {
        ObjectNode body = DeclareFixtures.declareBody(solutionId, UUID.randomUUID(), name, container, healthPort);
        body.remove("targetId");
        body.put("connectionId", connection.toString());
        body.put("deploymentMode", "dp-remote");
        return body;
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void thePackageIsLoadedSoThereIsACatalogEntryToDeclareAgainst() throws Exception {
        Client http = client();
        ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());

        HttpResponse<String> response = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);

        assertEquals(201, response.statusCode(), response.body());
        solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(response.body()).get("solutionId").asText());
    }

    @Test
    @Order(2)
    void anOperatorDeclaresADpRemoteInstanceOnARemoteServerConnectionAndBothPlanesHoldIt() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post(declarePath(world.envOne()), operator,
                body(envOneConnection, "conn-cache", "sm-conn", 9201));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode item = DeclareFixtures.MAPPER.readTree(response.body());
        UUID instance = UUID.fromString(item.get("instanceId").asText());
        assertEquals(envOneConnection.toString(), item.at("/placements/0/connectionId").asText());
        assertFalse(item.at("/placements/0").has("targetId"), "omitted, never null: " + item);
        assertFalse(response.body().contains("vm.example.test"), "no host reaches the browser");

        Map<String, Object> dp = ownerJdbc.queryForMap("SELECT i.lifecycle, i.deployment_mode, p.target_id,"
                + " p.connection_id FROM sm_data.instance_state i JOIN sm_data.placement p ON p.tenant_id = i.tenant_id"
                + " AND p.instance_id = i.instance_id WHERE i.tenant_id = :t AND i.instance_id = :i",
                Map.of("t", world.organization(), "i", instance));
        assertEquals("active", dp.get("lifecycle"));
        assertEquals("dp-remote", dp.get("deployment_mode"));
        assertNull(dp.get("target_id"));
        assertEquals(envOneConnection, dp.get("connection_id"));
        assertEquals(1L, count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o AND instance_id = :i",
                Map.of("o", world.organization(), "i", instance)), "the control plane registered the node");

        HttpResponse<String> fetched = http.get("/api/dp/instances/" + instance, operator);
        assertEquals(200, fetched.statusCode(), fetched.body());
        assertEquals(envOneConnection.toString(),
                DeclareFixtures.MAPPER.readTree(fetched.body()).at("/placements/0/connectionId").asText());
    }

    @Test
    @Order(3)
    void aConnectionOwnedByASiblingEnvironmentIsRefusedTargetUseAs404AndNothingIsWritten() throws Exception {
        Client http = client();
        Counts before = counts();

        HttpResponse<String> response = http.post(declarePath(world.envOne()), http.login(DeclareFixtures.OPERATOR_ONE),
                body(envTwoConnection, "cross-conn", "sm-cross", 9202));

        assertEquals(404, response.statusCode(), response.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(4)
    void anUnusableConnectionIs422NamingConnectionIdAndNothingIsWritten() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        for (UUID unusable : List.of(unprojectedConnection, inactiveConnection, brokerConnection)) {
            HttpResponse<String> response = http.post(declarePath(world.envOne()), one,
                    body(unusable, "unusable", "sm-unusable", 9203));

            assertEquals(422, response.statusCode(), response.body());
            assertTrue(DeclareFixtures.MAPPER.readTree(response.body()).get("detail").asText().contains("connectionId"),
                    response.body());
        }
        assertEquals(before, counts());
    }

    @Test
    @Order(5)
    void reservedModesAndAnAmbiguousBindingAre422AndNothingIsWritten() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();
        ObjectNode hostLocal = body(envOneConnection, "host-local", "sm-hl", 9204);
        hostLocal.put("deploymentMode", "host-local");
        ObjectNode federated = body(envOneConnection, "federated", "sm-fed", 9204);
        federated.put("deploymentMode", "federated");
        ObjectNode both = body(envOneConnection, "both", "sm-both", 9204);
        both.put("targetId", world.targetOne().toString());
        ObjectNode neither = body(envOneConnection, "neither", "sm-neither", 9204);
        neither.remove("connectionId");

        assertEquals("deploymentMode", refusedField(http.post(declarePath(world.envOne()), one, hostLocal)));
        assertEquals("deploymentMode", refusedField(http.post(declarePath(world.envOne()), one, federated)));
        assertEquals("connectionId", refusedField(http.post(declarePath(world.envOne()), one, both)));
        assertEquals("connectionId", refusedField(http.post(declarePath(world.envOne()), one, neither)));
        assertEquals(before, counts());
    }

    @Test
    @Order(6)
    void aPlacementOutsideTheConnectionsEnvelopeIs422AndACollisionOnTheConnectionIs409() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();
        ObjectNode outside = body(envOneConnection, "outside", "sm-outside", 9205);
        ((ObjectNode) outside.get("placement")).put("configDirectory", "/home/ec2-user/.ssh");

        HttpResponse<String> envelope = http.post(declarePath(world.envOne()), one, outside);
        HttpResponse<String> collision = http.post(declarePath(world.envOne()), one,
                body(envOneConnection, "same-container", "sm-conn", 9206));

        assertEquals(422, envelope.statusCode(), envelope.body());
        assertTrue(envelope.body().contains("placement.configDirectory"), envelope.body());
        assertFalse(envelope.body().contains(".ssh"), "the value is never echoed");
        assertEquals(409, collision.statusCode(), collision.body());
        assertEquals(before, counts());
    }

    /**
     * Plan 015: the gateway projection seam, SERVED — a Connection created through the real
     * {@code POST /api/cp/connections} endpoint (not the {@link #project} hand-insert every other test in this class
     * uses) must be immediately usable by {@code declareInstance}, because {@code ConnectionOrchestrator.create} now
     * upserts {@code sm_data.connection_runtime} itself.
     */
    @Test
    @Order(8)
    void aConnectionCreatedThroughTheRealEndpointCanDeclareWithNoHandInsertedProjection() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        String name = "ui-created-" + UUID.randomUUID();
        com.fasterxml.jackson.databind.node.ObjectNode attributes = DeclareFixtures.MAPPER.createObjectNode();
        // UAT 10da18d7 removed allowedConfigRoot, allowedContainerPrefix and hostKeyFingerprint from remote-server.json
        // (the runtime projection is deferred), so sending them is a field outside the descriptor (422).
        attributes.put("host", "vm-ui.example.test").put("username", "deployer").put("authMethod", "basic")
                .put("password", "s3cret");
        com.fasterxml.jackson.databind.node.ObjectNode createBody = DeclareFixtures.MAPPER.createObjectNode();
        createBody.put("name", name).put("connectionType", "remote-server");
        createBody.set("ownershipScope",
                DeclareFixtures.MAPPER.createObjectNode().put("environmentId", world.envOne().toString()));
        createBody.set("attributes", attributes);
        createBody.put("testBeforeSave", false);

        HttpResponse<String> created = http.post("/api/cp/connections", admin, createBody);
        assertEquals(201, created.statusCode(), created.body());
        UUID connectionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(created.body()).get("connectionId").asText());

        HttpResponse<String> declared = http.post(declarePath(world.envOne()), http.login(DeclareFixtures.OPERATOR_ONE),
                body(connectionId, "ui-created-instance", "sm-ui", 9208));

        assertEquals(201, declared.statusCode(), declared.body());
    }

    @Test
    @Order(7)
    void theLegacyTargetDeclareIsUnchangedAndReadsDpRemote() throws Exception {
        Client http = client();
        HttpResponse<String> response = http.post(declarePath(world.envOne()), http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "legacy-cache", "sm-legacy", 9207));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode item = DeclareFixtures.MAPPER.readTree(response.body());
        assertEquals(world.targetOne().toString(), item.at("/placements/0/targetId").asText());
        assertFalse(item.at("/placements/0").has("connectionId"));
        assertEquals("dp-remote", ownerJdbc.queryForObject("SELECT deployment_mode FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", Map.of("t", world.organization(),
                "i", UUID.fromString(item.get("instanceId").asText())), String.class));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String refusedField(HttpResponse<String> response) throws Exception {
        assertEquals(422, response.statusCode(), response.body());
        String detail = DeclareFixtures.MAPPER.readTree(response.body()).get("detail").asText();
        return detail.contains("deploymentMode") ? "deploymentMode" : detail.contains("connectionId") ? "connectionId"
                : detail;
    }

    private record Counts(long dpInstances, long dpPlacements, long cpInstances) {
    }

    private Counts counts() {
        Map<String, Object> t = Map.of("t", world.organization());
        return new Counts(
                count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", t),
                count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", t),
                count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :t", t));
    }

    private static long count(String sql, Map<String, ?> params) {
        Long n = ownerJdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }
}
