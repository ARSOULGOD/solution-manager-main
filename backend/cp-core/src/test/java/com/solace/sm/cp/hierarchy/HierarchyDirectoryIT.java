package com.solace.sm.cp.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.hierarchy.VisibleOrganization;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcHierarchyTreeRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Proves console-integration.md §5.2 rows 8-10 and {@code D11-AMENDMENT-PROPOSAL.md} §3.4 against
 * a real PostgreSQL 14 {@code sm_control} schema — the either-direction prefix rule, the 404
 * collapse of existence and visibility, and cross-organization invisibility.
 */
class HierarchyDirectoryIT {

    private NamedParameterJdbcTemplate jdbc;
    private ScopeVisibleHierarchyDirectory directory;

    private UUID organization;
    private UUID otherOrganization;
    private UUID unit;
    private UUID siblingUnit;
    private UUID development;
    private UUID production;
    private UUID siblingUnitEnvironment;
    private PrincipalRef principal;
    /** Organization names are unique install-wide (V015) and the database is shared across tests. */
    private String acme;

    @BeforeEach
    void setUp() {
        // A DISTINCT cache key from AuthorizationIT's "006": that class also grants
        // ScopePath.INSTALL, and the `scope` table's scope_one_install unique index means only ONE
        // such row can ever exist — sharing AuthorizationIT's container would collide with it (or
        // vice versa) since neither class's @BeforeEach purges `scope`, only `role_binding`.
        jdbc = SmControlPostgres.jdbcAtVersion("hierarchy-directory-it", SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.role_binding");
        // Two tests below each grant ScopePath.INSTALL, and scope_one_install allows only one such
        // row to exist at a time — role_binding is the only referrer, already cleared above.
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.scope");
        organization = UUID.randomUUID();
        otherOrganization = UUID.randomUUID();
        unit = UUID.randomUUID();
        siblingUnit = UUID.randomUUID();
        development = UUID.randomUUID();
        production = UUID.randomUUID();
        siblingUnitEnvironment = UUID.randomUUID();
        acme = "ACME-" + organization;
        seedOrganization(organization, acme);
        seedOrganization(otherOrganization, "Other Org " + otherOrganization);
        seedUnit(organization, unit, "ACME-Sub-1");
        seedUnit(organization, siblingUnit, "ACME-Sub-2");
        seedEnvironment(organization, unit, development, "Dev");
        seedEnvironment(organization, unit, production, "Prod");
        // siblingUnit gets its own environment so a leak of environmentIdsOf's unit filter (it
        // would then return every environment in the ORGANIZATION, not just this unit's) is
        // something the tests below can actually observe: with no environment under siblingUnit,
        // an unfiltered query returns exactly the same rows a correctly filtered one does, and no
        // assertion below would notice.
        seedEnvironment(organization, siblingUnit, siblingUnitEnvironment, "UAT");
        principal = new PrincipalRef(seedPrincipal(organization));
        directory = new ScopeVisibleHierarchyDirectory(
                new JdbcAuthorizationGrantRepository(jdbc), new JdbcHierarchyTreeRepository(jdbc));
    }

    private Session sessionFor(UUID organizationId) {
        Instant now = Instant.now();
        return new Session(new SessionId(UUID.randomUUID()), principal, organizationId, false,
                now, now, now.plusSeconds(3600), now.plusSeconds(86_400));
    }

    // ── listOrganizations ───────────────────────────────────────────────────────────────────

    @Test
    void anOrganizationScopedGrantSeesItsOwnOrganization() {
        grant("Viewer", ScopePath.organization(organization));

        assertThat(directory.listOrganizations(sessionFor(organization)))
                .containsExactly(new ScopeNode(organization, acme));
    }

    @Test
    void anEnvironmentScopedGrantSeesTheAncestorOrganization() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(directory.listOrganizations(sessionFor(organization)))
                .containsExactly(new ScopeNode(organization, acme));
    }

