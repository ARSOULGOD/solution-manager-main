package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Plan 010 T4.4: {@code declareInstance}, {@code listInstances} and {@code getInstance} are SERVED - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, the real
 * {@code GrantReachAuthorization}, the real two-plane declare and both planes' real triggers. Nothing is faked.
 * A controller that only passes {@code InstanceControllerTest} is "built", not "served".
 *
 * <p>State carries from test to test (ordered), because a declare is exactly the thing whose effect on the NEXT
 * request matters: quota, name and placement collisions, and the reach filter all read what earlier tests wrote. Every
 * refusal test asserts the row counts on BOTH planes did not move.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceEndpointsIT {

    private static TwoSchemaTestDatabase db;
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instanceOne;
    private static UUID instanceTwo;
    private static String cpRuntimePassword;

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
        cpRuntimePassword = provisioned.properties().get("sm.control.runtime.password");
        // OR-48: the only entitlement write path. Quota 3, so the fourth declare is the refused one.
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", cpRuntimePassword, world.organization(), "cache", 3,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private String declarePath(UUID environment) {
        return "/api/dp/environments/" + environment + "/instances";
    }

    private ObjectNode body(UUID target, String name, String container, int healthPort) {
        return DeclareFixtures.declareBody(solutionId, target, name, container, healthPort);
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void declareListAndGetAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        Client http = client();
        assertEquals(401, http.anonymous("POST", declarePath(world.envOne())).statusCode());
        assertEquals(401, http.anonymous("GET", "/api/dp/solutions/" + UUID.randomUUID() + "/instances").statusCode());
        assertEquals(401, http.anonymous("GET", "/api/dp/instances/" + UUID.randomUUID()).statusCode());
    }

    @Test
    @Order(2)
    void thePackageIsLoadedSoThereIsACatalogEntryToDeclareAgainst() throws Exception {
        Client http = client();
        ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());

        HttpResponse<String> response = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);

        assertEquals(201, response.statusCode(), response.body());
        solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(response.body()).get("solutionId").asText());
    }

    @Test
    @Order(3)
    void anEnvironmentOperatorDeclaresAnInstanceAndBothPlanesHoldIt() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> response = http.post(declarePath(world.envOne()), operator,
                body(world.targetOne(), "orders-cache", "sm-orders", 9101));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode item = DeclareFixtures.MAPPER.readTree(response.body());
        instanceOne = UUID.fromString(item.get("instanceId").asText());
        assertEquals(7, instanceOne.version(), "the DP minted the id (OR-27)");
        assertEquals(solutionId.toString(), item.get("solutionId").asText(), "the CATALOG id (OR-41)");
        assertEquals(world.envOne().toString(), item.get("environmentId").asText());
        assertEquals(1, item.get("declaredPackageVersion").asInt());
        assertEquals("direct-http", item.at("/acquisition/mode").asText());
        assertFalse(item.at("/acquisition").has("host"), "OR-82: the mode only");
        assertEquals(world.targetOne().toString(), item.at("/placements/0/targetId").asText());
        assertEquals("sm-orders", item.at("/placements/0/containerName").asText());
        assertEquals("liveness", item.at("/placements/0/probeStrength").asText(),
                "the probe strength is the PACKAGE's declaration, copied at declare");
        assertTrue(item.get("stateVersion").asLong() > 0);

        // data plane: active, with the probe copied from the package (never from the body)
        Map<String, Object> dp = ownerJdbc.queryForMap("SELECT i.lifecycle, i.acquisition_mode, p.health_kind,"
                + " p.health_path, p.health_port, p.health_strength FROM sm_data.instance_state i"
                + " JOIN sm_data.placement p ON p.tenant_id = i.tenant_id AND p.instance_id = i.instance_id"
                + " WHERE i.tenant_id = :t AND i.instance_id = :i", Map.of("t", world.organization(), "i", instanceOne));
        assertEquals("active", dp.get("lifecycle"));
        assertEquals("direct-http", dp.get("acquisition_mode"));
        assertEquals("actuator-health", dp.get("health_kind"));
        assertEquals("/actuator/health", dp.get("health_path"));
        assertEquals(9101, dp.get("health_port"), "the host port is the body's; the package's 8081 is not used");
        assertEquals("liveness", dp.get("health_strength"));

        // control plane: the instance node, its environment's Solution node (OR-41) and their scope rows
        assertEquals(1L, count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o AND instance_id"
                + " = :i AND environment_id = :e", Map.of("o", world.organization(), "i", instanceOne,
                "e", world.envOne())));
        assertEquals(1L, count("SELECT count(*) FROM sm_control.solution WHERE organization_id = :o AND"
                + " environment_id = :e AND catalog_solution_id = :c", Map.of("o", world.organization(),
                "e", world.envOne(), "c", solutionId)), "the first declare in an environment creates its node");
        assertEquals(1L, count("SELECT count(*) FROM sm_control.scope WHERE kind = 'instance' AND instance_id = :i",
                Map.of("i", instanceOne)));

        // audited as INSTANCE_DECLARED, by the declaring principal, as the CP runtime role (OR-25)
        String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        List<Map<String, Object>> audit = ownerJdbc.queryForList("SELECT table_name, audit_operation,"
                + " actor_principal_id, db_role FROM sm_control.change_audit WHERE correlation_id = :c"
                + " ORDER BY change_audit_id", Map.of("c", UUID.fromString(correlation)));
        assertFalse(audit.isEmpty(), "the declare is audited");
        for (Map<String, Object> row : audit) {
            assertEquals("INSTANCE_DECLARED", row.get("audit_operation"), row.toString());
            assertEquals("sm_cp_runtime", row.get("db_role"));
        }
        assertEquals(ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.OPERATOR_ONE), UUID.class), audit.get(0).get("actor_principal_id"));
    }

    @Test
    @Order(4)
    void theDeclaredInstanceIsListedAndFetchableByItsOperatorAndByNobodyInTheSiblingEnvironment() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Login two = http.login(DeclareFixtures.OPERATOR_TWO);

        JsonNode listed = DeclareFixtures.MAPPER.readTree(
                http.get("/api/dp/solutions/" + solutionId + "/instances", one).body());
        HttpResponse<String> fetched = http.get("/api/dp/instances/" + instanceOne, one);

        assertEquals(Set.of(instanceOne.toString()), ids(listed));
        assertEquals(200, fetched.statusCode(), fetched.body());
        JsonNode item = DeclareFixtures.MAPPER.readTree(fetched.body());
        assertEquals("orders-cache", item.get("name").asText());
        assertEquals(1, item.get("placements").size());
        assertNotNull(item.get("stateVersion"));
        assertTrue(listed.get("items").get(0).get("stateVersion").asLong() > 0,
                "every item carries its own stateVersion");
        assertFalse(listed.has("stateVersion"), "§6.1: a collection carries versions per item");

        // the sibling environment's operator: reach lists nothing, and the instance is simply not found
        JsonNode siblingList = DeclareFixtures.MAPPER.readTree(
                http.get("/api/dp/solutions/" + solutionId + "/instances", two).body());
        assertEquals(0, siblingList.get("items").size());
        assertEquals(404, http.get("/api/dp/instances/" + instanceOne, two).statusCode(), "§5.6 rule 4");
    }

    @Test
    @Order(5)
    void anEnvironmentBoundOperatorListsItsOwnEnvironmentsInstancesOnlyAndAnAdministratorListsBoth()
            throws Exception {
        Client http = client();
        HttpResponse<String> declared = http.post(declarePath(world.envTwo()), http.login(DeclareFixtures.OPERATOR_TWO),
                body(world.targetTwo(), "billing-cache", "sm-billing", 9101));
        assertEquals(201, declared.statusCode(), declared.body());
        instanceTwo = UUID.fromString(DeclareFixtures.MAPPER.readTree(declared.body()).get("instanceId").asText());

        String listPath = "/api/dp/solutions/" + solutionId + "/instances";
        Set<String> seenByOne = ids(DeclareFixtures.MAPPER.readTree(
                http.get(listPath, http.login(DeclareFixtures.OPERATOR_ONE)).body()));
        Set<String> seenByTwo = ids(DeclareFixtures.MAPPER.readTree(
                http.get(listPath, http.login(DeclareFixtures.OPERATOR_TWO)).body()));
        Set<String> seenByAdmin = ids(DeclareFixtures.MAPPER.readTree(
                http.get(listPath, http.login(DeclareFixtures.ADMIN)).body()));
        Set<String> seenByViewer = ids(DeclareFixtures.MAPPER.readTree(
                http.get(listPath, http.login(DeclareFixtures.VIEWER)).body()));

        assertEquals(Set.of(instanceOne.toString()), seenByOne, "the unauthorized id is ABSENT, not merely hidden");
        assertEquals(Set.of(instanceTwo.toString()), seenByTwo);
        assertEquals(Set.of(instanceOne.toString(), instanceTwo.toString()), seenByAdmin);
        assertEquals(seenByAdmin, seenByViewer, "instance.view at the organization reaches every environment");
        assertEquals(404, http.get("/api/dp/instances/" + instanceTwo, http.login(DeclareFixtures.OPERATOR_ONE))
                .statusCode());
        assertEquals(2L, count("SELECT count(*) FROM sm_control.solution WHERE organization_id = :o AND"
                + " catalog_solution_id = :c", Map.of("o", world.organization(), "c", solutionId)),
                "one Solution node per environment: two environments, two nodes (OR-41)");
    }

    @Test
    @Order(6)
    void aTargetOwnedByASiblingEnvironmentIsRefusedTargetUseAs404AndNothingIsWritten() throws Exception {
        Client http = client();
        Counts before = counts();

        HttpResponse<String> response = http.post(declarePath(world.envOne()), http.login(DeclareFixtures.OPERATOR_ONE),
                body(world.targetTwo(), "cross-target", "sm-cross", 9102));

        assertEquals(404, response.statusCode(), response.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(7)
    void anEnvironmentTheCallerCannotSeeIs404AndNothingIsWrittenOnEitherPlane() throws Exception {
        Client http = client();
        Counts before = counts();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> siblingEnvironment = http.post(declarePath(world.envTwo()), one,
                body(world.targetOne(), "into-sibling", "sm-sibling", 9102));
        HttpResponse<String> foreignEnvironment = http.post(declarePath(world.otherEnvironment()), one,
                body(world.targetOne(), "into-foreign", "sm-foreign", 9102));
        HttpResponse<String> asForeign = http.post(declarePath(world.envOne()), http.login(DeclareFixtures.FOREIGN),
                body(world.targetOne(), "foreign-caller", "sm-fc", 9102));
        HttpResponse<String> foreignTarget = http.post(declarePath(world.envOne()), one,
                body(world.otherTarget(), "foreign-target", "sm-ft", 9102));

        assertEquals(404, siblingEnvironment.statusCode(), siblingEnvironment.body());
        assertEquals(404, foreignEnvironment.statusCode(), foreignEnvironment.body());
        assertEquals(404, asForeign.statusCode(), asForeign.body());
        assertEquals(404, foreignTarget.statusCode(), foreignTarget.body());
        assertEquals(before, counts());
    }

    @Test
    @Order(8)
    void aViewerHoldingNoInstanceCreateIs403AndNothingIsWritten() throws Exception {
        Client http = client();
        Counts before = counts();

        HttpResponse<String> response = http.post(declarePath(world.envOne()), http.login(DeclareFixtures.VIEWER),
                body(world.targetOne(), "viewer-attempt", "sm-viewer", 9102));

        assertEquals(403, response.statusCode(), response.body());
        assertEquals("FORBIDDEN", DeclareFixtures.MAPPER.readTree(response.body()).get("code").asText());
        assertEquals(before, counts());
    }

    @Test
    @Order(9)
    void aPlacementOrNameCollisionIs409ConflictAndNothingIsWrittenOnEitherPlane() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();

        HttpResponse<String> container = http.post(declarePath(world.envOne()), one,
                body(world.targetOne(), "other-name", "sm-orders", 9102));
        HttpResponse<String> port = http.post(declarePath(world.envOne()), one,
                body(world.targetOne(), "other-name-2", "sm-other", 9101));
        HttpResponse<String> name = http.post(declarePath(world.envOne()), one,
                body(world.targetOne(), "orders-cache", "sm-other", 9102));

        for (HttpResponse<String> response : List.of(container, port, name)) {
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("CONFLICT", DeclareFixtures.MAPPER.readTree(response.body()).get("code").asText());
        }
        assertTrue(DeclareFixtures.MAPPER.readTree(container.body()).get("detail").asText().contains("container name"));
        assertTrue(DeclareFixtures.MAPPER.readTree(port.body()).get("detail").asText().contains("health port"));
        assertFalse(container.body().contains("sm-orders"), "the value is never echoed");
        assertFalse(name.body().contains("orders-cache"), "the name is never echoed");
        assertEquals(before, counts());
    }

    @Test
    @Order(10)
    void aPlacementOutsideTheTargetsEnvelopeIs422NamingTheFieldAndNothingIsWritten() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();
        ObjectNode ssh = body(world.targetOne(), "ssh-attempt", "sm-ssh", 9102);
        ((ObjectNode) ssh.get("placement")).put("configDirectory", "/home/ec2-user/.ssh");
        ObjectNode evil = body(world.targetOne(), "evil-attempt", "evil-container", 9102);

        HttpResponse<String> directory = http.post(declarePath(world.envOne()), one, ssh);
        HttpResponse<String> container = http.post(declarePath(world.envOne()), one, evil);

        assertEquals(422, directory.statusCode(), directory.body());
        assertTrue(DeclareFixtures.MAPPER.readTree(directory.body()).get("detail").asText()
                .contains("placement.configDirectory"), directory.body());
        assertFalse(directory.body().contains(".ssh"), "the value is never echoed");
        assertEquals(422, container.statusCode(), container.body());
        assertTrue(DeclareFixtures.MAPPER.readTree(container.body()).get("detail").asText()
                .contains("placement.containerName"), container.body());
        assertFalse(container.body().contains("evil"));
        assertEquals(before, counts());
    }

    @Test
    @Order(11)
    void aMalformedOrUnknownDeclareIsRefusedBeforeEitherPlaneIsWritten() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);
        Counts before = counts();
        ObjectNode unknownVersion = body(world.targetOne(), "v7", "sm-v7", 9102);
        unknownVersion.put("declaredPackageVersion", 7);
        ObjectNode withHost = body(world.targetOne(), "host", "sm-host", 9102);
        ((ObjectNode) withHost.get("acquisition")).put("host", "169.254.169.254");
        ObjectNode withOrganization = body(world.targetOne(), "org", "sm-org", 9102);
        withOrganization.put("organizationId", world.otherOrganization().toString());

        HttpResponse<String> version = http.post(declarePath(world.envOne()), one, unknownVersion);
        HttpResponse<String> host = http.post(declarePath(world.envOne()), one, withHost);
        HttpResponse<String> organization = http.post(declarePath(world.envOne()), one, withOrganization);
        HttpResponse<String> noCsrf = http.postWithoutCsrf(declarePath(world.envOne()), one,
                body(world.targetOne(), "csrf", "sm-csrf", 9102));

        assertEquals(422, version.statusCode(), version.body());
        assertTrue(DeclareFixtures.MAPPER.readTree(version.body()).get("detail").asText()
                .contains("declaredPackageVersion"));
        assertEquals(422, host.statusCode(), host.body());
        assertTrue(DeclareFixtures.MAPPER.readTree(host.body()).get("detail").asText().contains("acquisition"));
        assertFalse(host.body().contains("169.254"));
        assertEquals(400, organization.statusCode(), organization.body());
        assertEquals(403, noCsrf.statusCode(), noCsrf.body());
        assertEquals("CSRF_REJECTED", DeclareFixtures.MAPPER.readTree(noCsrf.body()).get("code").asText());
        assertEquals(before, counts());
    }

    @Test
    @Order(12)
    void theQuotaIsEnforcedAtDeclareAndAnOverQuotaRefusalWritesNothing() throws Exception {
        Client http = client();
        Login one = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> third = http.post(declarePath(world.envOne()), one,
                body(world.targetOne(), "third", "sm-third", 9102));
        assertEquals(201, third.statusCode(), "two instances exist; quota 3 admits a third: " + third.body());
        Counts before = counts();
        HttpResponse<String> fourth = http.post(declarePath(world.envOne()), one,
                body(world.targetOne(), "fourth", "sm-fourth", 9103));

        assertEquals(409, fourth.statusCode(), fourth.body());
        assertEquals("ENTITLEMENT_REFUSED", DeclareFixtures.MAPPER.readTree(fourth.body()).get("code").asText());
        assertEquals(before, counts(), "the refused declare reserved nothing that survives");
        assertEquals(0L, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t AND lifecycle ="
                + " 'pending'", Map.of("t", world.organization())), "no pending row is left behind");
        // and the freed names are really free: the same names succeed once the quota is raised
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", cpRuntimePassword, world.organization(), "cache", 4,
                DeclareFixtures.ADMIN);
        HttpResponse<String> retried = http.post(declarePath(world.envOne()), one,
                body(world.targetOne(), "fourth", "sm-fourth", 9103));
        assertEquals(201, retried.statusCode(), retried.body());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private record Counts(long dpInstances, long dpPlacements, long cpInstances, long cpSolutions, long cpScopes) {
    }

    private Counts counts() {
        Map<String, Object> t = Map.of("t", world.organization());
        return new Counts(
                count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", t),
                count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", t),
                count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :t", t),
                count("SELECT count(*) FROM sm_control.solution WHERE organization_id = :t", t),
                count("SELECT count(*) FROM sm_control.scope WHERE organization_id = :t", t));
    }

    private static long count(String sql, Map<String, ?> params) {
        Long n = ownerJdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    private static Set<String> ids(JsonNode list) {
        Set<String> ids = new HashSet<>();
        list.get("items").forEach(item -> ids.add(item.get("instanceId").asText()));
        return ids;
    }
}
