package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.hierarchy.ScopeVisibleHierarchyDirectory;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcHierarchyTreeRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 012 T4 / {@code OPS-Q4} (a): what a retired instance does to resolution and to authorization, against real
 * PostgreSQL with the real readers wired as {@code ControlPlaneConfiguration} wires them. The retirement itself is
 * written as the migrator (the write path and its audit are {@code InstanceRetirementServiceIT}'s subject).
 *
 * <p>The decisive case is the last two: a principal bound <i>only</i> on the retired instance must stop seeing the
 * environment and the solution above it, and must get {@code 404} - not {@code 403} - on them, because a {@code 403}
 * would tell it they exist. (A sibling <i>instance</i> is on no part of that binding's path, so it is a {@code 404}
 * before retirement too; the test pins that it stays one.)
 */
class RetiredInstanceAuthorizationIT {

    private static final String KEY = "retired-instance-authorization-it";

    private final NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(KEY, "017");
    private final JdbcResourceScopeDirectory directory = new JdbcResourceScopeDirectory(jdbc);
    private final JdbcAuthorizationGrantRepository grants = new JdbcAuthorizationGrantRepository(jdbc);
    private final ScopeEvaluatingAuthorizationService authorization = new ScopeEvaluatingAuthorizationService(
            new JdbcPrincipalOrganizationResolver(jdbc), grants, new ScopePathResolver(directory));
    private final ScopeVisibleHierarchyDirectory hierarchy =
            new ScopeVisibleHierarchyDirectory(grants, new JdbcHierarchyTreeRepository(jdbc));

    @Test
    void aRetiredInstanceIsNotFoundByTheResourceDirectoryAndASiblingIsStillThere() {
        World w = world();

        assertThat(directory.findScopePath(instance(w, w.retiring))).isPresent();

        retire(w.retiring);

        assertThat(directory.findScopePath(instance(w, w.retiring))).as("a retired instance resolves to nothing").isEmpty();
        assertThat(directory.findScopePath(instance(w, w.sibling))).isPresent();
        assertThat(directory.findScopePaths(ResourceKind.INSTANCE, w.organization,
                List.of(w.retiring, w.sibling)).keySet()).containsExactly(w.sibling);
    }

    @Test
    void everyInstanceScopedOperationOnARetiredIdIs404EvenForAnAdministratorWithAnOrganizationGrant() {
        World w = world();
        PrincipalRef orgAdmin = bind(w, "Administrator", ScopePath.organization(w.organization));
        authorization.require(orgAdmin, Permission.INSTANCE_VIEW, instance(w, w.retiring));

        retire(w.retiring);

        for (Permission verb : List.of(Permission.INSTANCE_VIEW, Permission.CONFIG_EDIT, Permission.CONFIG_APPLY,
                Permission.INSTANCE_STOP, Permission.INSTANCE_UNDECLARE, Permission.DASHBOARD_VIEW)) {
            assertThatThrownBy(() -> authorization.require(orgAdmin, verb, instance(w, w.retiring)))
                    .as(verb.id()).isInstanceOf(ResourceNotFoundException.class);
        }
        authorization.require(orgAdmin, Permission.INSTANCE_VIEW, instance(w, w.sibling));
    }

    @Test
    void aBindingWhoseScopeIsTheRetiredInstanceGrantsNothingAndOneElsewhereIsKept() {
        World w = world();
        PrincipalRef onlyThere = bind(w, "Viewer", instancePath(w, w.retiring));
        PrincipalRef alsoElsewhere = bind(w, "Viewer", instancePath(w, w.retiring));
        bindMore(alsoElsewhere, "Viewer", ScopePath.environment(w.organization, w.unit, w.environment));
        assertThat(grants.grantsFor(onlyThere)).hasSize(1);

        retire(w.retiring);

        assertThat(grants.grantsFor(onlyThere)).as("its only binding was on the retired instance").isEmpty();
        assertThat(grants.grantsFor(alsoElsewhere)).extracting(g -> g.scope())
                .containsExactly(ScopePath.environment(w.organization, w.unit, w.environment));
    }

    @Test
    void aPrincipalBoundOnlyOnTheRetiredInstanceSeesNeitherItsEnvironmentNorItsSolution() {
        World w = world();
        PrincipalRef principal = bind(w, "Viewer", instancePath(w, w.retiring));
        Session session = session(principal, w.organization);
        assertThat(hierarchy.listOrganizations(session)).as("before: the ancestors of its binding are visible")
                .hasSize(1);
        assertThat(hierarchy.listEnvironments(session, w.organization, w.unit)).hasSize(1);
        assertThatThrownBy(() -> authorization.require(principal, Permission.SOLUTION_VIEW, solution(w)))
                .as("before: it sees the solution (403, not 404) but may not act on it")
                .isInstanceOf(AccessDeniedException.class);

        retire(w.retiring);

        assertThat(hierarchy.listOrganizations(session)).as("no binding, no organization").isEmpty();
        assertThatThrownBy(() -> hierarchy.listEnvironments(session, w.organization, w.unit))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authorization.require(principal, Permission.SOLUTION_VIEW, solution(w)))
                .as("after: the solution is invisible - 404").isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aRetiredBindingTurnsNoResourceBesideOrAboveTheRetiredInstanceFromA404IntoA403() {
        // design ops-501.md section 4.3 finding F9: ScopeVisibility is prefix-comparable in BOTH directions, so a binding
        // left at a retired instance would turn the 404s of the resources on its path (its environment and solution)
        // into 403s - a principal told "that exists, you may not" about nodes it has no live binding near.
        World w = world();
        PrincipalRef principal = bind(w, "Viewer", instancePath(w, w.retiring));
        ResourceRef environment = ResourceRef.of(ResourceKind.ENVIRONMENT, w.environment, new TenantId(w.organization));
        assertThatThrownBy(() -> authorization.require(principal, Permission.INSTANCE_VIEW, environment))
                .as("before: the environment is visible through the binding, so it is a 403")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.require(principal, Permission.INSTANCE_VIEW, instance(w, w.sibling)))
                .as("a sibling instance is not on the binding's path: 404 before and after, never a 403")
                .isInstanceOf(ResourceNotFoundException.class);

        retire(w.retiring);

        assertThatThrownBy(() -> authorization.require(principal, Permission.INSTANCE_VIEW, environment))
                .as("after: a 403 would tell this principal the environment exists - it must be a 404")
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authorization.require(principal, Permission.INSTANCE_VIEW, instance(w, w.sibling)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- harness

    private record World(UUID organization, UUID unit, UUID environment, UUID solution, UUID retiring, UUID sibling) {
    }

    private World world() {
        World w = new World(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        var j = jdbc.getJdbcTemplate();
        j.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)", w.organization);
        j.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                w.organization, w.unit);
        j.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                + " VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", w.organization, w.unit, w.environment);
        j.update("INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id,"
                + " solution_id) VALUES (?, ?, ?, ?)", w.organization, w.unit, w.environment, w.solution);
        for (UUID instance : Set.of(w.retiring, w.sibling)) {
            j.update("INSERT INTO sm_control.instance (organization_id, organization_unit_id, environment_id,"
                    + " solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", w.organization, w.unit, w.environment,
                    w.solution, instance);
        }
        return w;
    }

    private void retire(UUID instance) {
        UUID by = UUID.randomUUID();
        UUID org = jdbc.getJdbcTemplate().queryForObject(
                "SELECT organization_id FROM sm_control.instance WHERE instance_id = ?", UUID.class, instance);
        jdbc.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", by, org, "retirer-" + by);
        jdbc.getJdbcTemplate().update(
                "UPDATE sm_control.instance SET retired_at = now(), retired_by = ? WHERE instance_id = ?", by, instance);
    }

    private PrincipalRef bind(World w, String role, ScopePath path) {
        UUID id = UUID.randomUUID();
        jdbc.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", id, w.organization, "retired-authz-" + id);
        PrincipalRef principal = new PrincipalRef(id);
        bindMore(principal, role, path);
        return principal;
    }

    private void bindMore(PrincipalRef principal, String role, ScopePath path) {
        String kind = switch (path.depth()) {
            case 1 -> "organization";
            case 3 -> "environment";
            case 5 -> "instance";
            default -> throw new IllegalArgumentException("unsupported depth in this test");
        };
        // One canonical scope row per node (scope's unique indexes): mint it once, then every binding names it.
        jdbc.getJdbcTemplate().update("INSERT INTO sm_control.scope (scope_id, kind, organization_id,"
                + " organization_unit_id, environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING",
                UUID.randomUUID(), kind, path.organization(), path.subOrganization(), path.environment(),
                path.solution(), path.instance());
        UUID leaf = switch (path.depth()) {
            case 1 -> path.organization();
            case 3 -> path.environment();
            default -> path.instance();
        };
        String leafColumn = switch (path.depth()) {
            case 1 -> "organization_id";
            case 3 -> "environment_id";
            default -> "instance_id";
        };
        UUID scopeId = jdbc.getJdbcTemplate().queryForObject(
                "SELECT scope_id FROM sm_control.scope WHERE kind = ? AND " + leafColumn + " = ?", UUID.class, kind,
                leaf);
        jdbc.getJdbcTemplate().update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id,"
                + " scope_id) VALUES (?, ?, (SELECT role_id FROM sm_control.role WHERE name = ?), ?)",
                UUID.randomUUID(), principal.id(), role, scopeId);
    }

    private static ScopePath instancePath(World w, UUID instance) {
        return ScopePath.instance(w.organization, w.unit, w.environment, w.solution, instance);
    }

    private static ResourceRef instance(World w, UUID instance) {
        return ResourceRef.instance(instance, new TenantId(w.organization));
    }

    private static ResourceRef solution(World w) {
        return ResourceRef.solution(w.solution, new TenantId(w.organization));
    }

    private static Session session(PrincipalRef principal, UUID organization) {
        Instant now = Instant.now();
        return new Session(new SessionId(UUID.randomUUID()), principal, organization, false, now, now,
                now.plusSeconds(3600), now.plusSeconds(86_400));
    }
}
