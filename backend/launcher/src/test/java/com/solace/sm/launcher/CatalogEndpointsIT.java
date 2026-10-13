package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.EntitlementMain;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Plan 010 T3.2: {@code loadPackage} and {@code listSolutions} served by the production launcher - the real
 * {@code SolutionManagerApplication}, composed only through {@link SolutionManagerConfiguration}'s imports, over
 * real HTTP against PostgreSQL. Nothing here is a fake: the gates, the entitlement, the audit trigger running as
 * {@code sm_cp_runtime}, the interceptor and the reach pre-check are all the production ones.
 *
 * <p>A SEPARATE Spring context and database from every other launcher IT, so its assertions about which catalog
 * rows exist are not at the mercy of another class's flow.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogEndpointsIT {

    private static final String CONSOLE_ORIGIN = "https://console.test";
    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final UUID BOOTSTRAP_ORGANIZATION = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final String LOADER = "catalog-it-loader";
    private static final String ENV_OPERATOR = "catalog-it-env-operator";
    private static final String UNBOUND = "catalog-it-unbound";
    private static final String CREDENTIAL = "catalog-it-credential-0001";

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TwoSchemaTestDatabase db;
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static String cpRuntimePassword;
    private static UUID otherOrganization;
    private static UUID otherEntryId;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        db = provisioned.db();
        ownerJdbc = db.ownerJdbc;
        cpRuntimePassword = provisioned.properties().get("sm.control.runtime.password");
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        // OR-148: this class walks the refusal and then the operator command, so it runs with the first-load grant
        // switched off; the default (a first load entitles) is proved in cp-core's CatalogServiceIT.
        registry.add("sm.cp.entitlement.on-first-load.enabled", () -> false);
        seed();
    }

    /**
     * Three principals of the bootstrap organization: {@code loader} (Administrator at the ORGANIZATION),
     * {@code env-operator} (Operator at one ENVIRONMENT: holds {@code solution.view}, not {@code package.load}) and
     * {@code unbound} (no grant at all) - plus a second organization whose catalog the first must never see.
     */
    private static void seed() {
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name)"
                + " VALUES (:o, :u, 'fixture-' || gen_random_uuid()::text)", new MapSqlParameterSource().addValue("o", BOOTSTRAP_ORGANIZATION).addValue("u", unit));
        ownerJdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                + " VALUES (:o, :u, :e, 'fixture-' || gen_random_uuid()::text)", new MapSqlParameterSource().addValue("o", BOOTSTRAP_ORGANIZATION)
                .addValue("u", unit).addValue("e", environment));

        UUID loader = principal(BOOTSTRAP_ORGANIZATION, LOADER);
        UUID envOperator = principal(BOOTSTRAP_ORGANIZATION, ENV_OPERATOR);
        principal(BOOTSTRAP_ORGANIZATION, UNBOUND);

        UUID organizationScope = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id)"
                + " VALUES (:s, 'organization', :o)", new MapSqlParameterSource().addValue("s", organizationScope)
                .addValue("o", BOOTSTRAP_ORGANIZATION));
        bind(loader, "Administrator", organizationScope);

        UUID environmentScope = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id,"
                + " environment_id) VALUES (:s, 'environment', :o, :u, :e)", new MapSqlParameterSource()
                .addValue("s", environmentScope).addValue("o", BOOTSTRAP_ORGANIZATION).addValue("u", unit)
                .addValue("e", environment));
        bind(envOperator, "Operator", environmentScope);

        // Another organization with a catalog entry the first must never see, whatever its caller holds.
        otherOrganization = UUID.randomUUID();
        otherEntryId = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:o, 'fixture-' || gen_random_uuid()::text)",
                Map.of("o", otherOrganization));
        UUID otherPrincipal = principal(otherOrganization, "catalog-it-other");
        ownerJdbc.update("INSERT INTO sm_control.catalog_solution (organization_id, catalog_solution_id, solution,"
                + " created_at) VALUES (:o, :id, 'rival-solution', now())", new MapSqlParameterSource()
                .addValue("o", otherOrganization).addValue("id", otherEntryId));
        ownerJdbc.update("INSERT INTO sm_control.solution_package (organization_id, catalog_solution_id,"
                + " package_version, applies_to, digest, signature_status, document, loaded_by, loaded_at)"
                + " VALUES (:o, :id, '[1]', '>=1', :digest, 'unsigned', '{}'::jsonb, :by, now())",
                new MapSqlParameterSource().addValue("o", otherOrganization).addValue("id", otherEntryId)
                        .addValue("digest", "sha256:" + "c".repeat(64)).addValue("by", otherPrincipal));
    }

    private static UUID principal(UUID organization, String username) {
        UUID id = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash,"
                + " must_rotate) VALUES (:id, :o, 'local', :u, :h, false)", new MapSqlParameterSource()
                .addValue("id", id).addValue("o", organization).addValue("u", username)
                .addValue("h", new CredentialHasher().hash(CREDENTIAL.toCharArray())));
        return id;
    }

    private static void bind(UUID principal, String role, UUID scope) {
        ownerJdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                + " VALUES (:b, :p, (SELECT role_id FROM sm_control.role WHERE name = :role), :s)",
                new MapSqlParameterSource().addValue("b", UUID.randomUUID()).addValue("p", principal)
                        .addValue("role", role).addValue("s", scope));
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void anEmptyCatalogListsNothingAndNeverShowsAnotherOrganizationsEntries() throws Exception {
        Login loader = login(LOADER);

        HttpResponse<String> response = get("/api/cp/solutions", loader);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals(0, body.get("items").size(), "the other organization's 'rival-solution' is not ours");
        assertFalse(body.has("stateVersion"));
    }

    @Test
    @Order(2)
    void aPackageForASolutionTheOrganizationIsNotEntitledToIs409EntitlementRefusedAndWritesNothing()
            throws Exception {
        Login loader = login(LOADER);

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(cache()));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("ENTITLEMENT_REFUSED", MAPPER.readTree(response.body()).get("code").asText());
        assertEquals(0L, rows("solution_package", BOOTSTRAP_ORGANIZATION));
        assertEquals(0L, rows("catalog_solution", BOOTSTRAP_ORGANIZATION));
    }

    @Test
    @Order(3)
    void thePackageLoadsOnceTheOperatorCommandHasEntitledTheSolution() throws Exception {
        // OR-48: the only write path. Runs as sm_cp_runtime against the live database, as an operator would.
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", cpRuntimePassword, BOOTSTRAP_ORGANIZATION, "cache", 3, LOADER);
        Login loader = login(LOADER);

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(cache()));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertNotNull(UUID.fromString(body.get("solutionId").asText()));
        assertEquals(1, body.at("/packageVersion/0").asInt());
        assertEquals("unsigned", body.get("signature").asText(), "OR-40: the response says so");
        assertTrue(body.get("stateVersion").asLong() > 0);
        assertEquals(1L, rows("solution_package", BOOTSTRAP_ORGANIZATION));
        assertEquals("unsigned", ownerJdbc.queryForObject("SELECT signature_status FROM sm_control.solution_package"
                + " WHERE organization_id = :o", Map.of("o", BOOTSTRAP_ORGANIZATION), String.class));
    }

    @Test
    @Order(4)
    void theLoadIsAuditedAsPackageLoadedByTheLoaderAndJoinedToItsResponse() throws Exception {
        Login loader = login(LOADER);
        ObjectNode second = cacheVersion(2, ">=2.5 <3.0");

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(second));

        assertEquals(201, response.statusCode(), response.body());
        String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        List<Map<String, Object>> audit = ownerJdbc.queryForList("SELECT table_name, audit_operation,"
                + " actor_principal_id, db_role FROM sm_control.change_audit WHERE correlation_id = :c"
                + " ORDER BY change_audit_id", Map.of("c", UUID.fromString(correlation)));
        assertEquals(List.of("solution_package"), audit.stream().map(r -> r.get("table_name")).toList(),
                "the entry already existed, so only the package row is new");
        assertEquals("PACKAGE_LOADED", audit.get(0).get("audit_operation"));
        assertEquals("sm_cp_runtime", audit.get(0).get("db_role"));
        assertEquals(ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                Map.of("u", LOADER), UUID.class), audit.get(0).get("actor_principal_id"));
    }

    @Test
    @Order(5)
    void anIdenticalReloadIs200WithTheSameResultAndAddsNoRow() throws Exception {
        Login loader = login(LOADER);
        long before = rows("solution_package", BOOTSTRAP_ORGANIZATION);

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(cache()));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(before, rows("solution_package", BOOTSTRAP_ORGANIZATION));
    }

    @Test
    @Order(6)
    void listSolutionsReturnsTheEntryWithBothVersionsAndANewerStateVersion() throws Exception {
        Login loader = login(LOADER);

        JsonNode body = MAPPER.readTree(get("/api/cp/solutions", loader).body());

        assertEquals(1, body.get("items").size());
        JsonNode entry = body.at("/items/0");
        assertEquals("cache", entry.get("solution").asText());
        assertEquals(List.of(1, 2), List.of(entry.at("/packageVersions/0").asInt(), entry.at("/packageVersions/1").asInt()));
        assertEquals(2, entry.get("latestPackageVersion").asInt());
        assertEquals("unsigned", entry.get("signature").asText());
        long stateVersion = ownerJdbc.queryForObject("SELECT max(load_seq) FROM sm_control.solution_package"
                + " WHERE organization_id = :o", Map.of("o", BOOTSTRAP_ORGANIZATION), Long.class);
        assertEquals(stateVersion, entry.get("stateVersion").asLong(), "rises on each load");
        assertFalse(body.toString().contains(otherEntryId.toString()), "another organization's entry never appears");
        assertFalse(body.toString().contains("rival-solution"));
    }

    @Test
    @Order(7)
    void anEnvironmentBoundOperatorCanListTheCatalogButNotLoadIntoIt() throws Exception {
        Login operator = login(ENV_OPERATOR);

        HttpResponse<String> list = get("/api/cp/solutions", operator);
        HttpResponse<String> load = postJson("/api/cp/solutions/load", operator, packageBody(cacheVersion(3, ">=3.0 <4.0")));

        assertEquals(200, list.statusCode(), list.body());
        assertEquals(1, MAPPER.readTree(list.body()).get("items").size(),
                "S0 point 24: reach with no item filter - the operator holds the verb at an environment, and the "
                        + "catalog has no environment to filter by");
        assertEquals(403, load.statusCode(), load.body());
        assertEquals("FORBIDDEN", MAPPER.readTree(load.body()).get("code").asText());
        assertEquals(2L, rows("solution_package", BOOTSTRAP_ORGANIZATION), "the refused load wrote nothing");
    }

    @Test
    @Order(8)
    void aPrincipalHoldingSolutionViewNowhereIsRefusedTheListAndCannotSeeTheOrganization() throws Exception {
        Login unbound = login(UNBOUND);

        HttpResponse<String> list = get("/api/cp/solutions", unbound);
        HttpResponse<String> load = postJson("/api/cp/solutions/load", unbound, packageBody(cache()));

        assertEquals(403, list.statusCode(), list.body());
        assertEquals(404, load.statusCode(), "no grant makes the organization visible, so it is simply not found");
    }

    @Test
    @Order(9)
    void aPackageFailingAGateIs422NamingTheGateAndWritesNothing() throws Exception {
        Login loader = login(LOADER);
        ObjectNode broken = cacheVersion(4, ">=4.0 <5.0");
        broken.remove("components");
        long before = rows("solution_package", BOOTSTRAP_ORGANIZATION);

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(broken));

        assertEquals(422, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("VALIDATION_FAILED", body.get("code").asText());
        assertTrue(body.get("detail").asText().contains("1"), body.get("detail").asText());
        assertEquals(before, rows("solution_package", BOOTSTRAP_ORGANIZATION));
    }

    @Test
    @Order(10)
    void aDevModePackageIsRefused422InProduction() throws Exception {
        Login loader = login(LOADER);
        ObjectNode devMode = (ObjectNode) readFixture("engine-edge.package.json");

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(devMode));

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(MAPPER.readTree(response.body()).get("detail").asText().contains("2"));
    }

    @Test
    @Order(11)
    void aDifferentDocumentUnderAnExistingVersionIs422() throws Exception {
        Login loader = login(LOADER);
        ObjectNode changed = cache();
        changed.put("appliesTo", ">=2.2 <2.6");

        HttpResponse<String> response = postJson("/api/cp/solutions/load", loader, packageBody(changed));

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(MAPPER.readTree(response.body()).get("detail").asText().contains("already loaded"));
    }

    @Test
    @Order(12)
    void aBodyNamingAnOrganizationIs400AndALoadWithoutCsrfIs403() throws Exception {
        Login loader = login(LOADER);
        ObjectNode withOrganization = MAPPER.createObjectNode();
        withOrganization.put("organizationId", otherOrganization.toString());
        withOrganization.set("package", cache());

        HttpResponse<String> named = postJson("/api/cp/solutions/load", loader, withOrganization.toString());
        HttpResponse<String> noCsrf = send(HttpRequest.newBuilder(uri("/api/cp/solutions/load"))
                .header("Cookie", SESSION_COOKIE + "=" + loader.session).header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(packageBody(cache()))).build());

        assertEquals(400, named.statusCode(), named.body());
        assertEquals(403, noCsrf.statusCode(), noCsrf.body());
        assertEquals("CSRF_REJECTED", MAPPER.readTree(noCsrf.body()).get("code").asText());
        assertEquals(1L, rows("solution_package", otherOrganization), "the other organization gained no package");
    }

    @Test
    @Order(13)
    void getSolutionPackageDefaultsToTheLatestVersionAndEchoesTheRawDocument() throws Exception {
        Login loader = login(LOADER);
        UUID solutionId = UUID.fromString(MAPPER.readTree(get("/api/cp/solutions", loader).body())
                .at("/items/0/solutionId").asText());

        HttpResponse<String> response = get("/api/cp/solutions/" + solutionId + "/package", loader);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals(solutionId.toString(), body.get("solutionId").asText());
        assertEquals(2, body.get("packageVersion").asInt(), "the latest of the two loaded versions (1, 2)");
        assertEquals("cache", body.at("/package/solution").asText());
        assertTrue(body.get("stateVersion").asLong() > 0);
    }

    @Test
    @Order(14)
    void getSolutionPackageHonoursAnExplicitOlderVersion() throws Exception {
        Login loader = login(LOADER);
        UUID solutionId = UUID.fromString(MAPPER.readTree(get("/api/cp/solutions", loader).body())
                .at("/items/0/solutionId").asText());

        HttpResponse<String> response = get("/api/cp/solutions/" + solutionId + "/package?packageVersion=1", loader);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, MAPPER.readTree(response.body()).get("packageVersion").asInt());
    }

    @Test
    @Order(15)
    void getSolutionPackageIs404ForAVersionNoLoadedPackageServes() throws Exception {
        Login loader = login(LOADER);
        UUID solutionId = UUID.fromString(MAPPER.readTree(get("/api/cp/solutions", loader).body())
                .at("/items/0/solutionId").asText());

        HttpResponse<String> response = get("/api/cp/solutions/" + solutionId + "/package?packageVersion=99", loader);

        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(16)
    void getSolutionPackageIs404ForAnotherOrganizationsEntry() throws Exception {
        Login loader = login(LOADER);

        HttpResponse<String> response = get("/api/cp/solutions/" + otherEntryId + "/package", loader);

        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(17)
    void getSolutionPackageIsReadableByAnEnvironmentBoundOperatorHoldingSolutionViewOnly() throws Exception {
        Login operator = login(ENV_OPERATOR);
        UUID solutionId = UUID.fromString(MAPPER.readTree(get("/api/cp/solutions", operator).body())
                .at("/items/0/solutionId").asText());

        HttpResponse<String> response = get("/api/cp/solutions/" + solutionId + "/package", operator);

        assertEquals(200, response.statusCode(), response.body());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static long rows(String table, UUID organization) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control." + table + " WHERE organization_id = :o",
                Map.of("o", organization), Long.class);
    }

    private record Login(String session, String csrf) {
    }

    private Login login(String username) throws IOException, InterruptedException {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization\":\"" + BOOTSTRAP_ORGANIZATION
                        + "\",\"username\":\"" + username + "\",\"credential\":\"" + CREDENTIAL + "\"}")).build());
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
        String value = cookie.substring((SESSION_COOKIE + "=").length());
        int semicolon = value.indexOf(';');
        return new Login(semicolon < 0 ? value : value.substring(0, semicolon),
                MAPPER.readTree(login.body()).get("csrfToken").asText());
    }

    private HttpResponse<String> get(String path, Login login) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Cookie", SESSION_COOKIE + "=" + login.session)
                .GET().build());
    }

    private HttpResponse<String> postJson(String path, Login login, String body)
            throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).header("Cookie", SESSION_COOKIE + "=" + login.session)
                .header("Origin", CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String packageBody(JsonNode document) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("package", document);
        return body.toString();
    }

    private static ObjectNode cache() {
        return (ObjectNode) readFixture("cache-2.4.package.json");
    }

    private static ObjectNode cacheVersion(int version, String appliesTo) {
        ObjectNode doc = cache();
        doc.set("packageVersion", MAPPER.createArrayNode().add(version));
        doc.put("appliesTo", appliesTo);
        return doc;
    }

    private static JsonNode readFixture(String name) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/validation/fixtures/" + name);
            if (Files.isRegularFile(candidate)) {
                try {
                    return MAPPER.readTree(Files.readString(candidate));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixture " + name + " not found from " + Path.of("").toAbsolutePath());
    }
}
