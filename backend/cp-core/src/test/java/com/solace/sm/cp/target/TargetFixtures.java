package com.solace.sm.cp.target;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.JdbcScopeStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Shared arrangement for the target-governance ITs (plan 010 T2.7): one migrated {@code sm_control} database, a
 * fresh organization tree per test, principals bound by role at a scope. Everything here is seeded as the migrator,
 * which the OR-25 audit fail-closed rule does not apply to; the code under test runs as {@code sm_cp_runtime}.
 */
public final class TargetFixtures {

    /**
     * One container for the target ITs, fully migrated: the readers they exercise (the grant and resource-directory
     * adapters) read V017's {@code instance.retired_at}, so a schema older than the head is not a valid fixture.
     */
    public static final String CACHE_KEY = "target-governance";
    public static final String VERSION = SmControlPostgres.LATEST_VERSION;

    private TargetFixtures() {
    }

    /** An organization with one unit holding two environments, so a sibling is always available to leak. */
    public record Tree(UUID organization, UUID unit, UUID environment, UUID siblingEnvironment) {
        public ScopePath organizationScope() {
            return ScopePath.organization(organization);
        }

        public ScopePath unitScope() {
            return ScopePath.subOrganization(organization, unit);
        }

        public ScopePath environmentScope() {
            return ScopePath.environment(organization, unit, environment);
        }

        public ScopePath siblingEnvironmentScope() {
            return ScopePath.environment(organization, unit, siblingEnvironment);
        }
    }

    public static NamedParameterJdbcTemplate migrator() {
        return SmControlPostgres.jdbcAtVersion(CACHE_KEY, VERSION);
    }

    public static NamedParameterJdbcTemplate runtime() {
        migrator();
        return SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);
    }

    public static Tree seedTree() {
        NamedParameterJdbcTemplate m = migrator();
        Tree tree = new Tree(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        m.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization());
        m.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), tree.unit());
        for (UUID environment : List.of(tree.environment(), tree.siblingEnvironment())) {
            m.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                    + " environment_id, name) VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", tree.organization(), tree.unit(), environment);
        }
        return tree;
    }

    /** A local principal whose credential is {@code credential}, optionally flagged must-rotate. */
    public static UUID localPrincipal(UUID organization, String username, String credential, boolean mustRotate) {
        UUID id = UUID.randomUUID();
        migrator().update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash,"
                        + " must_rotate) VALUES (:id, :org, 'local', :username, :hash, :rotate)",
                new MapSqlParameterSource().addValue("id", id).addValue("org", organization)
                        .addValue("username", username)
                        .addValue("hash", new CredentialHasher().hash(credential.toCharArray()))
                        .addValue("rotate", mustRotate));
        return id;
    }

    /** Binds a built-in role ({@code Administrator}, {@code Operator}, {@code Viewer}) at a scope. */
    public static void bind(UUID principal, String roleName, ScopePath scope) {
        UUID scopeId = new JdbcScopeStore(migrator()).canonicalize(scope).value();
        migrator().update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                        + " VALUES (:binding, :principal, (SELECT role_id FROM sm_control.role WHERE name = :role),"
                        + " :scope)",
                new MapSqlParameterSource().addValue("binding", UUID.randomUUID()).addValue("principal", principal)
                        .addValue("role", roleName).addValue("scope", scopeId));
    }

    public static int targetRows(UUID organization) {
        return migrator().queryForObject("SELECT count(*) FROM sm_control.target WHERE organization_id = :o",
                Map.of("o", organization), Integer.class);
    }

    public static void retire(UUID targetId) {
        migrator().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = :id",
                Map.of("id", targetId));
    }

    /** The audit rows for a table and correlation id. */
    public static List<Map<String, Object>> auditRows(String table, UUID correlation) {
        return migrator().queryForList("SELECT audit_operation, actor_principal_id, client_address,"
                        + " row_key::text AS row_key FROM sm_control.change_audit WHERE table_name = :t"
                        + " AND correlation_id = :c",
                Map.of("t", table, "c", correlation));
    }
}
