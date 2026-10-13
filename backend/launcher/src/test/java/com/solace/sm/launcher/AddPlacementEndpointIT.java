package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 008 S3 ({@code BE-D47}, {@code BE-D53}): {@code addPlacement} and {@code retirePlacement} are SERVED - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, the real
 * {@code GrantReachAuthorization}, the real orchestrator, the real inventory service and both planes' real triggers.
 * Nothing is faked. {@code PlacementControllerTest} pins the gateway's half with the ports faked;
 * {@code AddPlacementIT} and {@code RetirePlacementIT} the data plane's half without HTTP; this is the same thing through
 * the one door a browser has.
 *
 * <p>State carries from test to test (ordered), as {@code InstanceEndpointsIT}: the second placement the add writes is the
 * one the retire removes. Every refusal asserts the data plane's row counts did not move.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddPlacementEndpointIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Client.REQUEST_TIMEOUT).build();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instance;
    private static UUID firstPlacement;
    private static UUID secondPlacement;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static String placementsPath() {
        return "/api/dp/instances/" + instance + "/placements";
    }

    private static String retirePath(UUID placement, String query) {
        return placementsPath() + "/" + placement + query;
    }

    /** {@code DeclareFixtures.Client} has no DELETE (and is shared); this is the one the retire needs. */
    private HttpResponse<String> delete(String path, Login login) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(Client.REQUEST_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    /** A valid add body: a container, directory, health port and metrics host port no other placement uses. */
    private ObjectNode addBody(UUID target, String container, int healthPort, int metricsHostPort) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("targetId", target.toString());
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", container);
        placement.put("configDirectory", "/srv/sm/" + container);
        placement.put("containerConfigPath", "/app/external/spring/config");
        placement.put("fileName", "application.yml");
        placement.put("healthPort", healthPort);
        placement.putObject("metricsPorts").put("8081", metricsHostPort);
        return body;
    }

    private long count(String table) {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data." + table + " WHERE tenant_id = :t",
                Map.of("t", world.organization()), Long.class);
        return n == null ? 0 : n;
    }

    private record Counts(long placements, long endpoints, long requests) {
    }

    private Counts counts() {
        return new Counts(count("placement"), count("placement_metrics_endpoint"), count("apply_request"));
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void bothOperationsAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        Client http = client();
        assertEquals(401, http.anonymous("POST", "/api/dp/instances/" + UUID.randomUUID() + "/placements").statusCode());
        assertEquals(401, http.anonymous("DELETE", "/api/dp/instances/" + UUID.randomUUID() + "/placements/"
                + UUID.randomUUID() + "?workload=leave-running").statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredWithOnePlacement() throws Exception {
        Client http = client();
        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);
        instance = declared.instance();
        firstPlacement = declared.placement();
        solutionId = UUID.fromString(body(http.get("/api/dp/instances/" + instance,
                http.login(DeclareFixtures.OPERATOR_ONE))).get("solutionId").asText());
        assertEquals(1, count("placement"));
    }

    @Test
    @Order(3)
    void aViewerIsRefusedBothOperationsWith403AndNothingMoves() throws Exception {
        Client http = client();
        Login viewer = http.login(DeclareFixtures.VIEWER);
        Counts before = counts();

        HttpResponse<String> add = http.post(placementsPath(), viewer,
                addBody(world.targetOne(), "sm-second", 9201, 39201));
        HttpResponse<String> retire = delete(retirePath(firstPlacement, "?workload=leave-running"), viewer);

        assertEquals(403, add.statusCode(), add.body());
        assertEquals("FORBIDDEN", body(add).get("code").asText());
        assertEquals(403, retire.statusCode(), retire.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(4)
    void aSiblingEnvironmentsOperatorAndAForeignAdministratorSeeNoneOfItAs404() throws Exception {
        Client http = client();
        Counts before = counts();

        for (String who : java.util.List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            Login login = http.login(who);
            assertEquals(404, http.post(placementsPath(), login, addBody(world.targetOne(), "sm-second", 9201, 39201))
                    .statusCode(), who + " add");
            assertEquals(404, delete(retirePath(firstPlacement, "?workload=leave-running"), login).statusCode(),
                    who + " retire");
        }
        assertEquals(before, counts());
    }

    @Test
    @Order(5)
    void aTargetOwnedByASiblingEnvironmentIsRefusedTargetUseAs404AndNothingIsWritten() throws Exception {
        Client http = client();
        Counts before = counts();

        HttpResponse<String> response = http.post(placementsPath(), http.login(DeclareFixtures.OPERATOR_ONE),
                addBody(world.targetTwo(), "sm-cross", 9202, 39202));

        assertEquals(404, response.statusCode(), "the second verb, at the target the BODY names: " + response.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(6)
    void aBodyThatNamesAProbeAnOrganizationOrAnAcquisitionIs400AndNothingIsWritten() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        ObjectNode probe = addBody(world.targetOne(), "sm-probe", 9203, 39203);
        ((ObjectNode) probe.get("placement")).put("healthPath", "/anywhere");
        ObjectNode organization = addBody(world.targetOne(), "sm-org", 9204, 39204);
        organization.put("organizationId", UUID.randomUUID().toString());
        ObjectNode acquisition = addBody(world.targetOne(), "sm-acq", 9205, 39205);
        acquisition.putObject("acquisition").put("mode", "via-ssh");

        for (ObjectNode body : new ObjectNode[] {probe, organization, acquisition}) {
            assertEquals(400, http.post(placementsPath(), operator, body).statusCode(), body.toString());
        }
        assertEquals(before, counts());
    }

    @Test
    @Order(7)
    void anOperatorAddsASecondPlacementWhoseProbeComesFromThePackageAndNeverTheBody() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        HttpResponse<String> response = http.post(placementsPath(), operator,
                addBody(world.targetOne(), "sm-second", 9201, 39201));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode added = body(response);
        assertEquals(instance.toString(), added.get("instanceId").asText());
        assertEquals(2, added.get("placements").size());
        JsonNode first = added.get("placements").get(0);
        JsonNode second = added.get("placements").get(1);
        assertEquals(firstPlacement.toString(), first.get("placementId").asText());
        secondPlacement = UUID.fromString(second.get("placementId").asText());
        assertEquals(world.targetOne().toString(), second.get("targetId").asText());
        assertEquals("sm-second", second.get("containerName").asText());
        assertEquals(first.get("probeStrength").asText(), second.get("probeStrength").asText(),
                "the strength the package declares, copied; the body has no field for it");
        assertEquals(before.placements() + 1, counts().placements());
        assertEquals(before.endpoints() + 1, counts().endpoints(), "the new placement's own metrics endpoint (8081)");
        assertEquals(before.requests(), counts().requests(), "no apply, no stop: nothing touched a host");
        assertEquals(2, body(http.get("/api/dp/instances/" + instance, operator)).get("placements").size(),
                "and a read now shows both");
    }

    @Test
    @Order(8)
    void aPlacementCollisionIs409NamingTheClassAndNothingIsAdded() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        HttpResponse<String> response = http.post(placementsPath(), operator,
                addBody(world.targetOne(), "sm-second", 9301, 39301));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertTrue(body(response).get("detail").asText().contains("container name"), response.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(9)
    void aFieldOutsideTheTargetsEnvelopeIs422NamingTheField() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        HttpResponse<String> response = http.post(placementsPath(), operator,
                addBody(world.targetOne(), "other-cache", 9302, 39302));

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(body(response).get("detail").asText().contains("placement.containerName"), response.body());
        assertFalse(response.body().contains("other-cache"), "never the value");
        assertEquals(before, counts());
    }

    @Test
    @Order(10)
    void theWorkloadParameterIsRequiredAndAnythingButLeaveRunningIs400() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        for (String query : java.util.List.of("", "?workload=stop", "?workload=remove", "?workload=")) {
            assertEquals(400, delete(retirePath(secondPlacement, query), operator).statusCode(), query);
        }
        assertEquals(before, counts());
        assertEquals(2, body(http.get("/api/dp/instances/" + instance, operator)).get("placements").size());
    }

    @Test
    @Order(11)
    void aPlacementOfNowhereIs404() throws Exception {
        Client http = client();

        assertEquals(404, delete(retirePath(UUID.randomUUID(), "?workload=leave-running"),
                http.login(DeclareFixtures.OPERATOR_ONE)).statusCode());
    }

    @Test
    @Order(12)
    void anOperatorRetiresTheAddedPlacementAndTheResponseSaysTheWorkloadWasLeftRunning() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        HttpResponse<String> response = delete(retirePath(secondPlacement, "?workload=leave-running"), operator);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode retired = body(response);
        assertEquals(secondPlacement.toString(), retired.get("placementId").asText());
        assertEquals("left-running", retired.get("workload").asText());
        assertEquals(1, retired.get("instance").get("placements").size());
        assertEquals(firstPlacement.toString(),
                retired.get("instance").get("placements").get(0).get("placementId").asText());
        assertEquals(before.placements(), counts().placements(), "the row is kept forever");
        assertEquals(before.requests(), counts().requests(), "and no host was touched");
        assertEquals(1L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p AND retired_at IS NOT NULL",
                Map.of("t", world.organization(), "p", secondPlacement), Long.class));
        assertEquals(1, body(http.get("/api/dp/instances/" + instance, operator)).get("placements").size());
        assertEquals(404, delete(retirePath(secondPlacement, "?workload=leave-running"), operator).statusCode(),
                "a retired placement is not a live one: the second DELETE is the same 404 as nowhere");
    }

    @Test
    @Order(13)
    void theLastLivePlacementIs409AndStaysLive() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        HttpResponse<String> response = delete(retirePath(firstPlacement, "?workload=leave-running"), operator);

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertTrue(body(response).get("detail").asText().contains("undeclare"), response.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(14)
    void theFreedContainerNameAndPortsCanBeAddedAgainAndTheFirstPlacementIsStillTheFirst() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post(placementsPath(), operator,
                addBody(world.targetOne(), "sm-second", 9201, 39201));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode added = body(response);
        assertEquals(2, added.get("placements").size());
        assertEquals(firstPlacement.toString(), added.get("placements").get(0).get("placementId").asText());
        assertFalse(added.get("placements").get(1).get("placementId").asText().equals(secondPlacement.toString()),
                "a NEW placement: the retired row stays retired");
        assertEquals(3, count("placement"));
        assertEquals(solutionId.toString(), added.get("solutionId").asText());
    }

    // ---- OQ-8.3s (i): instance.bindTarget is checked AT THE INSTANCE (W2 review W2A-5) -------------------------------

    private UUID instanceScope(UUID instanceId) {
        ownerJdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id,"
                + " environment_id, solution_id, instance_id) SELECT :id, 'instance', organization_id,"
                + " organization_unit_id, environment_id, solution_id, instance_id FROM sm_control.instance"
                + " WHERE instance_id = :i ON CONFLICT DO NOTHING",
                Map.of("id", UUID.randomUUID(), "i", instanceId));
        return ownerJdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'instance'"
                + " AND instance_id = :i", Map.of("i", instanceId), UUID.class);
    }

    private static UUID role(NamedParameterJdbcTemplate jdbc, String name, String permission) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.role (role_id, name, built_in) VALUES (:id, :n, false)",
                Map.of("id", id, "n", name));
        jdbc.update("INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES (:id, :p)",
                Map.of("id", id, "p", permission));
        return id;
    }

    private static void bindRole(NamedParameterJdbcTemplate jdbc, UUID principal, UUID role, UUID scope) {
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                + " VALUES (:b, :p, :r, :s)", Map.of("b", UUID.randomUUID(), "p", principal, "r", role, "s", scope));
    }

    /**
     * A principal holding {@code instance.bindTarget} at ONE instance's scope and nothing else about instances, plus
     * {@code target.use} at the environment that owns the target (two custom single-verb roles, so no built-in role's
     * wider reach confuses the question). The grant at the instance must suffice there, and must not at a sibling
     * instance of the same environment: if {@code addPlacement} resolved its resource to the environment, the second
     * assertion would fail; if it resolved to the organization, the same.
     */
    @Test
    @Order(15)
    void anInstanceScopedBindTargetGrantSufficesAtThatInstanceAndNotAtItsSibling() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.OPERATOR_ONE);
        ApplyItSupport.Declared sibling = ApplyItSupport.declare(http, world, "sibling-cache", "sm-sibling", 9501);
        UUID siblingPath = sibling.instance();

        UUID bindOnly = role(ownerJdbc, "bind-only-" + UUID.randomUUID(), "instance.bindTarget");
        UUID useOnly = role(ownerJdbc, "use-only-" + UUID.randomUUID(), "target.use");
        String binder = "declare-it-instance-binder";
        UUID principal = DeclareFixtures.principal(ownerJdbc, world.organization(), binder);
        UUID envScope = ownerJdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'environment'"
                + " AND environment_id = :e", Map.of("e", world.envOne()), UUID.class);
        bindRole(ownerJdbc, principal, bindOnly, instanceScope(instance));
        bindRole(ownerJdbc, principal, useOnly, envScope);
        Login login = http.login(binder);
        Counts before = counts();

        HttpResponse<String> here = http.post(placementsPath(), login,
                addBody(world.targetOne(), "sm-bound", 9502, 39502));
        HttpResponse<String> there = http.post("/api/dp/instances/" + siblingPath + "/placements", login,
                addBody(world.targetOne(), "sm-bound-sibling", 9503, 39503));

        assertEquals(201, here.statusCode(), "the grant at the instance suffices there: " + here.body());
        assertTrue(there.statusCode() == 403 || there.statusCode() == 404,
                "and does NOT reach the sibling instance of the same environment: " + there.statusCode() + " " + there.body());
        assertEquals(before.placements() + 1, counts().placements(), "exactly the one add landed");
        assertEquals(1, body(http.get("/api/dp/instances/" + siblingPath, admin)).get("placements").size(),
                "the sibling gained nothing: it still has its one placement");
    }
}