    @Test
    void aPrincipalWithNoGrantAtAllSeesNoOrganization() {
        assertThat(directory.listOrganizations(sessionFor(organization))).isEmpty();
    }

    // ── listSubOrganizations ────────────────────────────────────────────────────────────────

    @Test
    void anOrganizationScopedGrantSeesEverySubOrganizationBelowIt() {
        grant("Viewer", ScopePath.organization(organization));

        assertThat(directory.listSubOrganizations(sessionFor(organization), organization))
                .extracting(ScopeNode::id).containsExactlyInAnyOrder(unit, siblingUnit);
    }

    @Test
    void listsCarryTheRealDisplayNamesNotTheIds() {
        grant("Viewer", ScopePath.organization(organization));

        assertThat(directory.listSubOrganizations(sessionFor(organization), organization))
                .containsExactlyInAnyOrder(new ScopeNode(unit, "ACME-Sub-1"), new ScopeNode(siblingUnit, "ACME-Sub-2"));
        assertThat(directory.listEnvironments(sessionFor(organization), organization, unit))
                .containsExactlyInAnyOrder(new ScopeNode(development, "Dev"), new ScopeNode(production, "Prod"));
        assertThat(directory.listEnvironments(sessionFor(organization), organization, siblingUnit))
                .containsExactly(new ScopeNode(siblingUnitEnvironment, "UAT"));
    }

