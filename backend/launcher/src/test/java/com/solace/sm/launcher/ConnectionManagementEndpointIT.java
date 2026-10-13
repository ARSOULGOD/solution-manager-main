package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.connection.ConnectionCredentialService;
import com.solace.sm.dp.api.connection.ConnectionTestOutcome;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 013 Task 7: {@code createConnection}, {@code updateConnection}, {@code deactivateConnection} and
 * {@code deleteConnection} SERVED end to end — the real {@code SolutionManagerApplication} over real HTTP against
 * PostgreSQL, through the real interceptors, the real grants, the real {@link com.solace.sm.gateway.http
 * .ConnectionOrchestrator}, and the real control-plane row ({@code sm_control.connection}, V021). Modeled on
 * {@code TargetManagementEndpointIT}'s harness (same {@link DeclareFixtures}/{@link TwoSchemaTestDatabase}).
 *
 * <p><b>What is doubled and what is real.</b> {@link ConnectionCredentialService} is replaced with {@link Doubles
 * #credentials}, exactly as {@code TargetManagementEndpointIT} replaces {@code TargetConnectionTester} — but
 * unlike that double, this one does not fabricate results by default: it delegates {@code sealCredential} to a
 * trivial in-memory map (so a rotated secret gets a fresh reference without needing the real encrypted store) and
 * delegates the two test methods to the REAL production testers ({@link SolaceBrokerConnectionTester},
 * {@link RemoteServerConnectionTester}), run against a {@link PermissiveTargetAddressPolicy} and this in-memory
 * secret resolver. Only when a test installs {@link #SCRIPTED} does it short-circuit to a scripted outcome. This
 * means the "forced test failure" scenarios below genuinely attempt a TCP connection to an address nothing is
 * listening on and get the real {@code 503}-vs-{@code 422} branch the production code computes — not a double
 * standing in for that branch.
 *
 * <p><b>Known gap (task-7-addendum.md).</b> There is no live Solace broker or SSH daemon in this environment, so a
 * {@code testBeforeSave: true} SUCCESS can only be proven by installing {@link #SCRIPTED} (this class's own
 * override), never by a real broker/sshd accepting a real credential. The specific "broker/sshd replies with a
 * credential rejection" code path (as opposed to "nothing is listening") is for the same reason not exercised for
 * real here either — consistent with, not a new instance of, Task 3's parked finding.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConnectionManagementEndpointIT.Doubles.class)
class ConnectionManagementEndpointIT {

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    /** When non-null, {@link Doubles#credentials} returns this instead of running a real test. Reset per test. */
    static final AtomicReference<ConnectionTestOutcome> SCRIPTED = new AtomicReference<>();
    static final Map<UUID, char[]> SEALED = new ConcurrentHashMap<>();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.gateway.connection-test.permits-per-minute", () -> 1000);
        world = DeclareFixtures.seed(ownerJdbc);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {

        @Bean
        @Primary
        ConnectionCredentialService credentials() {
            SecretResolver resolver = (tenantId, secretId, expected) -> {
                char[] value = SEALED.get(secretId);
                if (value == null) {
                    throw new IllegalStateException("fixture secret not found: " + secretId);
                }
                return SecretMaterial.adopt(new String(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            };
            SolaceBrokerConnectionTester broker = new SolaceBrokerConnectionTester(resolver,
                    new PermissiveTargetAddressPolicy(), Clock.systemUTC());
            RemoteServerConnectionTester remote = new RemoteServerConnectionTester(resolver,
                    new PermissiveTargetAddressPolicy(), Clock.systemUTC());
            return new ConnectionCredentialService() {
                @Override
                public UUID sealCredential(UUID tenantId, char[] value, UUID actor) {
                    UUID id = UUID.randomUUID();
                    SEALED.put(id, value.clone());
                    return id;
                }

                @Override
                public ConnectionTestOutcome testSolaceBroker(UUID tenantId, String host, int port, String vpn,
                        String username, UUID passwordSecretRef) {
                    ConnectionTestOutcome scripted = SCRIPTED.get();
                    if (scripted != null) {
                        return scripted;
                    }
                    var result = broker.test(new SolaceBrokerConnectionTester.Binding(new TenantId(tenantId), host,
                            port, vpn, username, passwordSecretRef));
                    return new ConnectionTestOutcome(result.success(), result.rejectionReason(), result.reachable(),
                            result.checkedAt());
                }

                @Override
                public ConnectionTestOutcome testRemoteServer(UUID tenantId, String host, int port, String username,
                        String authMethod, UUID passwordSecretRef, UUID privateKeySecretRef,
                        UUID passphraseSecretRef) {
                    ConnectionTestOutcome scripted = SCRIPTED.get();
                    if (scripted != null) {
                        return scripted;
                    }
                    RemoteServerConnectionTester.AuthMethod method = "basic".equals(authMethod)
                            ? RemoteServerConnectionTester.AuthMethod.BASIC
                            : RemoteServerConnectionTester.AuthMethod.PRIVATE_KEY;
                    var result = remote.test(new RemoteServerConnectionTester.Binding(new TenantId(tenantId), host,
                            port, username, method, passwordSecretRef, privateKeySecretRef, passphraseSecretRef));
                    return new ConnectionTestOutcome(result.success(), result.rejectionReason(), result.reachable(),
                            result.checkedAt());
                }
            };
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

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
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    /** A TCP port on loopback that was just closed — genuinely unreachable, not merely a reserved/blocked range. */
    private static int unreachablePort() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        return port;
    }

    private ObjectNode brokerAttributes(String host, int port) {
        ObjectNode attrs = DeclareFixtures.MAPPER.createObjectNode();
        attrs.put("host", host).put("port", port).put("vpn", "default").put("username", "svc");
        return attrs;
    }

    private ObjectNode createBody(String name, String connectionType, ObjectNode attributes, boolean testBeforeSave) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("name", name).put("connectionType", connectionType);
        body.set("ownershipScope", DeclareFixtures.MAPPER.createObjectNode().put("environmentId",
                world.envOne().toString()));
        body.set("attributes", attributes);
        body.put("testBeforeSave", testBeforeSave);
        return body;
    }

    private long connectionCount(UUID connectionId) {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.connection WHERE connection_id = :c",
                Map.of("c", connectionId), Long.class);
        return n == null ? 0 : n;
    }

    private long connectionCountByName(String name) {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.connection WHERE organization_id = :o"
                + " AND name = :n", Map.of("o", world.organization(), "n", name), Long.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------------------------------------- createConnection

    @Test
    void createsASolaceBrokerWithNoTestAndTheRowIsWritten() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "broker-no-test-" + UUID.randomUUID();

        HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                createBody(name, "solace-broker", brokerAttributes("broker.example.test", 55555), false));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode created = json(response);
        assertEquals(name, created.get("name").asText());
        assertEquals(1, connectionCountByName(name));
        assertEquals(0, created.get("usedByCount").asInt());
    }

    @Test
    void createsARemoteServerWithPrivateKeyAuthAndTheRowIsWritten() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "sftp-pk-" + UUID.randomUUID();
        ObjectNode attrs = DeclareFixtures.MAPPER.createObjectNode();
        attrs.put("host", "sftp.example.test").put("port", 22).put("username", "ingest")
                .put("authMethod", "private-key").put("privateKey", "-----BEGIN KEY-----fixture-----END KEY-----");

        HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                createBody(name, "remote-server", attrs, false));

        assertEquals(201, response.statusCode(), response.body());
        assertEquals(Map.of("set", true), DeclareFixtures.MAPPER.convertValue(
                json(response).get("attributes").get("privateKey"), Map.class));
        assertEquals(1, connectionCountByName(name));
    }

    @Test
    void aForcedTestFailureViaAnUnreachableHostIs503AndWritesNothingForSolaceBroker() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);     // real production tester, real (failed) TCP attempt
        String name = "broker-unreachable-" + UUID.randomUUID();
        int closedPort = unreachablePort();

        HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                createBody(name, "solace-broker", brokerAttributes("127.0.0.1", closedPort), true));

        assertEquals(503, response.statusCode(), response.body());
        assertEquals("SERVICE_UNAVAILABLE", json(response).get("code").asText());
        assertEquals(0, connectionCountByName(name), "nothing written on a 503");
    }

    @Test
    void aForcedTestFailureViaAnUnreachableHostIs503AndWritesNothingForRemoteServer() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "sftp-unreachable-" + UUID.randomUUID();
        int closedPort = unreachablePort();
        ObjectNode attrs = DeclareFixtures.MAPPER.createObjectNode();
        attrs.put("host", "127.0.0.1").put("port", closedPort).put("username", "ingest")
                .put("authMethod", "basic").put("password", "whatever");

        HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                createBody(name, "remote-server", attrs, true));

        assertEquals(503, response.statusCode(), response.body());
        assertEquals("SERVICE_UNAVAILABLE", json(response).get("code").asText());
        assertEquals(0, connectionCountByName(name), "nothing written on a 503");
    }

    @Test
    void testBeforeSaveTrueSucceedsAndWritesTheRowWhenTheProbeIsScriptedToSucceed() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(new ConnectionTestOutcome(true, null, true, java.time.Instant.now()));
        String name = "broker-test-success-" + UUID.randomUUID();
        try {
            HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                    createBody(name, "solace-broker", brokerAttributes("broker.example.test", 55555), true));

            assertEquals(201, response.statusCode(), response.body());
            assertEquals(1, connectionCountByName(name));
        } finally {
            SCRIPTED.set(null);
        }
    }

    @Test
    void aScriptedCredentialRejectionIs422AndWritesNothing() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(new ConnectionTestOutcome(false, "Access denied by broker ACL.", true, java.time.Instant.now()));
        String name = "broker-rejected-" + UUID.randomUUID();
        try {
            HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                    createBody(name, "solace-broker", brokerAttributes("broker.example.test", 55555), true));

            assertEquals(422, response.statusCode(), response.body());
            assertEquals("CONNECTION_TEST_FAILED", json(response).get("code").asText());
            assertEquals("Access denied by broker ACL.", json(response).get("detail").asText());
            assertEquals(0, connectionCountByName(name), "nothing written on a 422");
        } finally {
            SCRIPTED.set(null);
        }
    }

    // ---------------------------------------------------------------------------------------------- updateConnection

    private UUID createdWithPassword(Login admin, String name, String password) throws Exception {
        ObjectNode attrs = brokerAttributes("broker.example.test", 55555);
        attrs.put("password", password);
        HttpResponse<String> response = call("POST", "/api/cp/connections", admin,
                createBody(name, "solace-broker", attrs, false));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("connectionId").asText());
    }

    @Test
    void updatingOnlyTheNameLeavesTheSecretAttributeUntouched() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "broker-rotate-base-" + UUID.randomUUID();
        UUID connectionId = createdWithPassword(admin, name, "s3cr3t-one");
        String secretRefBefore = ownerJdbc.queryForObject("SELECT attributes ->> 'password' FROM sm_control.connection"
                + " WHERE connection_id = :c", Map.of("c", connectionId), String.class);

        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("name", name + "-renamed");
        body.put("testBeforeSave", false);
        HttpResponse<String> response = call("PATCH", "/api/cp/connections/" + connectionId, admin, body);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(name + "-renamed", json(response).get("name").asText());
        String secretRefAfter = ownerJdbc.queryForObject("SELECT attributes ->> 'password' FROM sm_control.connection"
                + " WHERE connection_id = :c", Map.of("c", connectionId), String.class);
        assertEquals(secretRefBefore, secretRefAfter, "the password's reference is unchanged by a name-only update");
    }

    @Test
    void updatingWithANewPasswordRotatesTheSecretReference() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "broker-rotate-" + UUID.randomUUID();
        UUID connectionId = createdWithPassword(admin, name, "s3cr3t-one");
        String secretRefBefore = ownerJdbc.queryForObject("SELECT attributes ->> 'password' FROM sm_control.connection"
                + " WHERE connection_id = :c", Map.of("c", connectionId), String.class);

        ObjectNode attrs = brokerAttributes("broker.example.test", 55555);
        attrs.put("password", "s3cr3t-two");
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("name", name);
        body.set("attributes", attrs);
        body.put("testBeforeSave", false);
        HttpResponse<String> response = call("PATCH", "/api/cp/connections/" + connectionId, admin, body);

        assertEquals(200, response.statusCode(), response.body());
        String secretRefAfter = ownerJdbc.queryForObject("SELECT attributes ->> 'password' FROM sm_control.connection"
                + " WHERE connection_id = :c", Map.of("c", connectionId), String.class);
        assertNotEquals(secretRefBefore, secretRefAfter, "a resubmitted password rotates the secret reference");
        assertEquals("s3cr3t-two", new String(SEALED.get(UUID.fromString(secretRefAfter))));
    }

    @Test
    void updateWithTestBeforeSaveTrueForcedUnreachableIs503AndLeavesTheRowUnchanged() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "broker-update-unreachable-" + UUID.randomUUID();
        UUID connectionId = createdWithPassword(admin, name, "s3cr3t-one");
        int closedPort = unreachablePort();

        ObjectNode attrs = brokerAttributes("127.0.0.1", closedPort);
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("testBeforeSave", true);
        body.set("attributes", attrs);
        HttpResponse<String> response = call("PATCH", "/api/cp/connections/" + connectionId, admin, body);

        assertEquals(503, response.statusCode(), response.body());
        Integer port = ownerJdbc.queryForObject("SELECT (attributes ->> 'port')::int FROM sm_control.connection"
                + " WHERE connection_id = :c", Map.of("c", connectionId), Integer.class);
        assertEquals(55555, port, "the update never applied: the row still has its original port");
    }

    // ---------------------------------------------------------------------------------------------- delete / deactivate

    @Test
    void deleteIsBlockedWhileUsedByCountIsPositiveAndSucceedsOnceItIsZero() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "broker-delete-" + UUID.randomUUID();
        UUID connectionId = createdWithPassword(admin, name, "s3cr3t");
        ownerJdbc.update("UPDATE sm_control.connection SET used_by_count = 2 WHERE connection_id = :c",
                new MapSqlParameterSource("c", connectionId));

        HttpResponse<String> blocked = call("DELETE", "/api/cp/connections/" + connectionId, admin, null);
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertEquals(1, connectionCount(connectionId), "the blocked delete left the row in place");

        ownerJdbc.update("UPDATE sm_control.connection SET used_by_count = 0 WHERE connection_id = :c",
                new MapSqlParameterSource("c", connectionId));
        HttpResponse<String> allowed = call("DELETE", "/api/cp/connections/" + connectionId, admin, null);

        assertEquals(204, allowed.statusCode(), allowed.body());
        assertEquals(0, connectionCount(connectionId));
    }

    @Test
    void deactivateFlipsActiveFalseAndTheRowSurvives() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCRIPTED.set(null);
        String name = "broker-deactivate-" + UUID.randomUUID();
        UUID connectionId = createdWithPassword(admin, name, "s3cr3t");

        HttpResponse<String> response = call("POST", "/api/cp/connections/" + connectionId + "/deactivate", admin,
                null);

        assertEquals(200, response.statusCode(), response.body());
        assertFalse(json(response).get("active").asBoolean());
        assertEquals(1, connectionCount(connectionId));
        String status = ownerJdbc.queryForObject("SELECT status FROM sm_control.connection WHERE connection_id = :c",
                Map.of("c", connectionId), String.class);
        assertEquals("inactive", status);
    }
}
