package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import com.solace.sm.cp.target.TargetFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Shared arrangement for the user- and assignment-administration ITs (plan 013 T3.1): the real authorization service
 * over real grants (never a stub), a principal bound by a built-in role at a scope, the install-scope binding the
 * bootstrap administrator has, live and retired instances, sessions, and the audit rows a correlation id produced. All
 * seeding is the migrator's (which the OR-25 rule does not apply to); the code under test runs as {@code sm_cp_runtime}.
 */
final class IdentityAdministrationFixtures {

    /** V007's install scope row: the one the bootstrap administrator is bound at. */
    static final UUID INSTALL_SCOPE = UUID.fromString("00000000-0000-4000-8000-000000000010");

    private static final List<UUID> CUSTOM_ROLES = new java.util.concurrent.CopyOnWriteArrayList<>();

    private IdentityAdministrationFixtures() {
    }

    static ScopeEvaluatingAuthorizationService authorization(NamedParameterJdbcTemplate runtime) {
        return new ScopeEvaluatingAuthorizationService(new JdbcPrincipalOrganizationResolver(runtime),
                new JdbcAuthorizationGrantRepository(runtime),
                new ScopePathResolver(new JdbcResourceScopeDirectory(runtime)));
    }

    /** A local principal bound by {@code role} at {@code scope} (never the install scope: see {@link #bindAtInstall}). */
    static UUID principalWith(TargetFixtures.Tree tree, String role, ScopePath scope) {
        UUID id = TargetFixtures.localPrincipal(tree.organization(), "it-" + UUID.randomUUID(), "unused-credential",
                false);
        TargetFixtures.bind(id, role, scope);
        return id;
    }

    /**
     * A role that is not one of the three built-ins, holding exactly {@code verbs}, inserted as the migrator (the
     * runtime role cannot write a role). The stand-in for D-REG {@code OQ-7 (c)}'s intermediate role, which no
     * migration creates: it lets an IT prove that the API does not rely on "only the Administrator can ever call this".
     */
    static String customRole(com.solace.sm.cp.api.authz.Permission... verbs) {
        String name = "it-role-" + UUID.randomUUID();
        UUID id = UUID.randomUUID();
        CUSTOM_ROLES.add(id);
        var m = TargetFixtures.migrator();
        m.update("INSERT INTO sm_control.role (role_id, name, built_in) VALUES (:id, :name, false)",
                Map.of("id", id, "name", name));
        for (com.solace.sm.cp.api.authz.Permission verb : verbs) {
            m.update("INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES (:id, :verb)",
                    Map.of("id", id, "verb", verb.id()));
        }
        return name;
    }

    /**
     * Removes every role {@link #customRole} made, with its verbs and bindings: the container is shared by the whole
     * IT suite and {@code listRoles} must keep returning exactly the three built-ins. Call it from {@code @AfterEach}.
     */
    static void dropCustomRoles() {
        var m = TargetFixtures.migrator();
        for (UUID id : CUSTOM_ROLES) {
            m.update("DELETE FROM sm_control.role_binding WHERE role_id = :id", Map.of("id", id));
            m.update("DELETE FROM sm_control.role_permission WHERE role_id = :id", Map.of("id", id));
            m.update("DELETE FROM sm_control.role WHERE role_id = :id", Map.of("id", id));
        }
        CUSTOM_ROLES.clear();
    }

    /** Binds at the install scope the way V007 binds the bootstrap administrator. */
    static void bindAtInstall(UUID principal, String role) {
        TargetFixtures.migrator().update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id,"
                        + " scope_id) VALUES (:binding, :principal, (SELECT role_id FROM sm_control.role"
                        + " WHERE name = :role), :scope)",
                new MapSqlParameterSource().addValue("binding", UUID.randomUUID()).addValue("principal", principal)
                        .addValue("role", role).addValue("scope", INSTALL_SCOPE));
    }

    /** A live session for the principal (inserted as the migrator, so no audit context is needed). */
    static String session(UUID principal) {
        String tokenHash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        Instant now = Instant.now();
        TargetFixtures.migrator().update("INSERT INTO sm_control.session (session_id, principal_id, token_hash,"
                        + " created_at, last_used_at, idle_expires_at, absolute_expires_at) VALUES (:id, :principal,"
                        + " :hash, :now, :now, :idle, :absolute)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("principal", principal)
                        .addValue("hash", tokenHash).addValue("now", java.sql.Timestamp.from(now))
                        .addValue("idle", java.sql.Timestamp.from(now.plusSeconds(1800)))
                        .addValue("absolute", java.sql.Timestamp.from(now.plusSeconds(43_200))));
        return tokenHash;
    }

    static boolean sessionRevoked(String tokenHash) {
        return TargetFixtures.migrator().queryForObject(
                "SELECT revoked_at IS NOT NULL FROM sm_control.session WHERE token_hash = :h",
                Map.of("h", tokenHash), Boolean.class);
    }

    /** Every audit row of a correlation id, oldest first, with the old and new values as text. */
    static List<Map<String, Object>> audit(UUID correlation) {
        return TargetFixtures.migrator().queryForList("SELECT table_name, operation, audit_operation,"
                        + " actor_principal_id, client_address, row_key::text AS row_key,"
                        + " coalesce(old_values::text, '') AS old_values, coalesce(new_values::text, '') AS new_values,"
                        + " secret_columns_changed FROM sm_control.change_audit WHERE correlation_id = :c"
                        + " ORDER BY change_audit_id",
                Map.of("c", correlation));
    }

    static long count(String sql, Map<String, ?> parameters) {
        return TargetFixtures.migrator().queryForObject(sql, parameters, Long.class);
    }

    static PrincipalRef ref(UUID id) {
        return new PrincipalRef(id);
    }

    /** A solution and two instances in the tree's first environment; {@code retiring} is retired when asked. */
    record Instances(UUID solution, UUID live, UUID retiring) {

        ScopePath solutionPath(TargetFixtures.Tree tree) {
            return ScopePath.solution(tree.organization(), tree.unit(), tree.environment(), solution);
        }

        ScopePath livePath(TargetFixtures.Tree tree) {
            return ScopePath.instance(tree.organization(), tree.unit(), tree.environment(), solution, live);
        }

        ScopePath retiringPath(TargetFixtures.Tree tree) {
            return ScopePath.instance(tree.organization(), tree.unit(), tree.environment(), solution, retiring);
        }
    }

    static Instances seedInstances(TargetFixtures.Tree tree) {
        Instances instances = new Instances(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var j = TargetFixtures.migrator().getJdbcTemplate();
        j.update("INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id, solution_id)"
                + " VALUES (?, ?, ?, ?)", tree.organization(), tree.unit(), tree.environment(), instances.solution());
        for (UUID instance : List.of(instances.live(), instances.retiring())) {
            j.update("INSERT INTO sm_control.instance (organization_id, organization_unit_id, environment_id,"
                    + " solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", tree.organization(), tree.unit(),
                    tree.environment(), instances.solution(), instance);
        }
        return instances;
    }

    static void retire(TargetFixtures.Tree tree, UUID instance) {
        UUID by = UUID.randomUUID();
        var j = TargetFixtures.migrator().getJdbcTemplate();
        j.update("INSERT INTO sm_control.principal (id, organization_id, source, username) VALUES (?, ?, 'idp', ?)",
                by, tree.organization(), "retirer-" + by);
        j.update("UPDATE sm_control.instance SET retired_at = now(), retired_by = ? WHERE instance_id = ?", by,
                instance);
    }
}