    /**
     * The lists are ordered by display name (byte-wise, locale-independent) then id (a tie-break that sibling-name uniqueness makes unreachable), never by id: the
     * OR-118 Postman walkthrough captures {@code items[0]}, so the order must put ACME-Sub-1 and Dev first. Ids here
     * are chosen so that id order is the REVERSE of name order.
     */
    @Test
    void listsAreOrderedByNameThenIdNotById() {
        UUID org = UUID.randomUUID();
        seedOrganization(org, "Ordering Org " + org);
        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        sortAsPostgresDoes(ids); // ids[0] < ids[1] < ids[2] < ids[3] in PostgreSQL's uuid order
        // Name order (C collation): ACME-Sub-1 < ACME-Sub-2 < Zeta < alpha. Id order is the reverse.
        seedUnit(org, ids[3], "ACME-Sub-1");
        seedUnit(org, ids[2], "ACME-Sub-2");
        seedUnit(org, ids[1], "Zeta");
        seedUnit(org, ids[0], "alpha");
        UUID firstUnit = ids[3];
        UUID[] envIds = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        sortAsPostgresDoes(envIds);
        seedEnvironment(org, firstUnit, envIds[2], "Dev");
        seedEnvironment(org, firstUnit, envIds[1], "Prod");
        seedEnvironment(org, firstUnit, envIds[0], "UAT");
        grant("Viewer", ScopePath.organization(org));

        assertThat(directory.listSubOrganizations(sessionFor(org), org)).extracting(ScopeNode::name)
                .containsExactly("ACME-Sub-1", "ACME-Sub-2", "Zeta", "alpha");
        assertThat(directory.listEnvironments(sessionFor(org), org, firstUnit))
                .containsExactly(new ScopeNode(envIds[2], "Dev"), new ScopeNode(envIds[1], "Prod"),
                        new ScopeNode(envIds[0], "UAT"));
        List<VisibleOrganization> visible = directory.visibleOrganizations(sessionFor(org));
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).subOrganizations()).extracting(ScopeNode::name)
                .containsExactly("ACME-Sub-1", "ACME-Sub-2", "Zeta", "alpha");
    }

    @Test
    void anEnvironmentScopedGrantSeesItsAncestorUnitButNotASiblingUnit() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(directory.listSubOrganizations(sessionFor(organization), organization))
                .extracting(ScopeNode::id).containsExactly(unit);
    }

    @Test
    void listSubOrganizationsOnAnotherOrganizationIs404() {
        grant("Viewer", ScopePath.organization(organization));

        assertThatThrownBy(() -> directory.listSubOrganizations(sessionFor(organization), otherOrganization))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listSubOrganizationsWithNoVisibleGrantIs404EvenThoughTheOrganizationExists() {
        // No grant at all: the organization itself is not visible, so its own children answer 404,
        // not an empty list — existence and visibility collapse to one answer (D11 §3.4).
        assertThatThrownBy(() -> directory.listSubOrganizations(sessionFor(organization), organization))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── listEnvironments ────────────────────────────────────────────────────────────────────

    @Test
    void aUnitScopedGrantSeesEveryEnvironmentBelowIt() {
        grant("Viewer", ScopePath.subOrganization(organization, unit));

        assertThat(directory.listEnvironments(sessionFor(organization), organization, unit))
                .extracting(ScopeNode::id).containsExactlyInAnyOrder(development, production);
    }

    @Test
    void anOrganizationScopedGrantListingOneUnitsEnvironmentsExcludesASiblingUnitsEnvironment() {
        // A single ORGANIZATION-scoped grant makes every environment in the organization visible
        // (the either-direction rule), so nothing in ScopeVisibility filters siblingUnitEnvironment
        // out of either call below — the ONLY thing that can is environmentIdsOf's own
        // "AND organization_unit_id = :organizationUnitId" filter. This isolates that repository
        // guard from the visibility layer, unlike aUnitScopedGrantSeesEveryEnvironmentBelowIt above,
        // where a unit-scoped grant would ALSO hide a leaked sibling environment via visibility —
        // so a regression here cannot hide behind "the visibility check happened to catch it".
        grant("Viewer", ScopePath.organization(organization));

        assertThat(directory.listEnvironments(sessionFor(organization), organization, unit))
                .extracting(ScopeNode::id).containsExactlyInAnyOrder(development, production);
        assertThat(directory.listEnvironments(sessionFor(organization), organization, siblingUnit))
                .extracting(ScopeNode::id).containsExactly(siblingUnitEnvironment);
    }

    @Test
    void anEnvironmentScopedGrantSeesOnlyItsOwnEnvironmentNotASibling() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(directory.listEnvironments(sessionFor(organization), organization, unit))
                .extracting(ScopeNode::id).containsExactly(development);
    }

    @Test
    void anInstallScopedGrantSeesEveryEnvironment() {
        grant("Administrator", ScopePath.INSTALL);

        assertThat(directory.listEnvironments(sessionFor(organization), organization, unit))
                .extracting(ScopeNode::id).containsExactlyInAnyOrder(development, production);
    }

    @Test
    void anEnvironmentScopedGrantCannotListEnvironmentsOfASiblingUnitEvenThoughItExists() {
        // siblingUnit is real (seeded in setUp, under the SAME organization) and has its own
        // environment (siblingUnitEnvironment), but this principal's only grant is under `unit`.
        // This exercises
        // listEnvironments' OWN step-1 visibility guard (requireVisible on the sub-organization
        // scope) — distinct from every other listEnvironments test, which either grants a scope
        // that already includes the sub-organization it queries, or queries an id that fails the
        // step-0 existence/coherence check first. Deleting that guard would turn this 404 into a
        // 200 with an empty item list, leaking that siblingUnit exists (D11 §3.4).
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThatThrownBy(() -> directory.listEnvironments(sessionFor(organization), organization, siblingUnit))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aNestedSubOrganizationRowIsInvisibleToListEnvironmentsJustAsItIsToListSubOrganizations() {
        // parent_unit_id is nullable and never populated in v1, but the column and its FK already
        // exist (V001__scope.sql) — this seeds the one future row shape that would silently surface
        // at the wrong level if EITHER query's defensive "AND parent_unit_id IS NULL" filter were
        // missing: JdbcHierarchyTreeRepository applies it to both subOrganizationIdsOf (used by
        // listSubOrganizations) and subOrganizationExists (used by listEnvironments' step 0) — this
        // test proves they agree, not just that one of them filters.
        UUID nestedUnit = UUID.randomUUID();
        seedNestedUnit(organization, unit, nestedUnit);
        grant("Viewer", ScopePath.organization(organization));

        assertThat(directory.listSubOrganizations(sessionFor(organization), organization))
                .extracting(ScopeNode::id).doesNotContain(nestedUnit);

        assertThatThrownBy(() -> directory.listEnvironments(sessionFor(organization), organization, nestedUnit))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listEnvironmentsForANonexistentSubOrganizationIs404() {
        grant("Viewer", ScopePath.organization(organization));

        assertThatThrownBy(
                () -> directory.listEnvironments(sessionFor(organization), organization, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listEnvironmentsForASubOrganizationOfAnotherOrganizationIs404IdenticallyToNonexistent() {
        UUID otherUnit = UUID.randomUUID();
        seedUnit(otherOrganization, otherUnit, "Other-Sub");
        grant("Viewer", ScopePath.organization(organization));

        // otherUnit is real, but not under `organization` — the coherence check in step 0 must
        // refuse it exactly like a random id, not distinguish "exists elsewhere" from "does not exist".
        assertThatThrownBy(() -> directory.listEnvironments(sessionFor(organization), organization, otherUnit))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossOrganizationPathParameterIsRefusedAgainstARealHeldGrant() {
        // This asserts the RESULT only — that an INSTALL-scoped grant (which would make every
        // organization visible) still does not save `otherOrganization` from the identity check.
        // It does NOT prove the ordering its old name claimed: moving requireOwnOrganization below
        // the grant read would not change this outcome, since the grant is real here. The ordering
        // itself — refused BEFORE a grant is ever read — is proven without a database by
        // ScopeVisibleHierarchyDirectoryOrderingTest, using fakes that throw if touched.
        grant("Administrator", ScopePath.INSTALL);

        // The session's own organization is `organization`; naming `otherOrganization` in the path
        // must be refused purely on identity comparison — I5, never trusting the path parameter.
        assertThatThrownBy(() -> directory.listSubOrganizations(sessionFor(organization), otherOrganization))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── visibleOrganizations (OR-116: Identity.organizations) ───────────────────────────────

    @Test
    void anOrganizationScopedGrantSeesItsOrganizationAndEverySubOrganizationBelowIt() {
        grant("Viewer", ScopePath.organization(organization));

        List<VisibleOrganization> visible = directory.visibleOrganizations(sessionFor(organization));

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).organization()).isEqualTo(new ScopeNode(organization, acme));
        assertThat(visible.get(0).subOrganizations()).extracting(ScopeNode::id)
                .containsExactlyInAnyOrder(unit, siblingUnit);
    }

    @Test
    void aSubOrganizationScopedGrantSeesOnlyItsOrganizationAndThatSubOrganization() {
        grant("Operator", ScopePath.subOrganization(organization, unit));

        List<VisibleOrganization> visible = directory.visibleOrganizations(sessionFor(organization));

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).organization().id()).isEqualTo(organization);
        assertThat(visible.get(0).subOrganizations()).extracting(ScopeNode::id).containsExactly(unit);
    }

    @Test
    void anInstallScopedAdministratorSeesEverySubOrganizationOfItsOwnOrganizationOnly() {
        grant("Administrator", ScopePath.INSTALL);

        List<VisibleOrganization> visible = directory.visibleOrganizations(sessionFor(organization));

        assertThat(visible).extracting(v -> v.organization().id()).containsExactly(organization);
        assertThat(visible.get(0).subOrganizations()).extracting(ScopeNode::id)
                .containsExactlyInAnyOrder(unit, siblingUnit);
    }

    @Test
    void aPrincipalWithNoBindingsSeesNoOrganizationsAndDoesNotThrow() {
        assertThat(directory.visibleOrganizations(sessionFor(organization))).isEmpty();
    }

    @Test
    void theIdentityFieldAndTheTwoHierarchyListsAgreeForEveryGrantShape() {
        List<Runnable> shapes = List.of(
                () -> grant("Viewer", ScopePath.organization(organization)),
                () -> grant("Operator", ScopePath.subOrganization(organization, unit)),
                () -> grant("Operator", ScopePath.environment(organization, siblingUnit, siblingUnitEnvironment)),
                () -> grant("Administrator", ScopePath.INSTALL),
                () -> { /* no grant at all */ });
        for (Runnable shape : shapes) {
            jdbc.getJdbcTemplate().update("DELETE FROM sm_control.role_binding");
            jdbc.getJdbcTemplate().update("DELETE FROM sm_control.scope");
            shape.run();
            Session session = sessionFor(organization);

            List<ScopeNode> organizations = directory.listOrganizations(session);
            List<VisibleOrganization> expected = organizations.stream()
                    .map(o -> new VisibleOrganization(o, directory.listSubOrganizations(session, o.id())))
                    .toList();

            assertThat(directory.visibleOrganizations(session)).isEqualTo(expected);
        }
    }

    /** PostgreSQL orders uuid as unsigned bytes, which is the canonical lower-case text order; UUID.compareTo is signed. */
    private static void sortAsPostgresDoes(UUID[] ids) {
        java.util.Arrays.sort(ids, java.util.Comparator.comparing(UUID::toString));
    }

    // ── seeding helpers ─────────────────────────────────────────────────────────────────────

    private void seedOrganization(UUID organizationId, String name) {
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:organization, :name)",
                new MapSqlParameterSource().addValue("organization", organizationId).addValue("name", name));
    }

    private void seedUnit(UUID organizationId, UUID unitId, String name) {
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) "
                        + "VALUES (:organization, :unit, :name)",
                new MapSqlParameterSource().addValue("organization", organizationId).addValue("unit", unitId)
                        .addValue("name", name));
    }

    private void seedNestedUnit(UUID organizationId, UUID parentUnitId, UUID unitId) {
        jdbc.update("INSERT INTO sm_control.organization_unit "
                        + "(organization_id, organization_unit_id, parent_unit_id, name) "
                        + "VALUES (:organization, :unit, :parent, 'Nested')",
                new MapSqlParameterSource().addValue("organization", organizationId).addValue("unit", unitId)
                        .addValue("parent", parentUnitId));
    }

    private void seedEnvironment(UUID organizationId, UUID unitId, UUID environmentId, String name) {
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name) "
                        + "VALUES (:organization, :unit, :environment, :name)",
                new MapSqlParameterSource().addValue("organization", organizationId).addValue("unit", unitId)
                        .addValue("environment", environmentId).addValue("name", name));
    }

    private UUID seedPrincipal(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                        + "VALUES (:id, :organization, 'idp', :username)",
                new MapSqlParameterSource().addValue("id", id).addValue("organization", organizationId)
                        .addValue("username", "hierarchy-" + id + "@example.test"));
        return id;
    }

    private void grant(String role, ScopePath path) {
        UUID scopeId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.scope "
                        + "(scope_id, kind, organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                        + "VALUES (:scopeId, :kind, :organization, :unit, :environment, :solution, :instance)",
                scopeParameters(scopeId, path));
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        + "(SELECT role_id FROM sm_control.role WHERE name = :role), :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", principal.id())
                        .addValue("role", role).addValue("scopeId", scopeId));
    }

    private static MapSqlParameterSource scopeParameters(UUID scopeId, ScopePath path) {
        return new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("kind", switch (path.depth()) {
            case 0 -> "install";
            case 1 -> "organization";
            case 2 -> "organization_unit";
            case 3 -> "environment";
            case 4 -> "solution";
            case 5 -> "instance";
            default -> throw new IllegalArgumentException("unsupported scope depth");
        }).addValue("organization", path.organization()).addValue("unit", path.subOrganization())
                .addValue("environment", path.environment()).addValue("solution", path.solution())
                .addValue("instance", path.instance());
    }
}
