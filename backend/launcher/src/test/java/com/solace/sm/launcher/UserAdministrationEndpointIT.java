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
import java.util.ArrayList;
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
 * Plan 013 T3.2: the four user operations and the four role operations are SERVED, and a login names its organization
 * ({@code OR-125} (4)). The real {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real
 * interceptors, the real authorization service over real grants, the real services, the real {@code V019} index and the
 * real change audit. Nothing is faked.
 *
 * <p>What this proves that the controller tests and the {@code cp-core} tests cannot: plan 013 S1 ("create the Operator,
 * bind it at an environment, sign in as it") end to end; that the resolvers' resources are the ones the REAL authorizer can
 * evaluate (a {@code PRINCIPAL}, an environment, an assignment's stored scope); that a unit-bound Administrator grants and
 * revokes inside its unit and is refused at the organization; that a username is unique per organization and a {@code 409}
 * never reveals another organization's user; and that an unknown organization is refused exactly as a wrong credential is.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserAdministrationEndpointIT {

    private static final String USERS = "/api/cp/admin/users";
    private static final String ASSIGNMENTS = "/api/cp/admin/assignments";
    private static final String PROVISIONAL = "a-provisional-credential-0001";
    private static final String OWN = "an-own-credential-after-rotation-0002";
    private static final String UNIT_ADMIN = "users-it-unit-admin";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Client.REQUEST_TIMEOUT).build();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID operatorRole;
    private static UUID viewerRole;
    private static UUID administratorRole;

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
        operatorRole = roleId("Operator");
        viewerRole = roleId("Viewer");
        administratorRole = roleId("Administrator");

        // an Administrator bound at ONE sub-organization: it may grant inside its unit and nowhere above it
        UUID unitScope = DeclareFixtures.scope(ownerJdbc, "organization_unit", world.organization(), world.unit(), null);
        DeclareFixtures.bind(ownerJdbc, DeclareFixtures.principal(ownerJdbc, world.organization(), UNIT_ADMIN),
                "Administrator", unitScope);
    }

    private static UUID roleId(String name) {
        return ownerJdbc.queryForObject("SELECT role_id FROM sm_control.role WHERE name = :n", Map.of("n", name),
                UUID.class);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Client client() {
        return new Client(port);
    }

    private HttpResponse<String> send(String method, String path, Login login, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .header("Content-Type", "application/json").timeout(Client.REQUEST_TIMEOUT);
        return HTTP.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rawLogin(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cp/auth/login"))
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("Content-Type", "application/json")
                .timeout(Client.REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String loginBody(Object organization, String username, String credential) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        if (organization != null) {
            body.put("organization", organization.toString());
        }
        return body.put("username", username).put("credential", credential).toString();
    }

    private Login loginAs(Object organization, String username, String credential) throws Exception {
        HttpResponse<String> response = rawLogin(loginBody(organization, username, credential));
        assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith(DeclareFixtures.SESSION_COOKIE + "=")).findFirst().orElseThrow();
        String value = cookie.substring((DeclareFixtures.SESSION_COOKIE + "=").length());
        int semicolon = value.indexOf(';');
        return new Login(semicolon < 0 ? value : value.substring(0, semicolon),
                json(response).get("csrfToken").asText());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    private static String createBody(String username, String credential) {
        return DeclareFixtures.MAPPER.createObjectNode().put("username", username).put("initialCredential", credential)
                .toString();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> create(Login by, String username) throws Exception {
        return send("POST", USERS, by, createBody(username, PROVISIONAL));
    }

    private UUID created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("id").asText());
    }

    private String grantBody(UUID principal, UUID role, String scopeJson) {
        return "{\"principalId\":\"" + principal + "\",\"roleId\":\"" + role + "\",\"scope\":" + scopeJson + "}";
    }

    private String envScope() {
        return "{\"subOrganizationId\":\"" + world.unit() + "\",\"environmentId\":\"" + world.envOne() + "\"}";
    }

    private String unitScope() {
        return "{\"subOrganizationId\":\"" + world.unit() + "\"}";
    }

    /** The audit rows of {@code operation} written by {@code actor} about the row whose key (or new values) names {@code subject}. */
    private int auditRows(String operation, UUID actor, UUID subject) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE audit_operation = :op"
                + " AND actor_principal_id = :actor AND (row_key::text LIKE :like OR coalesce(new_values::text, '') LIKE :like"
                + " OR coalesce(old_values::text, '') LIKE :like)",
                Map.of("op", operation, "actor", actor, "like", "%" + subject + "%"), Integer.class);
    }

    private UUID principalId(String username) {
        return ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o"
                        + " AND lower(username) = lower(:u)", Map.of("o", world.organization(), "u", username),
                UUID.class);
    }

    // -------------------------------------------------------------------------------- S1: create, bind, sign in

    @Test
    void anAdministratorCreatesAUserBindsItAtAnEnvironmentAndTheUserSignsInAndSeesWhatItHolds() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        String username = unique("Users.It.Operator");

        HttpResponse<String> createdResponse = create(admin, username);
        UUID user = created(createdResponse);
        JsonNode body = json(createdResponse);
        assertEquals(username, body.get("username").asText());
        assertEquals("local", body.get("source").asText());
        assertEquals("active", body.get("status").asText());
        assertTrue(body.get("mustRotate").asBoolean());
        assertFalse(createdResponse.body().contains(PROVISIONAL), "the credential is never echoed");
        assertEquals(1, auditRows("PRINCIPAL_CREATED", principalId(DeclareFixtures.ADMIN), user));

        // it signs in (by the organization's id, and by its name in any case), but must rotate before anything else
        Login provisional = loginAs(world.organization(), username.toUpperCase(), PROVISIONAL);
        HttpResponse<String> blocked = client().get("/api/cp/targets", provisional);
        assertEquals(428, blocked.statusCode(), blocked.body());
        HttpResponse<String> rotated = send("POST", "/api/cp/auth/credential", provisional,
                DeclareFixtures.MAPPER.createObjectNode().put("currentCredential", PROVISIONAL)
                        .put("newCredential", OWN).toString());
        assertEquals(204, rotated.statusCode(), rotated.body());

        // unbound: it holds no verb anywhere, so the reach read is refused
        Login operator = loginAs(world.organization(), username, OWN);
        assertEquals(403, client().get("/api/cp/targets", operator).statusCode());

        // the Administrator binds it at the environment; the assignment is listed with the scope it was granted at
        HttpResponse<String> granted = send("POST", ASSIGNMENTS, admin, grantBody(user, operatorRole, envScope()));
        assertEquals(201, granted.statusCode(), granted.body());
        UUID assignment = UUID.fromString(json(granted).get("assignmentId").asText());
        assertEquals(world.envOne().toString(), json(granted).at("/scope/environmentId").asText());
        assertEquals(1, auditRows("ROLE_BINDING_GRANTED", principalId(DeclareFixtures.ADMIN), user));
        assertEquals(200, send("POST", ASSIGNMENTS, admin, grantBody(user, operatorRole, envScope())).statusCode(),
                "the identical grant is a replay");
        JsonNode listed = json(client().get(ASSIGNMENTS + "?principalId=" + user, admin));
        assertEquals(1, listed.get("items").size());
        assertEquals(assignment.toString(), listed.at("/items/0/assignmentId").asText());

        // the user now sees the target owned at its environment, and not the one at the sibling environment
        HttpResponse<String> targets = client().get("/api/cp/targets", operator);
        assertEquals(200, targets.statusCode(), targets.body());
        List<String> names = new ArrayList<>();
        json(targets).get("items").forEach(t -> names.add(t.get("name").asText()));
        assertTrue(names.contains("target-one"), names.toString());
        assertFalse(names.contains("target-two"), names.toString());

        // revoking takes effect on the very next request
        assertEquals(204, send("DELETE", ASSIGNMENTS + "/" + assignment, admin, "").statusCode());
        assertEquals(403, client().get("/api/cp/targets", operator).statusCode());
        assertEquals(1, auditRows("ROLE_BINDING_REVOKED", principalId(DeclareFixtures.ADMIN), assignment));
        assertEquals(404, send("DELETE", ASSIGNMENTS + "/" + assignment, admin, "").statusCode(), "already gone");
    }

    // ------------------------------------------------------------------ OR-125 (4): per organization, no oracle

    @Test
    void aUsernameIsUniquePerOrganizationAndAnotherOrganizationsNameIsNeitherAConflictNorRevealed() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        Login foreign = client().login(DeclareFixtures.FOREIGN);
        String shared = unique("users-it-shared");

        UUID mine = created(create(admin, shared));
        // the other organization's Administrator creates the SAME name: no 409, so nothing about my organization is learned
        UUID theirs = created(create(foreign, shared));
        assertNotEquals(mine, theirs);
        // and a name only the foreign organization has is free here
        String onlyTheirs = unique("users-it-only-theirs");
        created(create(foreign, onlyTheirs));
        created(create(admin, onlyTheirs));

        // inside one organization a repeat is a 409, in any case, with a fixed body that names nothing
        for (String repeat : new String[] {shared, shared.toUpperCase()}) {
            HttpResponse<String> clash = create(admin, repeat);
            assertEquals(409, clash.statusCode(), clash.body());
            assertEquals("CONFLICT", json(clash).get("code").asText());
            assertFalse(clash.body().contains(shared), "the name a caller sent is not echoed");
            assertFalse(clash.body().contains(world.organization().toString()));
            assertFalse(clash.body().contains(world.otherOrganization().toString()));
        }

        // each lists only its own organization's users
        List<UUID> mineListed = new ArrayList<>();
        json(client().get(USERS, admin)).get("items").forEach(u -> mineListed.add(UUID.fromString(u.get("id").asText())));
        assertTrue(mineListed.contains(mine));
        assertFalse(mineListed.contains(theirs), "another organization's user is never listed");

        // the two users are signed in to by their own organization, with their own credential, and no other
        loginAs(world.organization(), shared, PROVISIONAL);
        loginAs(world.otherOrganization(), shared, PROVISIONAL);
    }

    @Test
    void anUnknownOrganizationIsRefusedExactlyAsAWrongCredentialIsAndAMissingOneIsMalformed() throws Exception {
        String organizationName = ownerJdbc.queryForObject("SELECT name FROM sm_control.organization"
                + " WHERE organization_id = :o", Map.of("o", world.organization()), String.class);

        HttpResponse<String> wrongCredential = rawLogin(loginBody(world.organization(), DeclareFixtures.ADMIN, "nope"));
        HttpResponse<String> unknownName = rawLogin(loginBody("No Such Organization", DeclareFixtures.ADMIN,
                DeclareFixtures.CREDENTIAL));
        HttpResponse<String> unknownId = rawLogin(loginBody(UUID.randomUUID(), DeclareFixtures.ADMIN,
                DeclareFixtures.CREDENTIAL));
        HttpResponse<String> otherOrganization = rawLogin(loginBody(world.otherOrganization(), DeclareFixtures.ADMIN,
                DeclareFixtures.CREDENTIAL));

        for (HttpResponse<String> refused : List.of(wrongCredential, unknownName, unknownId, otherOrganization)) {
            assertEquals(401, refused.statusCode(), refused.body());
            assertEquals("AUTHENTICATION_FAILED", json(refused).get("code").asText());
            assertEquals(strip(json(wrongCredential)), strip(json(refused)),
                    "byte-identical apart from the correlation id: the organization's existence is no oracle");
        }
        // a missing, empty or oversize organization is a malformed request, which says nothing about any organization
        assertEquals(400, rawLogin(loginBody(null, DeclareFixtures.ADMIN, DeclareFixtures.CREDENTIAL)).statusCode());
        assertEquals(400, rawLogin(loginBody("", DeclareFixtures.ADMIN, DeclareFixtures.CREDENTIAL)).statusCode());
        assertEquals(400, rawLogin(loginBody("x".repeat(257), DeclareFixtures.ADMIN, DeclareFixtures.CREDENTIAL))
                .statusCode());
        // the right organization, by id or by name in any case, and the username in any case, is the success
        for (Object organization : new Object[] {world.organization(), organizationName, organizationName.toUpperCase()}) {
            assertEquals(200, rawLogin(loginBody(organization, DeclareFixtures.ADMIN.toUpperCase(),
                    DeclareFixtures.CREDENTIAL)).statusCode(), String.valueOf(organization));
        }
    }

    private static JsonNode strip(JsonNode problem) {
        ObjectNode copy = ((ObjectNode) problem).deepCopy();
        copy.remove("correlationId");
        return copy;
    }

    // ----------------------------------------------------------------------------------------- authorization

    @Test
    void onlyAPrincipalHoldingTheVerbAtTheOrganizationAdministersUsers() throws Exception {
        Login viewer = client().login(DeclareFixtures.VIEWER);
        Login operator = client().login(DeclareFixtures.OPERATOR_ONE);
        Login unitAdmin = client().login(UNIT_ADMIN);
        UUID someone = principalId(DeclareFixtures.OPERATOR_TWO);

        for (Login caller : List.of(viewer, operator, unitAdmin)) {
            assertEquals(403, client().get(USERS, caller).statusCode());
            assertEquals(403, create(caller, unique("users-it-denied")).statusCode());
            assertEquals(403, send("POST", USERS + "/" + someone + "/deactivate", caller, "").statusCode());
            assertEquals(403, send("PUT", USERS + "/" + someone + "/credential", caller,
                    "{\"resetCredential\":\"" + PROVISIONAL + "\"}").statusCode());
        }
        // role.view at the organization: neither a Viewer nor an Operator has it, an organization Administrator does
        assertEquals(403, client().get("/api/cp/admin/roles", viewer).statusCode());
        assertEquals(403, client().get(ASSIGNMENTS, operator).statusCode());
        Login admin = client().login(DeclareFixtures.ADMIN);
        JsonNode roles = json(client().get("/api/cp/admin/roles", admin));
        List<String> roleNames = new ArrayList<>();
        roles.get("items").forEach(r -> roleNames.add(r.get("name").asText()));
        assertEquals(List.of("Administrator", "Operator", "Viewer"), roleNames);
    }

    @Test
    void aUnitBoundAdministratorGrantsAndRevokesInsideItsUnitAndIsRefusedAtTheOrganization() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        Login unitAdmin = client().login(UNIT_ADMIN);
        UUID user = created(create(admin, unique("users-it-unit")));

        // inside its own unit and its environments
        HttpResponse<String> inUnit = send("POST", ASSIGNMENTS, unitAdmin, grantBody(user, viewerRole, unitScope()));
        assertEquals(201, inUnit.statusCode(), inUnit.body());
        HttpResponse<String> inEnv = send("POST", ASSIGNMENTS, unitAdmin, grantBody(user, viewerRole, envScope()));
        assertEquals(201, inEnv.statusCode(), inEnv.body());
        // never at the organization (no role.assign there) and never in a unit it holds nothing on
        assertEquals(403, send("POST", ASSIGNMENTS, unitAdmin, grantBody(user, viewerRole, "{}")).statusCode());
        UUID foreignUnit = UUID.randomUUID();
        assertEquals(404, send("POST", ASSIGNMENTS, unitAdmin, grantBody(user, viewerRole,
                "{\"subOrganizationId\":\"" + foreignUnit + "\"}")).statusCode());

        // it revokes the binding it made, and cannot revoke one made at the organization
        UUID mine = UUID.fromString(json(inUnit).get("assignmentId").asText());
        assertEquals(204, send("DELETE", ASSIGNMENTS + "/" + mine, unitAdmin, "").statusCode());
        HttpResponse<String> atOrganization = send("POST", ASSIGNMENTS, admin, grantBody(user, viewerRole, "{}"));
        assertEquals(201, atOrganization.statusCode(), atOrganization.body());
        UUID orgBinding = UUID.fromString(json(atOrganization).get("assignmentId").asText());
        assertEquals(403, send("DELETE", ASSIGNMENTS + "/" + orgBinding, unitAdmin, "").statusCode());
        assertEquals(204, send("DELETE", ASSIGNMENTS + "/" + orgBinding, admin, "").statusCode());
    }

    // ------------------------------------------------------------------------------- the Administrator rules

    @Test
    void theAdministratorRoleIsNeverGrantedOrRevokedAndAnAdministratorIsNeverDeactivatedOrReset() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        UUID user = created(create(admin, unique("users-it-admin-rules")));

        HttpResponse<String> grant = send("POST", ASSIGNMENTS, admin, grantBody(user, administratorRole, "{}"));
        assertEquals(422, grant.statusCode(), grant.body());
        assertEquals("VALIDATION_FAILED", json(grant).get("code").asText());

        UUID adminId = principalId(DeclareFixtures.ADMIN);
        UUID adminBinding = ownerJdbc.queryForObject("SELECT binding_id FROM sm_control.role_binding"
                + " WHERE principal_id = :p", Map.of("p", adminId), UUID.class);
        assertEquals(409, send("DELETE", ASSIGNMENTS + "/" + adminBinding, admin, "").statusCode());
        assertEquals(409, send("POST", USERS + "/" + adminId + "/deactivate", admin, "").statusCode(), "itself");
        assertEquals(409, send("PUT", USERS + "/" + adminId + "/credential", admin,
                "{\"resetCredential\":\"" + PROVISIONAL + "\"}").statusCode());
        assertEquals("active", ownerJdbc.queryForObject("SELECT status FROM sm_control.principal WHERE id = :p",
                Map.of("p", adminId), String.class));
    }

    @Test
    void deactivatingEndsTheUsersSessionsAndResettingForcesARotationAndEndsThemToo() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        String resetName = unique("users-it-reset");
        String deactivatedName = unique("users-it-deactivated");
        UUID resetUser = created(create(admin, resetName));
        UUID deactivatedUser = created(create(admin, deactivatedName));
        for (UUID user : List.of(resetUser, deactivatedUser)) {
            // rotate off the provisional credential first so each is an ordinary, signed-in user
            String name = user.equals(resetUser) ? resetName : deactivatedName;
            Login provisional = loginAs(world.organization(), name, PROVISIONAL);
            assertEquals(204, send("POST", "/api/cp/auth/credential", provisional,
                    DeclareFixtures.MAPPER.createObjectNode().put("currentCredential", PROVISIONAL)
                            .put("newCredential", OWN).toString()).statusCode());
        }
        Login resetSession = loginAs(world.organization(), resetName, OWN);
        Login deactivatedSession = loginAs(world.organization(), deactivatedName, OWN);
        send("POST", ASSIGNMENTS, admin, grantBody(resetUser, viewerRole, "{}"));
        assertEquals(200, client().get("/api/cp/targets", resetSession).statusCode());

        // a reset: the old credential and the live session both stop working, and the new one must be rotated
        HttpResponse<String> reset = send("PUT", USERS + "/" + resetUser + "/credential", admin,
                "{\"resetCredential\":\"a-reset-credential-0003\"}");
        assertEquals(200, reset.statusCode(), reset.body());
        assertTrue(json(reset).get("mustRotate").asBoolean());
        assertFalse(reset.body().contains("a-reset-credential-0003"));
        assertEquals(401, client().get("/api/cp/targets", resetSession).statusCode(), "the session ended");
        assertEquals(401, rawLogin(loginBody(world.organization(), resetName, OWN)).statusCode());
        assertTrue(json(rawLogin(loginBody(world.organization(), resetName, "a-reset-credential-0003")))
                .get("mustRotate").asBoolean());

        // a deactivation: the live session ends, the user cannot sign in, and it is idempotent
        HttpResponse<String> deactivated = send("POST", USERS + "/" + deactivatedUser + "/deactivate", admin, "");
        assertEquals(200, deactivated.statusCode(), deactivated.body());
        assertEquals("deactivated", json(deactivated).get("status").asText());
        assertEquals(401, client().get("/api/cp/auth/identity", deactivatedSession).statusCode());
        assertEquals(401, rawLogin(loginBody(world.organization(), deactivatedName, OWN)).statusCode());
        assertEquals(200, send("POST", USERS + "/" + deactivatedUser + "/deactivate", admin, "").statusCode());
        // a deactivated user is neither reset nor granted a role
        assertEquals(409, send("PUT", USERS + "/" + deactivatedUser + "/credential", admin,
                "{\"resetCredential\":\"" + PROVISIONAL + "\"}").statusCode());
        assertEquals(409, send("POST", ASSIGNMENTS, admin, grantBody(deactivatedUser, viewerRole, "{}")).statusCode());
    }

    @Test
    void aUserOfAnotherOrganizationIsAsAbsentAsANonexistentOne() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        Login foreign = client().login(DeclareFixtures.FOREIGN);
        UUID theirs = created(create(foreign, unique("users-it-theirs")));

        assertEquals(404, send("POST", USERS + "/" + theirs + "/deactivate", admin, "").statusCode());
        assertEquals(404, send("PUT", USERS + "/" + theirs + "/credential", admin,
                "{\"resetCredential\":\"" + PROVISIONAL + "\"}").statusCode());
        assertEquals(404, send("POST", USERS + "/" + UUID.randomUUID() + "/deactivate", admin, "").statusCode());
        assertEquals(404, send("POST", USERS + "/not-a-uuid/deactivate", admin, "").statusCode());
        // granting a role to it is the same absence, and a scope in the other organization is a node that is not there
        assertEquals(404, send("POST", ASSIGNMENTS, admin, grantBody(theirs, viewerRole, "{}")).statusCode());
        assertEquals(404, send("POST", ASSIGNMENTS, admin, grantBody(principalId(DeclareFixtures.OPERATOR_ONE),
                viewerRole, "{\"subOrganizationId\":\"" + UUID.randomUUID() + "\"}")).statusCode());
        assertEquals("active", ownerJdbc.queryForObject("SELECT status FROM sm_control.principal WHERE id = :p",
                Map.of("p", theirs), String.class));
    }

    @Test
    void theCredentialIsInNoAuditRowAndNoResponse() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        String name = unique("users-it-audit");
        HttpResponse<String> response = create(admin, name);
        UUID user = created(response);
        send("PUT", USERS + "/" + user + "/credential", admin, "{\"resetCredential\":\"a-reset-credential-0004\"}");

        String audit = ownerJdbc.queryForList("SELECT coalesce(new_values::text, '') || coalesce(old_values::text, '')"
                + " || coalesce(row_key::text, '') FROM sm_control.change_audit WHERE row_key->>'id' = :id",
                Map.of("id", user.toString()), String.class).toString();
        assertFalse(audit.contains(PROVISIONAL), audit);
        assertFalse(audit.contains("a-reset-credential-0004"), audit);
        assertFalse(audit.contains("argon2id"), "not even the hash: " + audit);
        assertTrue(ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE audit_operation IN"
                + " ('PRINCIPAL_CREATED', 'PRINCIPAL_CREDENTIAL_RESET') AND row_key->>'id' = :id",
                new MapSqlParameterSource("id", user.toString()), Integer.class) >= 2);
    }
}
