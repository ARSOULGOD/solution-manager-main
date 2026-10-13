package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.SmControlMigrationMain;
import com.solace.sm.dp.apply.persistence.SmDataMigrationMain;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The production launcher — the real {@code SolutionManagerApplication}, composed only through
 * {@link SolutionManagerConfiguration}'s imports — proves it boots with the three hierarchy reads
 * wired in ({@code OperationCoverageValidator} is a startup gate: an unclassified handler, a
 * missing {@code HierarchyDirectory} bean, or a CATALOG-shaped resolver on one of these three
 * would refuse to start) and serves them over real HTTP (console-integration.md §5.2 rows 8-10).
 *
 * <p>A SEPARATE Spring context from {@link ProductionAuthenticationIT} / {@link
 * ProductionRateGateAndForwardingIT} — its own container, its own seeded tree, so this class's
 * assertions about exactly which organizations/sub-organizations/environments exist are not at
 * the mercy of what another test class's flow left behind.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HierarchyEndpointsIT {

    private static final String CONSOLE_ORIGIN = "https://console.test";
    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String BOOTSTRAP_USERNAME = "bootstrap-admin";

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static String bootstrapCredential;

    // A second, non-must-rotate local principal, bound at organization scope over the bootstrap
    // organization — seeded directly via ownerJdbc (there is no create-principal endpoint in v1;
    // D11-AMENDMENT-PROPOSAL.md §8's own posture: the hierarchy is installer-seeded, principals and
    // bindings beyond bootstrap-admin are provisioned "through the product's own authenticated
    // path" for a real deployment, direct SQL here for a test).
    private static UUID organizationId;
    private static UUID subOrganizationId;
    private static UUID environmentId;
    private static UUID siblingSubOrganizationId;
    private static UUID siblingEnvironmentId;
    private static UUID otherOrganizationId;
    private static final String OPERATOR_USERNAME = "hierarchy-it-operator";
    private static final String OPERATOR_CREDENTIAL = "hierarchy-it-operator-credential-0001";
    private static final String SUB_ORG_OPERATOR_USERNAME = "hierarchy-it-suborg-operator";
    private static final String SUB_ORG_OPERATOR_CREDENTIAL = "hierarchy-it-suborg-operator-credential-0001";
    private static final String ADMIN_USERNAME = "hierarchy-it-admin";
    private static final String ADMIN_CREDENTIAL = "hierarchy-it-admin-credential-0001";
    private static final String UNBOUND_USERNAME = "hierarchy-it-unbound";
    private static final String UNBOUND_CREDENTIAL = "hierarchy-it-unbound-credential-0001";

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionDatabasesAndSeedTree(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");
        postgres.start();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + postgres.getDatabaseName();

        DataSource superuser = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate superuserJdbc = new JdbcTemplate(superuser);
        runPhase0(superuser, "db/bootstrap/sm_control_phase0.sql");
        runPhase0(superuser, "db/bootstrap/sm_data_phase0.sql");

        String cpMigratorPassword = "cp-migrator-" + UUID.randomUUID();
        String cpRuntimePassword = "cp-runtime-" + UUID.randomUUID();
        String dpMigratorPassword = "dp-migrator-" + UUID.randomUUID();
        String dpRuntimePassword = "dp-runtime-" + UUID.randomUUID();
        superuserJdbc.execute("ALTER ROLE sm_cp_migrator PASSWORD '" + cpMigratorPassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_cp_runtime PASSWORD '" + cpRuntimePassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_dp_migrator PASSWORD '" + dpMigratorPassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_dp_runtime PASSWORD '" + dpRuntimePassword + "'");

        char[] credential = randomCredential();
        bootstrapCredential = new String(credential);
        String bootstrapCredentialHash = new CredentialHasher().hash(credential.clone());

        SmControlMigrationMain.run(url, "sm_cp_migrator", cpMigratorPassword, bootstrapCredentialHash);
        SmDataMigrationMain.run(url, "sm_dp_migrator", dpMigratorPassword);

        ownerJdbc = new NamedParameterJdbcTemplate(superuser);
        seedOperatorAndTree(ownerJdbc);

        TwoSchemaTestDatabase.registerTestMasterKey(registry);   // plan 010 T2.6: the secret store fails closed
        registry.add("sm.control.runtime.url", () -> url);
        registry.add("sm.control.runtime.username", () -> "sm_cp_runtime");
        registry.add("sm.control.runtime.password", () -> cpRuntimePassword);
        registry.add("sm.data.runtime.url", () -> url);
        registry.add("sm.data.runtime.username", () -> "sm_dp_runtime");
        registry.add("sm.data.runtime.password", () -> dpRuntimePassword);
        registry.add("sm.gateway.trusted-origins[0]", () -> CONSOLE_ORIGIN);
        registry.add("sm.gateway.direct-client-connections", () -> true);
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
    }

    /**
     * The tree this suite reads back: the bootstrap organization (from {@code V007}) gets one
     * sub-organization and one environment, a SECOND sub-organization ({@code
     * siblingSubOrganizationId}) that is real but never granted to anyone below organization
     * scope, plus a second, unrelated organization with its own sub-organization — proving
     * cross-organization invisibility needs a REAL other organization, not merely an unused id.
     * {@code hierarchy-it-operator} is bound at ORGANIZATION scope over the bootstrap organization
     * only, so the either-direction rule shows every node below it and nothing under {@code
     * otherOrganizationId}. {@code hierarchy-it-suborg-operator} is bound at SUB-ORGANIZATION scope
     * over {@code subOrganizationId} only, so {@code siblingSubOrganizationId} — real, with its own
     * environment ({@code siblingEnvironmentId}), and under the SAME organization — is invisible
     * to it: this is what lets
     * {@link #operatorScopedBelowOrganizationGetsAnIdenticalNotFoundForASiblingSubOrganizationAsForANonexistentOne}
     * compare a step-1 (visibility) 404 against a step-0 (existence) 404 for the identical route,
     * rather than two step-0 refusals that would pass even if the two checks rendered differently.
     * {@code hierarchy-it-unbound} is bound to {@code organizationId} itself but holds NO scope or
     * role_binding row at all, so listSubOrganizations' OWN step-1 visibility guard (the
     * {@code requireVisible} on the organization scope, distinct from
     * {@code requireOwnOrganization}'s identity check) is what refuses it in
     * {@link #unboundPrincipalGetsAnIdenticalNotFoundForListSubOrganizationsOnTheirOwnOrganizationAsForAForeignOrganization} —
     * completing the byte-identical-404 comparison for listSubOrganizations the way row 5 above
     * completes it for listEnvironments.
     */
    private static void seedOperatorAndTree(NamedParameterJdbcTemplate jdbc) {
        Map<String, Object> bootstrapPrincipal = jdbc.queryForMap(
                "SELECT organization_id FROM sm_control.principal WHERE username = :username",
                Map.of("username", BOOTSTRAP_USERNAME));
        organizationId = (UUID) bootstrapPrincipal.get("organization_id");

        // OR-120: the bootstrap organization and the tree carry real display names.
        jdbc.update("UPDATE sm_control.organization SET name = 'ACME' WHERE organization_id = :organization",
                Map.of("organization", organizationId));
        // The walkthrough captures items[0] of the lists, so they are ordered by name, not id. The ids here put
        // ACME-Sub-1 on the HIGHER id (in PostgreSQL's uuid order, which is the canonical text order), so an id-ordered list would put ACME-Sub-2 first.
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        subOrganizationId = firstId.toString().compareTo(secondId.toString()) > 0 ? firstId : secondId;
        environmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) "
                        + "VALUES (:organization, :unit, 'ACME-Sub-1')",
                new MapSqlParameterSource().addValue("organization", organizationId).addValue("unit", subOrganizationId));
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name) "
                        + "VALUES (:organization, :unit, :environment, 'Dev')",
                new MapSqlParameterSource().addValue("organization", organizationId).addValue("unit", subOrganizationId)
                        .addValue("environment", environmentId));

        siblingSubOrganizationId = subOrganizationId.equals(firstId) ? secondId : firstId;
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) "
                        + "VALUES (:organization, :unit, 'ACME-Sub-2')",
                new MapSqlParameterSource().addValue("organization", organizationId)
                        .addValue("unit", siblingSubOrganizationId));
        // Gives environmentIdsOf's unit filter something to leak: without a row here, a query for
        // subOrganizationId's environments would return the identical result whether or not that
        // filter is applied, so OPERATOR_USERNAME's org-scoped read below (which makes both units
        // visible) could not observe a regression that dropped it.
        siblingEnvironmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name) "
                        + "VALUES (:organization, :unit, :environment, 'UAT')",
                new MapSqlParameterSource().addValue("organization", organizationId)
                        .addValue("unit", siblingSubOrganizationId).addValue("environment", siblingEnvironmentId));

        otherOrganizationId = UUID.randomUUID();
        UUID otherUnitId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:organization, 'fixture-' || gen_random_uuid()::text)",
                Map.of("organization", otherOrganizationId));
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) "
                        + "VALUES (:organization, :unit, 'fixture-' || gen_random_uuid()::text)",
                new MapSqlParameterSource().addValue("organization", otherOrganizationId).addValue("unit", otherUnitId));

        UUID operatorId = UUID.randomUUID();
        String operatorCredentialHash = new CredentialHasher().hash(OPERATOR_CREDENTIAL.toCharArray());
        jdbc.update("INSERT INTO sm_control.principal "
                        + "(id, organization_id, source, username, credential_hash, must_rotate) "
                        + "VALUES (:id, :organization, 'local', :username, :hash, false)",
                new MapSqlParameterSource().addValue("id", operatorId).addValue("organization", organizationId)
                        .addValue("username", OPERATOR_USERNAME).addValue("hash", operatorCredentialHash));

        UUID scopeId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id) "
                        + "VALUES (:scopeId, 'organization', :organization)",
                new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("organization", organizationId));
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        // 'Viewer' — see V003__role_and_binding.sql's built-in seed.
                        + "(SELECT role_id FROM sm_control.role WHERE name = 'Viewer'), :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", operatorId)
                        .addValue("scopeId", scopeId));

        UUID subOrgOperatorId = UUID.randomUUID();
        String subOrgOperatorCredentialHash = new CredentialHasher().hash(SUB_ORG_OPERATOR_CREDENTIAL.toCharArray());
        jdbc.update("INSERT INTO sm_control.principal "
                        + "(id, organization_id, source, username, credential_hash, must_rotate) "
                        + "VALUES (:id, :organization, 'local', :username, :hash, false)",
                new MapSqlParameterSource().addValue("id", subOrgOperatorId).addValue("organization", organizationId)
                        .addValue("username", SUB_ORG_OPERATOR_USERNAME).addValue("hash", subOrgOperatorCredentialHash));

        UUID subOrgScopeId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id) "
                        + "VALUES (:scopeId, 'organization_unit', :organization, :unit)",
                new MapSqlParameterSource().addValue("scopeId", subOrgScopeId).addValue("organization", organizationId)
                        .addValue("unit", subOrganizationId));
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        + "(SELECT role_id FROM sm_control.role WHERE name = 'Viewer'), :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID())
                        .addValue("principalId", subOrgOperatorId).addValue("scopeId", subOrgScopeId));

        // OR-116: an installation-scope Administrator (the install scope row V007 already created),
        // not must-rotate — the "admin sees all" case for Identity.organizations.
        UUID adminId = UUID.randomUUID();
        String adminCredentialHash = new CredentialHasher().hash(ADMIN_CREDENTIAL.toCharArray());
        jdbc.update("INSERT INTO sm_control.principal "
                        + "(id, organization_id, source, username, credential_hash, must_rotate) "
                        + "VALUES (:id, :organization, 'local', :username, :hash, false)",
                new MapSqlParameterSource().addValue("id", adminId).addValue("organization", organizationId)
                        .addValue("username", ADMIN_USERNAME).addValue("hash", adminCredentialHash));
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        + "(SELECT role_id FROM sm_control.role WHERE name = 'Administrator'), "
                        + "(SELECT scope_id FROM sm_control.scope WHERE kind = 'install'))",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", adminId));

        // Deliberately no scope / role_binding row for this principal — it exercises
        // listSubOrganizations' OWN step-1 visibility guard (requireVisible on the organization
        // scope itself), which can only fail when a principal holds no grant anywhere at all.
        UUID unboundId = UUID.randomUUID();
        String unboundCredentialHash = new CredentialHasher().hash(UNBOUND_CREDENTIAL.toCharArray());
        jdbc.update("INSERT INTO sm_control.principal "
                        + "(id, organization_id, source, username, credential_hash, must_rotate) "
                        + "VALUES (:id, :organization, 'local', :username, :hash, false)",
                new MapSqlParameterSource().addValue("id", unboundId).addValue("organization", organizationId)
                        .addValue("username", UNBOUND_USERNAME).addValue("hash", unboundCredentialHash));
    }

    private static void runPhase0(DataSource superuser, String classpathResource) {
        try (Connection connection = superuser.getConnection()) {
            new ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource(classpathResource)).populate(connection);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("phase 0 (" + classpathResource + ") failed", e);
        }
    }

    private static char[] randomCredential() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    @Test
    @Order(1)
    void listOrganizationsWithNoSessionIsUnauthenticated() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/cp/hierarchy/organizations"))
                .GET().build());
        assertEquals(401, response.statusCode(), response.body());
    }

    @Test
    @Order(2)
    void bootstrapAdminUnderMustRotateIsRefusedOnAllThreeReads() throws Exception {
        String session = loginAndGetSessionToken(BOOTSTRAP_USERNAME, bootstrapCredential);

        assertEquals(428, get("/api/cp/hierarchy/organizations", session).statusCode());
        assertEquals(428, get("/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs", session)
                .statusCode());
        assertEquals(428, get("/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs/"
                + subOrganizationId + "/environments", session).statusCode());
    }

    @Test
    @Order(3)
    void operatorSeesTheirOwnOrganizationAndEverythingBelowIt() throws Exception {
        String session = loginAndGetSessionToken(OPERATOR_USERNAME, OPERATOR_CREDENTIAL);

        JsonNode organizations = MAPPER.readTree(get("/api/cp/hierarchy/organizations", session).body());
        assertTrue(organizations.get("stateVersion") == null,
                "ScopeNodeList carries no stateVersion (OR-38) — a non-entity response is exempt");
        assertEquals(1, organizations.get("items").size());
        assertEquals(organizationId.toString(), organizations.at("/items/0/id").asText());
        assertEquals("ACME", organizations.at("/items/0/name").asText());

        // ORGANIZATION-scoped: the either-direction rule makes every sub-organization below it
        // visible, siblingSubOrganizationId included — this principal's grant is not narrowed to
        // subOrganizationId the way SUB_ORG_OPERATOR_USERNAME's is (see Order(5)/Order(6) below).
        JsonNode subOrgs = MAPPER.readTree(
                get("/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs", session).body());
        assertEquals(2, subOrgs.get("items").size());
        java.util.Set<String> subOrgIds = new java.util.HashSet<>();
        subOrgs.get("items").forEach(item -> subOrgIds.add(item.get("id").asText()));
        assertEquals(java.util.Set.of(subOrganizationId.toString(), siblingSubOrganizationId.toString()), subOrgIds);
        java.util.Set<String> subOrgNames = new java.util.HashSet<>();
        subOrgs.get("items").forEach(item -> subOrgNames.add(item.get("name").asText()));
        assertEquals(java.util.Set.of("ACME-Sub-1", "ACME-Sub-2"), subOrgNames);
        assertEquals("ACME-Sub-1", subOrgs.at("/items/0/name").asText(), "lists are ordered by name: items[0] is ACME-Sub-1");
        assertEquals(subOrganizationId.toString(), subOrgs.at("/items/0/id").asText());

        JsonNode environments = MAPPER.readTree(get("/api/cp/hierarchy/organizations/" + organizationId
                + "/sub-orgs/" + subOrganizationId + "/environments", session).body());
        assertEquals(1, environments.get("items").size());
        assertEquals(environmentId.toString(), environments.at("/items/0/id").asText());
        assertEquals("Dev", environments.at("/items/0/name").asText());

        // This principal's grant is ORGANIZATION-scoped, so siblingSubOrganizationId is ALSO
        // visible to it — the only thing standing between the two calls above/below is
        // environmentIdsOf's own unit filter, exercised here over real HTTP the same way
        // HierarchyDirectoryIT.anOrganizationScopedGrantListingOneUnitsEnvironmentsExcludesASiblingUnitsEnvironment
        // exercises it in-process: a leak of that filter would make this assertion see 2 items
        // (environmentId AND siblingEnvironmentId) instead of just siblingEnvironmentId.
        JsonNode siblingEnvironments = MAPPER.readTree(get("/api/cp/hierarchy/organizations/" + organizationId
                + "/sub-orgs/" + siblingSubOrganizationId + "/environments", session).body());
        assertEquals(1, siblingEnvironments.get("items").size());
        assertEquals(siblingEnvironmentId.toString(), siblingEnvironments.at("/items/0/id").asText());
        assertEquals("UAT", siblingEnvironments.at("/items/0/name").asText());
    }

    @Test
    @Order(4)
    void operatorGetsAnIdenticalNotFoundForAnotherOrganizationAsForANonexistentOne() throws Exception {
        String session = loginAndGetSessionToken(OPERATOR_USERNAME, OPERATOR_CREDENTIAL);

        HttpResponse<String> realOtherOrganization =
                get("/api/cp/hierarchy/organizations/" + otherOrganizationId + "/sub-orgs", session);
        HttpResponse<String> nonexistentOrganization =
                get("/api/cp/hierarchy/organizations/" + UUID.randomUUID() + "/sub-orgs", session);

        assertEquals(404, realOtherOrganization.statusCode());
        assertEquals(404, nonexistentOrganization.statusCode());
        assertEquals(withoutCorrelationId(MAPPER.readTree(nonexistentOrganization.body())),
                withoutCorrelationId(MAPPER.readTree(realOtherOrganization.body())),
                "an out-of-organization sub-organization and a nonexistent one must be byte-identical "
                        + "apart from correlationId (D11-AMENDMENT-PROPOSAL.md §3.4)");
    }

    @Test
    @Order(5)
    void operatorScopedBelowOrganizationGetsAnIdenticalNotFoundForASiblingSubOrganizationAsForANonexistentOne()
            throws Exception {
        String session = loginAndGetSessionToken(SUB_ORG_OPERATOR_USERNAME, SUB_ORG_OPERATOR_CREDENTIAL);

        // siblingSubOrganizationId is REAL and under the caller's own organization — this hits
        // listEnvironments' step-1 visibility guard (requireVisible), not step-0 existence. A
        // nonexistent unit id instead hits step-0 (subOrganizationExists). Comparing the two
        // bodies is what proves the two DIFFERENT guards render the identical 404 shape — the
        // existing otherOrganizationId/random-id pair below both take the same
        // requireOwnOrganization branch, so it cannot make this comparison on its own.
        HttpResponse<String> realSiblingSubOrganization = get("/api/cp/hierarchy/organizations/" + organizationId
                + "/sub-orgs/" + siblingSubOrganizationId + "/environments", session);
        HttpResponse<String> nonexistentSubOrganization = get("/api/cp/hierarchy/organizations/" + organizationId
                + "/sub-orgs/" + UUID.randomUUID() + "/environments", session);

        assertEquals(404, realSiblingSubOrganization.statusCode());
        assertEquals(404, nonexistentSubOrganization.statusCode());
        assertEquals(withoutCorrelationId(MAPPER.readTree(nonexistentSubOrganization.body())),
                withoutCorrelationId(MAPPER.readTree(realSiblingSubOrganization.body())),
                "an existing-but-not-visible sub-organization and a nonexistent one must be "
                        + "byte-identical apart from correlationId (D11-AMENDMENT-PROPOSAL.md §3.4)");

        // Also confirms this principal's OWN sub-organization is still visible — the 404 above is
        // scope, not a broken login.
        JsonNode environments = MAPPER.readTree(get("/api/cp/hierarchy/organizations/" + organizationId
                + "/sub-orgs/" + subOrganizationId + "/environments", session).body());
        assertEquals(1, environments.get("items").size());
        assertEquals(environmentId.toString(), environments.at("/items/0/id").asText());
    }

    @Test
    @Order(6)
    void unboundPrincipalGetsAnIdenticalNotFoundForListSubOrganizationsOnTheirOwnOrganizationAsForAForeignOrganization()
            throws Exception {
        String session = loginAndGetSessionToken(UNBOUND_USERNAME, UNBOUND_CREDENTIAL);

        // organizationId IS this principal's own organization, so requireOwnOrganization (the
        // identity check) passes — this hits listSubOrganizations' OWN step-1 visibility guard
        // (requireVisible on the organization scope), not the identity check row 4 above exercises.
        // otherOrganizationId is a real, different organization, so it fails the identity check
        // instead. Comparing the two bodies proves these two DIFFERENT guards, on the SAME route,
        // render an identical 404 — closing the gap row 4 leaves open (it only compares two
        // identity-check failures against each other).
        HttpResponse<String> ownOrganizationNoGrant =
                get("/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs", session);
        HttpResponse<String> foreignOrganization =
                get("/api/cp/hierarchy/organizations/" + otherOrganizationId + "/sub-orgs", session);

        assertEquals(404, ownOrganizationNoGrant.statusCode());
        assertEquals(404, foreignOrganization.statusCode());
        assertEquals(withoutCorrelationId(MAPPER.readTree(foreignOrganization.body())),
                withoutCorrelationId(MAPPER.readTree(ownOrganizationNoGrant.body())),
                "a real organization the caller holds no grant on and a foreign organization must be "
                        + "byte-identical apart from correlationId (D11-AMENDMENT-PROPOSAL.md §3.4)");
    }

    // ---- OR-116: Identity.organizations ----------------------------------------------------

    @Test
    @Order(7)
    void identityOfAnOrganizationScopedOperatorCarriesItsOrganizationAndEverySubOrganization() throws Exception {
        for (JsonNode identity : loginAndIdentity(OPERATOR_USERNAME, OPERATOR_CREDENTIAL)) {
            JsonNode organizations = identity.get("organizations");
            assertEquals(1, organizations.size());
            assertEquals(organizationId.toString(), organizations.at("/0/id").asText());
            assertEquals("ACME", organizations.at("/0/name").asText(), "Identity.organizations carries ACME's real name");
            assertEquals(java.util.Set.of(subOrganizationId.toString(), siblingSubOrganizationId.toString()),
                    idsOf(organizations.at("/0/subOrganizations")));
            java.util.Set<String> names = new java.util.HashSet<>();
            organizations.at("/0/subOrganizations").forEach(sub -> names.add(sub.get("name").asText()));
            assertEquals(java.util.Set.of("ACME-Sub-1", "ACME-Sub-2"), names);
        }
    }

    @Test
    @Order(8)
    void identityOfASubOrganizationScopedOperatorCarriesOnlyItsOrganizationAndThatSubOrganization() throws Exception {
        for (JsonNode identity : loginAndIdentity(SUB_ORG_OPERATOR_USERNAME, SUB_ORG_OPERATOR_CREDENTIAL)) {
            JsonNode organizations = identity.get("organizations");
            assertEquals(1, organizations.size());
            assertEquals(organizationId.toString(), organizations.at("/0/id").asText());
            assertEquals(java.util.Set.of(subOrganizationId.toString()),
                    idsOf(organizations.at("/0/subOrganizations")),
                    "the sibling sub-organization (real, same organization) must not appear");
            // Never another organization, even though one really exists.
            assertTrue(!organizations.toString().contains(otherOrganizationId.toString()));
        }
    }

    @Test
    @Order(9)
    void identityOfAnInstallScopedAdministratorSeesEverySubOrganizationOfItsOwnOrganization() throws Exception {
        for (JsonNode identity : loginAndIdentity(ADMIN_USERNAME, ADMIN_CREDENTIAL)) {
            JsonNode organizations = identity.get("organizations");
            assertEquals(1, organizations.size(), "an administrator sees its own organization, never another");
            assertEquals(organizationId.toString(), organizations.at("/0/id").asText());
            assertEquals(java.util.Set.of(subOrganizationId.toString(), siblingSubOrganizationId.toString()),
                    idsOf(organizations.at("/0/subOrganizations")));
        }
    }

    @Test
    @Order(10)
    void identityOfACallerWithNoBindingsCarriesAnEmptyArray() throws Exception {
        for (JsonNode identity : loginAndIdentity(UNBOUND_USERNAME, UNBOUND_CREDENTIAL)) {
            assertTrue(identity.has("organizations"));
            assertEquals(0, identity.get("organizations").size());
        }
    }

    @Test
    @Order(11)
    void identityWhileMustRotateIsStillAnsweredButCarriesNoOrganizations() throws Exception {
        for (JsonNode identity : loginAndIdentity(BOOTSTRAP_USERNAME, bootstrapCredential)) {
            assertTrue(identity.get("mustRotate").asBoolean());
            assertTrue(identity.get("organizations").isArray());
            assertEquals(0, identity.get("organizations").size(),
                    "the hierarchy lists answer 428 until rotation (see bootstrapAdminUnderMustRotate...), "
                            + "so the identity field is empty too");
        }
    }

    @Test
    @Order(12)
    void theIdentityFieldAndTheHierarchyListsAgreeForEveryPrincipal() throws Exception {
        String[][] principals = {
                {OPERATOR_USERNAME, OPERATOR_CREDENTIAL},
                {SUB_ORG_OPERATOR_USERNAME, SUB_ORG_OPERATOR_CREDENTIAL},
                {ADMIN_USERNAME, ADMIN_CREDENTIAL},
                {UNBOUND_USERNAME, UNBOUND_CREDENTIAL}};
        for (String[] principal : principals) {
            String session = loginAndGetSessionToken(principal[0], principal[1]);

            // Rebuild the tree the way a client would from the two list operations.
            com.fasterxml.jackson.databind.node.ArrayNode fromLists = MAPPER.createArrayNode();
            for (JsonNode organization : MAPPER.readTree(get("/api/cp/hierarchy/organizations", session).body())
                    .get("items")) {
                com.fasterxml.jackson.databind.node.ObjectNode entry = MAPPER.createObjectNode();
                entry.put("id", organization.get("id").asText());
                entry.put("name", organization.get("name").asText());
                entry.set("subOrganizations", MAPPER.readTree(get("/api/cp/hierarchy/organizations/"
                        + organization.get("id").asText() + "/sub-orgs", session).body()).get("items"));
                fromLists.add(entry);
            }

            HttpResponse<String> identity = get("/api/cp/auth/identity", session);
            assertEquals(200, identity.statusCode(), identity.body());
            assertEquals(sortedById(fromLists), sortedById(MAPPER.readTree(identity.body()).get("organizations")),
                    principal[0] + ": Identity.organizations must equal what listOrganizations + "
                            + "listSubOrganizations return (ids and names)");
        }
    }

    /**
     * The client contract (and the Postman README) says: a must-rotate principal gets {@code organizations: []}, rotates, and calls
     * getIdentity AGAIN on the same session to receive the hierarchy. That holds only while {@code Session.mustRotate} is re-read from
     * {@code principal.must_rotate} on every request; a value captured at login would leave the console an empty hierarchy for the
     * session's life. Rotation is one-way, so this test runs LAST and nothing after it may use the bootstrap credential.
     */
    @Test
    @Order(13)
    void aRotatedBootstrapAdminSeesTheHierarchyOnTheSameSessionWithoutLoggingInAgain() throws Exception {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(BOOTSTRAP_USERNAME, bootstrapCredential)))
                .build());
        assertEquals(200, login.statusCode(), login.body());
        JsonNode loginBodyJson = MAPPER.readTree(login.body());
        assertTrue(loginBodyJson.get("mustRotate").asBoolean());
        assertEquals(0, loginBodyJson.get("organizations").size(), "must-rotate: the login answer carries no hierarchy");
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
        String value = cookie.substring((SESSION_COOKIE + "=").length());
        String token = value.contains(";") ? value.substring(0, value.indexOf(';')) : value;
        String csrf = loginBodyJson.get("csrfToken").asText();

        JsonNode before = MAPPER.readTree(get("/api/cp/auth/identity", token).body());
        assertTrue(before.get("mustRotate").asBoolean());
        assertEquals(0, before.get("organizations").size());

        String newCredential = "hierarchy-it-rotated-bootstrap-credential-0001";
        HttpResponse<String> rotate = send(HttpRequest.newBuilder(uri("/api/cp/auth/credential"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("Cookie", SESSION_COOKIE + "=" + token + "; __Host-SM_CSRF=" + csrf)
                .header("X-SM-CSRF", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"currentCredential\":\"" + bootstrapCredential
                        + "\",\"newCredential\":\"" + newCredential + "\"}"))
                .build());
        assertEquals(204, rotate.statusCode(), rotate.body());

        // The SAME session, no new login: getIdentity must now carry the hierarchy.
        HttpResponse<String> identityResponse = get("/api/cp/auth/identity", token);
        assertEquals(200, identityResponse.statusCode(), identityResponse.body());
        JsonNode identity = MAPPER.readTree(identityResponse.body());
        assertTrue(!identity.get("mustRotate").asBoolean(), "the rotating session's identity shows mustRotate=false at once");
        JsonNode organizations = identity.get("organizations");
        assertEquals(1, organizations.size(), "the install-scope administrator sees its own organization");
        assertEquals(organizationId.toString(), organizations.at("/0/id").asText());
        assertEquals(java.util.Set.of(subOrganizationId.toString(), siblingSubOrganizationId.toString()),
                idsOf(organizations.at("/0/subOrganizations")));

        // and it equals what the two list operations return on that session
        JsonNode listedOrganizations = MAPPER.readTree(get("/api/cp/hierarchy/organizations", token).body()).get("items");
        assertEquals(1, listedOrganizations.size());
        assertEquals(organizationId.toString(), listedOrganizations.at("/0/id").asText());
        assertEquals(idsOf(organizations.at("/0/subOrganizations")),
                idsOf(MAPPER.readTree(get("/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs", token).body()).get("items")));
    }

    /** The login response body followed by the getIdentity body for the same fresh session. */
    private java.util.List<JsonNode> loginAndIdentity(String username, String credential) throws Exception {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(username, credential)))
                .build());
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
        String value = cookie.substring((SESSION_COOKIE + "=").length());
        String token = value.contains(";") ? value.substring(0, value.indexOf(';')) : value;
        HttpResponse<String> identity = get("/api/cp/auth/identity", token);
        assertEquals(200, identity.statusCode(), identity.body());
        return java.util.List.of(MAPPER.readTree(login.body()), MAPPER.readTree(identity.body()));
    }

    private static java.util.Set<String> idsOf(JsonNode array) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        array.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    /** Order-insensitive comparison: organizations and their sub-organizations sorted by id. */
    private static JsonNode sortedById(JsonNode organizations) {
        com.fasterxml.jackson.databind.node.ArrayNode sorted = MAPPER.createArrayNode();
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        organizations.forEach(organization -> {
            com.fasterxml.jackson.databind.node.ObjectNode copy = MAPPER.createObjectNode();
            copy.put("id", organization.get("id").asText());
            copy.put("name", organization.get("name").asText());
            java.util.List<JsonNode> subs = new java.util.ArrayList<>();
            organization.get("subOrganizations").forEach(subs::add);
            subs.sort(java.util.Comparator.comparing(node -> node.get("id").asText()));
            copy.set("subOrganizations", MAPPER.createArrayNode().addAll(subs));
            list.add(copy);
        });
        list.sort(java.util.Comparator.comparing(node -> node.get("id").asText()));
        return sorted.addAll(list);
    }

    private static JsonNode withoutCorrelationId(JsonNode node) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                .deepCopy();
        copy.remove("correlationId");
        return copy;
    }

    // ---- HTTP helpers -----------------------------------------------------------------------

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private URI uri(String path) {
        return URI.create(baseUrl() + path);
    }

    private HttpResponse<String> get(String path, String sessionToken) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Cookie", SESSION_COOKIE + "=" + sessionToken)
                .GET().build());
    }

    private String loginAndGetSessionToken(String username, String credential) throws IOException, InterruptedException {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(username, credential)))
                .build());
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + SESSION_COOKIE + " Set-Cookie on " + login));
        String value = cookie.substring((SESSION_COOKIE + "=").length());
        int semicolon = value.indexOf(';');
        String token = semicolon < 0 ? value : value.substring(0, semicolon);
        assertNotNull(token);
        assertTrue(!token.isBlank());
        return token;
    }

    private static String loginBody(String username, String credential) {
        return "{\"organization\":\"" + organizationId + "\",\"username\":\"" + username
                + "\",\"credential\":\"" + credential + "\"}";
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
