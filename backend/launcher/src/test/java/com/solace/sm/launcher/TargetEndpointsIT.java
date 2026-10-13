package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.identity.CredentialHasher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code listTargets} is SERVED (plan 010 T2.7): the real {@code SolutionManagerApplication} boots against PostgreSQL
 * and answers {@code GET /api/cp/targets} over real HTTP, through the real authentication interceptor, the real
 * {@code GrantReachAuthorization} and the real data plane's connection records. A controller that only passes a
 * gateway unit test is "built", not "served".
 *
 * <p>Everything is seeded directly (there is no create-target endpoint anywhere in the contract, §5.5): two
 * organizations, three targets in the first owned at the organization and at each of two sibling environments, one of
 * them with a data-plane connection record.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetEndpointsIT {

    private static final String CONSOLE_ORIGIN = "https://console.test";
    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String CREDENTIAL = "target-it-credential-0001";

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static UUID orgOwned;
    private static UUID envOneOwned;
    private static UUID envTwoOwned;
    private static UUID foreign;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase db = TwoSchemaTestDatabase.provision(registry);
        NamedParameterJdbcTemplate jdbc = db.ownerJdbc;

        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID envOne = UUID.randomUUID();
        UUID envTwo = UUID.randomUUID();
        seedOrganization(jdbc, organization, unit, envOne, envTwo);
        UUID otherOrganization = UUID.randomUUID();
        UUID otherUnit = UUID.randomUUID();
        UUID otherEnvironment = UUID.randomUUID();
        seedOrganization(jdbc, otherOrganization, otherUnit, otherEnvironment, null);

        UUID orgScope = scope(jdbc, "organization", organization, null, null);
        UUID envOneScope = scope(jdbc, "environment", organization, unit, envOne);
        UUID envTwoScope = scope(jdbc, "environment", organization, unit, envTwo);
        UUID otherScope = scope(jdbc, "organization", otherOrganization, null, null);

        principal(jdbc, organization, "target-it-viewer", "Viewer", orgScope);
        principal(jdbc, organization, "target-it-operator", "Operator", envOneScope);
        principal(jdbc, organization, "target-it-unbound", null, null);
        principal(jdbc, otherOrganization, "target-it-foreign", "Viewer", otherScope);

        UUID creator = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) VALUES (:id, :org,"
                + " 'idp', 'target-it-creator')", new MapSqlParameterSource().addValue("id", creator)
                .addValue("org", organization));
        orgOwned = target(jdbc, organization, orgScope, "t-org", creator);
        envOneOwned = target(jdbc, organization, envOneScope, "t-env-one", creator);
        envTwoOwned = target(jdbc, organization, envTwoScope, "t-env-two", creator);
        UUID foreignCreator = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) VALUES (:id, :org,"
                + " 'idp', 'target-it-foreign-creator')", new MapSqlParameterSource().addValue("id", foreignCreator)
                .addValue("org", otherOrganization));
        foreign = target(jdbc, otherOrganization, otherScope, "t-foreign", foreignCreator);

        connection(jdbc, organization, envOneOwned, "vm-env-one.example.test", 'A');
    }

    private static void seedOrganization(NamedParameterJdbcTemplate jdbc, UUID organization, UUID unit, UUID env,
            UUID siblingEnv) {
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:o, 'fixture-' || gen_random_uuid()::text)",
                new MapSqlParameterSource().addValue("o", organization));
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (:o, :u, 'fixture-' || gen_random_uuid()::text)",
                new MapSqlParameterSource().addValue("o", organization).addValue("u", unit));
        for (UUID environment : siblingEnv == null ? new UUID[] {env} : new UUID[] {env, siblingEnv}) {
            jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                            + " VALUES (:o, :u, :e, 'fixture-' || gen_random_uuid()::text)",
                    new MapSqlParameterSource().addValue("o", organization).addValue("u", unit)
                            .addValue("e", environment));
        }
    }

    private static UUID scope(NamedParameterJdbcTemplate jdbc, String kind, UUID organization, UUID unit,
            UUID environment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id,"
                        + " environment_id) VALUES (:id, :kind, :o, :u, :e)",
                new MapSqlParameterSource().addValue("id", id).addValue("kind", kind).addValue("o", organization)
                        .addValue("u", unit).addValue("e", environment));
        return id;
    }

    /** The organization each seeded user signs in to (OR-125 (4): a login names it). */
    private static final java.util.Map<String, UUID> ORGANIZATION_OF = new java.util.concurrent.ConcurrentHashMap<>();

    private static void principal(NamedParameterJdbcTemplate jdbc, UUID organization, String username, String role,
            UUID scope) {
        ORGANIZATION_OF.put(username, organization);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash,"
                        + " must_rotate) VALUES (:id, :o, 'local', :u, :h, false)",
                new MapSqlParameterSource().addValue("id", id).addValue("o", organization).addValue("u", username)
                        .addValue("h", new CredentialHasher().hash(CREDENTIAL.toCharArray())));
        if (role != null) {
            jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) VALUES"
                            + " (:b, :p, (SELECT role_id FROM sm_control.role WHERE name = :r), :s)",
                    new MapSqlParameterSource().addValue("b", UUID.randomUUID()).addValue("p", id)
                            .addValue("r", role).addValue("s", scope));
        }
    }

    private static UUID target(NamedParameterJdbcTemplate jdbc, UUID organization, UUID scope, String name,
            UUID creator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind,"
                        + " transport, created_by, created_at) VALUES (:id, :o, :s, :n, 'docker', 'ssh-docker', :c,"
                        + " now())",
                new MapSqlParameterSource().addValue("id", id).addValue("o", organization).addValue("s", scope)
                        .addValue("n", name).addValue("c", creator));
        return id;
    }

    private static void connection(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID target, String host, char fp) {
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :g, 'ssh-docker', :h, 22, 'sm', :fp, :cred,"
                        + " '/srv/sm', 'sm-', :by, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("g", target).addValue("h", host)
                        .addValue("fp", "SHA256:" + String.valueOf(fp).repeat(43)).addValue("cred", UUID.randomUUID())
                        .addValue("by", UUID.randomUUID()));
    }

    @Test
    void listTargetsIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/cp/targets")).GET().build());
        assertEquals(401, response.statusCode(), response.body());
    }

    @Test
    void anOrganizationBoundViewerSeesEveryTargetOfItsOrganizationAndNoOtherOrganizationsAndTheListIsDegraded()
            throws Exception {
        JsonNode json = list(login("target-it-viewer"));

        Set<String> ids = ids(json);
        assertEquals(Set.of(orgOwned.toString(), envOneOwned.toString(), envTwoOwned.toString()), ids);
        assertFalse(ids.contains(foreign.toString()), "another organization's target is never listed");
        assertNull(json.get("stateVersion"));
        // t-org and t-env-two have no connection record: listed, not hidden, and the list says so
        assertEquals("degraded", json.at("/availability/state").asText(), json.toString());
        assertTrue(json.at("/availability/reason").asText().contains("no connection record"), json.toString());
        for (JsonNode item : json.get("items")) {
            boolean connected = item.get("targetId").asText().equals(envOneOwned.toString());
            assertEquals(connected, item.has("allowedConfigRoot"), item.toString());
            assertEquals("docker", item.get("kind").asText());
            assertEquals("ssh-docker", item.get("transport").asText());
            assertNotNull(item.get("ownershipScope").get("organizationId"));
            for (String forbidden : new String[] {"host", "port", "sshUser", "hostKeyFingerprint", "credentialRef"}) {
                assertFalse(item.has(forbidden), forbidden + " must never leave the data plane: " + item);
            }
        }
        JsonNode connectedItem = item(json, envOneOwned);
        assertEquals("/srv/sm", connectedItem.get("allowedConfigRoot").asText());
        assertEquals("sm-", connectedItem.get("allowedContainerPrefix").asText());
    }

    @Test
    void anEnvironmentBoundOperatorSeesOnlyTargetsOwnedAtItsEnvironment() throws Exception {
        JsonNode json = list(login("target-it-operator"));

        assertEquals(Set.of(envOneOwned.toString()), ids(json),
                "an organization-owned and a sibling-owned target are absent: downward reach only");
        assertNull(json.get("availability"), "the one listed target has its connection record");
    }

    @Test
    void aPrincipalHoldingTargetViewNowhereIsRefusedBeforeTheHandler() throws Exception {
        HttpResponse<String> response = get("/api/cp/targets", login("target-it-unbound"));
        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void anotherOrganizationsPrincipalSeesOnlyItsOwnTarget() throws Exception {
        JsonNode json = list(login("target-it-foreign"));

        assertEquals(Set.of(foreign.toString()), ids(json));
    }

    private static Set<String> ids(JsonNode json) {
        Set<String> ids = new HashSet<>();
        json.get("items").forEach(item -> ids.add(item.get("targetId").asText()));
        return ids;
    }

    private static JsonNode item(JsonNode json, UUID id) {
        for (JsonNode item : json.get("items")) {
            if (item.get("targetId").asText().equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("no item " + id + " in " + json);
    }

    private JsonNode list(String session) throws Exception {
        HttpResponse<String> response = get("/api/cp/targets", session);
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    // ---- HTTP helpers -----------------------------------------------------------------------

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path, String sessionToken) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Cookie", SESSION_COOKIE + "=" + sessionToken)
                .GET().build());
    }

    private String login(String username) throws IOException, InterruptedException {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization\":\"" + ORGANIZATION_OF.get(username)
                        + "\",\"username\":\"" + username + "\",\"credential\":\"" + CREDENTIAL + "\"}"))
                .build());
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie"));
        String value = cookie.substring((SESSION_COOKIE + "=").length());
        int semicolon = value.indexOf(';');
        return semicolon < 0 ? value : value.substring(0, semicolon);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
