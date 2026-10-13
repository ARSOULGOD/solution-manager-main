package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * S0 point 24 / plan 010 T1.3 against a real {@code sm_control} schema, evaluated <b>as
 * {@code sm_cp_runtime}</b> — the role the application connects as — with every fixture row seeded
 * by the migrator (exempt from the {@code OR-25} audit contract, so the read path is the only thing
 * under test).
 *
 * <p>The two questions the reach classification answers, and the one it must not be confused with:
 * <i>is the verb held anywhere in my organization</i> (the pre-check), <i>at which of these ids does
 * my grant reach</i> (the subset — downward reach, the same {@code ScopePath#authorizes} that
 * {@code require} uses), and NOT <i>can I see this on a navigation tree</i>
 * ({@code ScopeVisibility}, which is also true of an ancestor and so would leak it).
 */
class GrantReachAuthorizationIT {

    private static final String CACHE_KEY = "grant-reach-authorization-it";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);

    private GrantReachAuthorization reach;
    private UUID organization;
    private UUID unit;
    private UUID development;
    private UUID production;
    private UUID otherOrganization;
    private UUID otherUnit;
    private UUID otherEnvironment;
    private PrincipalRef principal;

    @BeforeEach
    void setUp() {
        organization = UUID.randomUUID();
        unit = UUID.randomUUID();
        development = UUID.randomUUID();
        production = UUID.randomUUID();
        otherOrganization = UUID.randomUUID();
        otherUnit = UUID.randomUUID();
        otherEnvironment = UUID.randomUUID();
        seedEnvironment(organization, unit, development);
        seedEnvironment(organization, unit, production);
        seedEnvironment(otherOrganization, otherUnit, otherEnvironment);
        principal = new PrincipalRef(seedPrincipal(organization, "active"));
        reach = new GrantReachAuthorization(
                new JdbcPrincipalOrganizationResolver(runtime),
                new JdbcAuthorizationGrantRepository(runtime),
                new ScopePathResolver(new JdbcResourceScopeDirectory(runtime)));
    }

    // ---------------------------------------------------------------- the pre-check

    @Test
    void anEnvironmentBoundOperatorPassesTheReachPreCheck() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThatNoException().isThrownBy(() -> reach.requireHeldAnywhere(principal, Permission.INSTANCE_VIEW));
        assertThatNoException().isThrownBy(() -> reach.requireHeldAnywhere(principal, Permission.CONFIG_APPLY));
    }

    @Test
    void aPrincipalHoldingTheVerbNowhereIs403() {
        assertThatThrownBy(() -> reach.requireHeldAnywhere(principal, Permission.INSTANCE_VIEW))
                .as("no grant at all").isInstanceOf(AccessDeniedException.class);

        grant("Viewer", ScopePath.environment(organization, unit, development));

        assertThatThrownBy(() -> reach.requireHeldAnywhere(principal, Permission.CONFIG_APPLY))
                .as("a grant whose role lacks the verb").isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aGrantInAnotherOrganizationDoesNotSatisfyThePreCheck() {
        grant("Operator", ScopePath.environment(otherOrganization, otherUnit, otherEnvironment));

        assertThatThrownBy(() -> reach.requireHeldAnywhere(principal, Permission.INSTANCE_VIEW))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aDeactivatedPrincipalWhoseOrganizationCannotBeResolvedIs403() {
        PrincipalRef disabled = new PrincipalRef(seedPrincipal(organization, "deactivated"));
        grant(disabled, "Operator", ScopePath.environment(organization, unit, development));

        assertThatThrownBy(() -> reach.requireHeldAnywhere(disabled, Permission.INSTANCE_VIEW))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(reach.authorizedSubset(disabled, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT,
                List.of(development))).isEmpty();
    }

    // ---------------------------------------------------------------- the subset

    @Test
    void theSubsetUsesDownwardReachNotNavigationVisibility() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT,
                List.of(development))).containsExactly(development);
        // The organization and the unit are VISIBLE from an environment-bound grant (the navigation
        // tree shows them) but not REACHED by it: ScopePath#authorizes is downward only.
        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ORGANIZATION,
                List.of(organization))).isEmpty();
        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.SUB_ORGANIZATION,
                List.of(unit))).isEmpty();
    }

    @Test
    void aSiblingEnvironmentsIdIsNotInTheSubset() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT,
                List.of(development, production))).containsExactly(development);
    }

    @Test
    void grantsAtDifferentScopesUnionAndAVerbAtOneScopeIsNotBorrowedByAnother() {
        grant("Operator", ScopePath.environment(organization, unit, development));
        grant("Viewer", ScopePath.environment(organization, unit, production));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT,
                List.of(development, production))).containsExactlyInAnyOrder(development, production);
        assertThat(reach.authorizedSubset(principal, Permission.CONFIG_APPLY, ResourceKind.ENVIRONMENT,
                List.of(development, production)))
                .as("config.apply is held at development only; the Viewer grant at production does not carry it")
                .containsExactly(development);
    }

    @Test
    void anotherOrganizationsIdIsNeverInTheSubset() {
        // An install-scoped grant authorizes every path by prefix, so only the organization bind
        // can keep the other organization's environment out.
        grantAtInstall("Administrator");

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT,
                List.of(development, otherEnvironment))).containsExactly(development);
    }

    @Test
    void aResourceWithNoOrganizationIsNeverInTheSubset() {
        grantAtInstall("Administrator");
        UUID administratorRole = migrator.queryForObject(
                "SELECT role_id FROM sm_control.role WHERE name = 'Administrator'", new MapSqlParameterSource(),
                UUID.class);

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ROLE_DEFINITION,
                List.of(administratorRole))).isEmpty();
    }

    @Test
    void anIdThatDoesNotExistIsSimplyAbsent() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT,
                List.of(development, UUID.randomUUID()))).containsExactly(development);
    }

    @Test
    void aKindWithNoInstalledDirectoryYieldsAnEmptySubsetNotAnError() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.DEPLOYMENT_TARGET,
                List.of(UUID.randomUUID()))).isEqualTo(Set.of());
    }

    // ---------------------------------------------------------------- the batched directory read

    @Test
    void theBatchedDirectoryReadBindsTheOrganizationInSqlAndKeysEachPathByItsOwnId() {
        JdbcResourceScopeDirectory directory = new JdbcResourceScopeDirectory(runtime);
        UUID absent = UUID.randomUUID();

        assertThat(directory.findScopePaths(ResourceKind.ENVIRONMENT, organization,
                List.of(development, production, otherEnvironment, absent, development)))
                .as("own-organization ids only; the other organization's and the absent id are not keys; "
                        + "a repeated id is one row")
                .containsOnlyKeys(development, production)
                .containsEntry(development, ScopePath.environment(organization, unit, development));
        assertThat(directory.findScopePaths(ResourceKind.PRINCIPAL, organization, List.of(principal.id())))
                .as("a kind whose identity column is not named <kind>_id is keyed by that column")
                .containsOnlyKeys(principal.id());
        assertThat(directory.findScopePaths(ResourceKind.ENVIRONMENT, organization, List.of())).isEmpty();
        assertThat(directory.findScopePaths(ResourceKind.DEPLOYMENT_TARGET, organization, List.of(absent))).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures (migrator: OR-25-exempt)

    private void seedEnvironment(UUID organizationId, UUID unitId, UUID environmentId) {
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)"
                + " ON CONFLICT DO NOTHING", organizationId);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text) ON CONFLICT DO NOTHING", organizationId, unitId);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id,"
                + " organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", organizationId, unitId, environmentId);
    }

    private UUID seedPrincipal(UUID organizationId, String status) {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username,"
                + " status, deactivated_at) VALUES (?, ?, 'idp', ?, ?, CASE WHEN ? = 'active' THEN NULL ELSE now() END)",
                id, organizationId, "reach-" + id, status, status);
        return id;
    }

    private void grant(String role, ScopePath path) {
        grant(principal, role, path);
    }

    private void grant(PrincipalRef who, String role, ScopePath path) {
        UUID scopeId = UUID.randomUUID();
        migrator.update("INSERT INTO sm_control.scope "
                        + "(scope_id, kind, organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                        + "VALUES (:scopeId, :kind, :organization, :unit, :environment, :solution, :instance)",
                new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("kind", kindOf(path))
                        .addValue("organization", path.organization()).addValue("unit", path.subOrganization())
                        .addValue("environment", path.environment()).addValue("solution", path.solution())
                        .addValue("instance", path.instance()));
        bind(who, role, scopeId);
    }

    /** The install scope is a singleton row (V001 {@code scope_one_install}); reuse it. */
    private void grantAtInstall(String role) {
        UUID installScope = migrator.getJdbcTemplate().queryForObject(
                "SELECT scope_id FROM sm_control.scope WHERE kind = 'install'", UUID.class);
        bind(principal, role, installScope);
    }

    private void bind(PrincipalRef who, String role, UUID scopeId) {
        migrator.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, (SELECT role_id FROM sm_control.role WHERE name = :role),"
                        + " :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", who.id())
                        .addValue("role", role).addValue("scopeId", scopeId));
    }

    private static String kindOf(ScopePath path) {
        return switch (path.depth()) {
            case 1 -> "organization";
            case 2 -> "organization_unit";
            case 3 -> "environment";
            case 4 -> "solution";
            case 5 -> "instance";
            default -> throw new IllegalArgumentException("fixture grants are never install-scoped; see grantAtInstall");
        };
    }
}
