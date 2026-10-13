package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 013 T2.3: the five hierarchy writes and the two entitlement operations are SERVED. The real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptors, the real
 * {@code ScopeEvaluatingAuthorizationService} over real grants, the real services and both real triggers (the
 * {@code V015} name indexes and the change audit). Nothing is faked.
 *
 * <p>What this proves that {@code HierarchyAdministrationControllerTest} and {@code EntitlementControllerTest} cannot:
 * that the resolvers' resources are the ones the REAL authorizer can evaluate (the install anchor role exists, a
 * sub-organization id resolves under the session's tenant), that a unit-bound principal creates in its own unit and finds
 * a sibling unit absent, that {@code OR-123}'s comparison is the database's (a case-only difference is a {@code 409}, the
 * exact name is the {@code 200} replay, the same name under another parent is fine), that the replay and a rename to the
 * node's own name leave no second audit row, and that an organization-bound Administrator cannot raise its own quota
 * while an install-bound one can.
 *
 * <p><b>{@code OR-125} (3) closes W2D-2:</b> an organization is renamed at install scope only.
 * {@link #anOrganizationBoundAdministratorCannotRenameItsOrganizationSoTheInstallWideConflictIsNoCrossTenantOracle} proves
 * the organization-bound Administrator gets the same {@code 403} for a taken name as for a fresh one, and
 * {@link #anInstallBoundAdministratorRenamesItsOrganizationAndSeesTheInstallWideConflict} that the install-bound one renames
 * and sees the {@code 409}.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HierarchyAdministrationEndpointIT {

    private static final String BASE = "/api/cp/hierarchy/organizations/";
    private static final String INSTALL_ADMIN = "hier-it-install-admin";
    private static final String UNIT_ADMIN = "hier-it-unit-admin";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Client.REQUEST_TIMEOUT).build();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    /** A second sub-organization of the seeded organization, which {@link #UNIT_ADMIN} holds no grant on. */
    private static UUID siblingUnit;

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

        // an Administrator bound at the INSTALL scope V007 seeded, in the seeded organization (entitlement.set needs it)
        UUID installScope = ownerJdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'install'",
                Map.of(), UUID.class);
        DeclareFixtures.bind(ownerJdbc, DeclareFixtures.principal(ownerJdbc, world.organization(), INSTALL_ADMIN),
                "Administrator", installScope);

        // an Administrator bound at ONE sub-organization, plus a sibling sub-organization it holds nothing on
        UUID unitScope = DeclareFixtures.scope(ownerJdbc, "organization_unit", world.organization(), world.unit(), null);
        DeclareFixtures.bind(ownerJdbc, DeclareFixtures.principal(ownerJdbc, world.organization(), UNIT_ADMIN),
                "Administrator", unitScope);
        siblingUnit = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name)"
                        + " VALUES (:o, :u, 'hier-it-sibling-unit')",
                new MapSqlParameterSource().addValue("o", world.organization()).addValue("u", siblingUnit));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Client client() {
        return new Client(port);
    }

    private HttpResponse<String> send(String method, String path, Login login, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .header("Content-Type", "application/json").timeout(Client.REQUEST_TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String name(String value) {
        return DeclareFixtures.MAPPER.createObjectNode().put("name", value).toString();
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    private String subOrgs() {
        return BASE + world.organization() + "/sub-orgs";
    }

    private int auditRows(String operation, String nodeName) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE audit_operation = :op"
                        + " AND new_values->>'name' = :name",
                Map.of("op", operation, "name", nodeName), Integer.class);
    }

    // ------------------------------------------------------------------------------------------ the sequence

    @Test
    void anAdministratorCreatesAndRenamesTheTreeAndTheNamesShowInTheReads() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);

        HttpResponse<String> created = send("POST", subOrgs(), admin, name("Platform"));
        assertEquals(201, created.statusCode(), created.body());
        UUID platform = UUID.fromString(json(created).get("id").asText());
        assertEquals("Platform", json(created).get("name").asText());
        assertEquals(1, auditRows("HIERARCHY_NODE_CREATED", "Platform"));

        HttpResponse<String> replay = send("POST", subOrgs(), admin, name("Platform"));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(platform.toString(), json(replay).get("id").asText(), "the replay returns the same node");
        assertEquals(1, auditRows("HIERARCHY_NODE_CREATED", "Platform"), "a replay writes no second audit row");

        HttpResponse<String> envCreated = send("POST", subOrgs() + "/" + platform + "/environments", admin, name("Dev"));
        assertEquals(201, envCreated.statusCode(), envCreated.body());
        UUID dev = UUID.fromString(json(envCreated).get("id").asText());
        assertEquals(200, send("POST", subOrgs() + "/" + platform + "/environments", admin, name("Dev")).statusCode());

        // the same environment name under ANOTHER sub-organization is not a clash
        assertEquals(201, send("POST", subOrgs() + "/" + world.unit() + "/environments", admin, name("Dev")).statusCode());

        // names appear in the reads (the Identity carries them too once p/or116 is in)
        JsonNode listed = json(client().get(subOrgs(), admin));
        assertTrue(names(listed).contains("Platform"), listed.toString());
        JsonNode envs = json(client().get(subOrgs() + "/" + platform + "/environments", admin));
        assertEquals(List.of("Dev"), names(envs));

        // a rename; a rename to the node's own name writes nothing; a case-only change of its OWN name is allowed
        HttpResponse<String> renamed = send("PATCH", subOrgs() + "/" + platform, admin, name("Platform 2"));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Platform 2", json(renamed).get("name").asText());
        int renamedRows = auditRows("HIERARCHY_NODE_RENAMED", "Platform 2");
        assertEquals(1, renamedRows);
        assertEquals(200, send("PATCH", subOrgs() + "/" + platform, admin, name("Platform 2")).statusCode());
        assertEquals(renamedRows, auditRows("HIERARCHY_NODE_RENAMED", "Platform 2"), "own name: no second audit row");
        assertEquals(200, send("PATCH", subOrgs() + "/" + platform, admin, name("PLATFORM 2")).statusCode(),
                "the node does not clash with itself");

        HttpResponse<String> envRenamed = send("PATCH", subOrgs() + "/" + platform + "/environments/" + dev, admin,
                name("Development"));
        assertEquals(200, envRenamed.statusCode(), envRenamed.body());
        assertEquals("Development", json(envRenamed).get("name").asText());

        // the renamed environment is what the read of its sub-organization now says, and the Identity (OR-116) carries
        // the sub-organization under its new name
        assertEquals(List.of("Development"), names(json(client().get(subOrgs() + "/" + platform + "/environments", admin))));
        JsonNode identity = json(client().get("/api/cp/auth/identity", admin));
        List<String> visibleUnits = new java.util.ArrayList<>();
        for (JsonNode organization : identity.get("organizations")) {
            for (JsonNode unit : organization.get("subOrganizations")) {
                visibleUnits.add(unit.get("name").asText());
            }
        }
        assertTrue(visibleUnits.contains("PLATFORM 2"), "the Identity shows the current name: " + visibleUnits);
        assertFalse(visibleUnits.contains("Platform"), "and not the old one: " + visibleUnits);
    }

    @Test
    void aCaseOnlyDifferenceIsA409ConflictAndNotAReplayAndNothingIsEchoed() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        assertEquals(201, send("POST", subOrgs(), admin, name("Casing Test")).statusCode());

        HttpResponse<String> clash = send("POST", subOrgs(), admin, name("casing test"));
        assertEquals(409, clash.statusCode(), clash.body());
        assertEquals("CONFLICT", json(clash).get("code").asText());
        assertFalse(clash.body().contains("casing test"), "the name a caller sent is not echoed");
        assertEquals(0, auditRows("HIERARCHY_NODE_CREATED", "casing test"), "a refused create writes nothing");

        // a rename of ANOTHER sibling onto it is the same 409
        HttpResponse<String> other = send("POST", subOrgs(), admin, name("Casing Other"));
        UUID otherId = UUID.fromString(json(other).get("id").asText());
        HttpResponse<String> rename = send("PATCH", subOrgs() + "/" + otherId, admin, name("CASING TEST"));
        assertEquals(409, rename.statusCode(), rename.body());
        assertEquals("CONFLICT", json(rename).get("code").asText());
    }

    @Test
    void anOrganizationBoundAdministratorCannotRenameItsOrganizationSoTheInstallWideConflictIsNoCrossTenantOracle()
            throws Exception {
        // OR-125 (3) (closes W2D-2): an organization is renamed at INSTALL scope only. The organization-bound Administrator
        // is refused 403 whatever name it sends and whatever organization it names, so it can never learn that another
        // organization holds a name: the answer for a taken name equals the answer for a fresh one.
        String foreignName = ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", world.otherOrganization()), String.class);
        String ownName = ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", world.organization()), String.class);
        Login admin = client().login(DeclareFixtures.ADMIN);

        HttpResponse<String> taken = send("PATCH", BASE + world.organization(), admin, name(foreignName.toUpperCase()));
        HttpResponse<String> fresh = send("PATCH", BASE + world.organization(), admin, name("Fresh " + UUID.randomUUID()));
        HttpResponse<String> foreignId = send("PATCH", BASE + world.otherOrganization(), admin, name(foreignName));
        HttpResponse<String> absentId = send("PATCH", BASE + UUID.randomUUID(), admin, name(foreignName));
        for (HttpResponse<String> refused : List.of(taken, fresh, foreignId, absentId)) {
            assertEquals(403, refused.statusCode(), refused.body());
            assertEquals("FORBIDDEN", json(refused).get("code").asText());
            assertFalse(refused.body().contains(foreignName), "the name is never echoed");
        }
        // W5 review W5C-3: a malformed id is 404 before the authorizer, whoever asks (it names no organization)
        assertEquals(404, send("PATCH", BASE + "not-a-uuid", admin, name("X")).statusCode());
        assertEquals(json(taken).path("title").asText() + json(taken).path("detail").asText(),
                json(fresh).path("title").asText() + json(fresh).path("detail").asText(),
                "a taken name and a fresh one are refused identically");
        assertEquals(ownName, ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", world.organization()), String.class), "nothing was written");
    }

    @Test
    void anInstallBoundAdministratorRenamesItsOrganizationAndSeesTheInstallWideConflict() throws Exception {
        // OR-125 (3): the install-wide organization-name uniqueness (OR-123) is visible to install principals only
        String foreignName = ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", world.otherOrganization()), String.class);
        String ownName = ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", world.organization()), String.class);
        Login install = client().login(INSTALL_ADMIN);

        HttpResponse<String> clash = send("PATCH", BASE + world.organization(), install, name(foreignName.toUpperCase()));
        assertEquals(409, clash.statusCode(), clash.body());
        assertEquals("CONFLICT", json(clash).get("code").asText());
        assertFalse(json(clash).path("detail").asText("").toLowerCase().contains("sibling"),
                "install-wide, not a sibling clash");
        assertFalse(clash.body().contains(foreignName), "the name is never echoed");

        String renamedTo = "Install Renamed " + UUID.randomUUID();
        try {
            HttpResponse<String> renamed = send("PATCH", BASE + world.organization(), install, name(renamedTo));
            assertEquals(200, renamed.statusCode(), renamed.body());
            assertEquals(renamedTo, json(renamed).get("name").asText());
            assertEquals(1, auditRows("HIERARCHY_NODE_RENAMED", renamedTo));
            // another organization's id is as absent as a nonexistent one, for an install-bound caller too (I5)
            assertEquals(404, send("PATCH", BASE + world.otherOrganization(), install, name("X")).statusCode());
            assertEquals(404, send("PATCH", BASE + UUID.randomUUID(), install, name("X")).statusCode());
            assertEquals(404, send("PATCH", BASE + "not-a-uuid", install, name("X")).statusCode(), "W5C-3");
        } finally {
            send("PATCH", BASE + world.organization(), install, name(ownName));
        }
        assertEquals(ownName, ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", world.organization()), String.class));
    }

    @Test
    void aSessionCannotNameAnotherOrganizationOnAnyWrite() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        UUID foreign = world.otherOrganization();
        String foreignBase = BASE + foreign;
        assertEquals(404, send("POST", foreignBase + "/sub-orgs", admin, name("X")).statusCode());
        // an organization rename is install-scoped (OR-125 (3)): the organization-bound caller is refused at the install
        // anchor before the id is read, so a foreign id is 403 here (and 404 for an install-bound caller, below)
        assertEquals(403, send("PATCH", foreignBase, admin, name("X")).statusCode());
        assertEquals(404, send("PATCH", foreignBase, client().login(INSTALL_ADMIN), name("X")).statusCode());
        assertEquals(404, send("POST", foreignBase + "/sub-orgs/" + UUID.randomUUID() + "/environments", admin, name("X"))
                .statusCode());
        assertEquals(404, send("PATCH", foreignBase + "/sub-orgs/" + UUID.randomUUID(), admin, name("X")).statusCode());
        assertEquals(404, send("PATCH", foreignBase + "/sub-orgs/" + UUID.randomUUID() + "/environments/"
                + world.otherEnvironment(), admin, name("X")).statusCode());
        // the foreign organization's own name is untouched
        assertNotEquals("X", ownerJdbc.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", foreign), String.class));
        // and the other organization's administrator cannot reach this one's tree
        Login foreignAdmin = client().login(DeclareFixtures.FOREIGN);
        assertEquals(404, send("POST", subOrgs(), foreignAdmin, name("X")).statusCode());
    }

    @Test
    void aUnitBoundAdministratorCreatesInItsOwnUnitAndFindsASiblingUnitAbsent() throws Exception {
        Login unit = client().login(UNIT_ADMIN);
        HttpResponse<String> own = send("POST", subOrgs() + "/" + world.unit() + "/environments", unit, name("Unit Env"));
        assertEquals(201, own.statusCode(), own.body());
        HttpResponse<String> sibling = send("POST", subOrgs() + "/" + siblingUnit + "/environments", unit, name("Unit Env"));
        assertEquals(404, sibling.statusCode(), "the sibling unit is as absent as a nonexistent one: " + sibling.body());
        // hierarchy.edit is not held at the organization: a sub-organization cannot be created
        assertEquals(403, send("POST", subOrgs(), unit, name("Not Allowed")).statusCode());
        assertEquals(403, send("PATCH", BASE + world.organization(), unit, name("Not Allowed")).statusCode());
        // a newly created environment is usable as a rename target of the same principal at once
        UUID created = UUID.fromString(json(own).get("id").asText());
        assertEquals(200, send("PATCH", subOrgs() + "/" + world.unit() + "/environments/" + created, unit,
                name("Unit Env 2")).statusCode());
    }

    @Test
    void aViewerAndAnOperatorCannotWriteTheTree() throws Exception {
        for (String user : List.of(DeclareFixtures.VIEWER, DeclareFixtures.OPERATOR_ONE)) {
            Login login = client().login(user);
            assertEquals(403, send("POST", subOrgs(), login, name("No")).statusCode(), user);
            assertEquals(403, send("PATCH", BASE + world.organization(), login, name("No")).statusCode(), user);
        }
    }

    @Test
    void everyWriteNeedsASessionAndACsrfToken() throws Exception {
        Client client = client();
        for (String[] route : new String[][] {{"POST", subOrgs()}, {"PATCH", BASE + world.organization()},
                {"PUT", "/api/cp/entitlements/cache"}}) {
            assertEquals(401, client.anonymous(route[0], route[1]).statusCode(), route[0] + " " + route[1]);
        }
        Login admin = client.login(DeclareFixtures.ADMIN);
        Login noCsrf = new Login(admin.session(), "not-the-token");
        assertEquals(403, send("POST", subOrgs(), noCsrf, name("X")).statusCode());
        assertEquals(403, send("PUT", "/api/cp/entitlements/cache", noCsrf, "{\"maxInstances\":2}").statusCode());
    }

    @Test
    void anInvalidNameIs400AndWritesNothing() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        int before = ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.organization_unit WHERE organization_id = :o",
                Map.of("o", world.organization()), Integer.class);
        for (String body : List.of("{}", "{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":7}",
                "{\"name\":\"" + "x".repeat(101) + "\"}", "{\"name\":\"a\\tb\"}",
                "{\"name\":\"ok\",\"organizationId\":\"" + world.otherOrganization() + "\"}")) {
            HttpResponse<String> response = send("POST", subOrgs(), admin, body);
            assertEquals(400, response.statusCode(), body);
        }
        assertEquals(before, ownerJdbc.queryForObject(
                "SELECT count(*) FROM sm_control.organization_unit WHERE organization_id = :o",
                Map.of("o", world.organization()), Integer.class));
    }

    // ------------------------------------------------------------------------------------------ entitlements

    @Test
    void anInstallBoundAdministratorSetsItsOwnOrganizationsLimitAndAnOrganizationBoundOneCannot() throws Exception {
        Login install = client().login(INSTALL_ADMIN);
        Login organizationAdmin = client().login(DeclareFixtures.ADMIN);

        HttpResponse<String> refused = send("PUT", "/api/cp/entitlements/cache", organizationAdmin,
                "{\"maxInstances\":9}");
        assertEquals(403, refused.statusCode(), "an organization-bound Administrator cannot raise its own limit: "
                + refused.body());

        HttpResponse<String> set = send("PUT", "/api/cp/entitlements/cache", install, "{\"maxInstances\":4}");
        assertEquals(200, set.statusCode(), set.body());
        assertEquals("cache", json(set).get("solution").asText());
        assertEquals(4, json(set).get("maxInstances").asInt());
        assertEquals(0, json(set).get("declaredInstances").asInt());
        // W4 review W4D-3: the actor is the install-bound Administrator who called, and the row is this organization's
        // 'cache' entitlement, not whichever ENTITLEMENT_SET row any other test in the class may have written
        UUID installAdminId = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o"
                + " AND username = :u", Map.of("o", world.organization(), "u", INSTALL_ADMIN), UUID.class);
        Map<String, Object> auditKey = Map.of("o", world.organization().toString(), "s", "cache", "a", installAdminId);
        String auditCount = "SELECT count(*) FROM sm_control.change_audit WHERE audit_operation = 'ENTITLEMENT_SET'"
                + " AND table_name = 'entitlement' AND row_key ->> 'organization_id' = :o AND row_key ->> 'solution' = :s"
                + " AND actor_principal_id = :a";
        int rows = ownerJdbc.queryForObject(auditCount, auditKey, Integer.class);
        assertEquals(1, rows, "audited once, with the install administrator as the actor");

        // a PUT replay changes nothing: same answer, no second audit row
        assertEquals(200, send("PUT", "/api/cp/entitlements/cache", install, "{\"maxInstances\":4}").statusCode());
        assertEquals(rows, ownerJdbc.queryForObject(auditCount, auditKey, Integer.class));

        // a read by anyone with solution.view at the organization
        for (Login reader : List.of(organizationAdmin, client().login(DeclareFixtures.VIEWER))) {
            HttpResponse<String> list = client().get("/api/cp/entitlements", reader);
            assertEquals(200, list.statusCode(), list.body());
            JsonNode cache = null;
            for (JsonNode item : json(list).get("items")) {
                if ("cache".equals(item.get("solution").asText())) {
                    cache = item;
                }
            }
            assertTrue(cache != null && cache.get("maxInstances").asInt() == 4, list.body());
        }
        // the limit of a solution is not removable, and 0 is not a limit
        assertEquals(400, send("PUT", "/api/cp/entitlements/cache", install, "{\"maxInstances\":0}").statusCode());
        assertEquals(405, send("DELETE", "/api/cp/entitlements/cache", install, "").statusCode());
        assertEquals(400, send("PUT", "/api/cp/entitlements/Not-A-Solution", install, "{\"maxInstances\":1}").statusCode());
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode item : list.get("items")) {
            names.add(item.get("name").asText());
        }
        return names;
    }
}
