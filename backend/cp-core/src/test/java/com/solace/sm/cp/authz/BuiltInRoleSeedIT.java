package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.Permission;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BuiltInRoleSeedIT {

    private NamedParameterJdbcTemplate jdbc;
    private NamedParameterJdbcTemplate runtime;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("006");
        runtime = SmControlPostgres.runtimeJdbcAtVersion("006");
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.role_binding");
    }

    @Test
    void exactlyTheThreeApprovedBuiltInRolesAreSeeded() {
        assertThat(roleNames()).containsExactlyInAnyOrder("Administrator", "Viewer", "Operator");
    }

    @Test
    void administratorHoldsEveryCatalogPermissionAndNoOther() {
        assertThat(permissionsOf("Administrator")).containsExactlyInAnyOrderElementsOf(catalogIds());
    }

    @Test
    void viewerHoldsExactlyTheEightApprovedReadPermissions() {
        assertThat(permissionsOf("Viewer")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view");
    }

    @Test
    void operatorHoldsExactlyTheNineteenApprovedPermissions() {
        assertThat(permissionsOf("Operator")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view",
                "instance.bindTarget", "target.use", "instance.create", "config.edit",
                "config.apply", "config.rollback", "instance.restart", "metric.declare",
                "dashboard.edit", "dashboard.reset", "mesh.ticket.mint");
    }

    @Test
    void administratorOnlyPermissionsAreAbsentFromOperator() {
        assertThat(permissionsOf("Operator")).doesNotContain(
                "mesh.stream.grant", "instance.stop", "audit.view", "metrics.export", "metrics.forceRaw", "user.edit");
    }

    @Test
    void everySeededRolePermissionHasAnApprovedCatalogForeignKey() {
        Set<String> persisted = Set.copyOf(jdbc.queryForList(
                "SELECT permission_id FROM sm_control.role_permission", Map.of(), String.class));
        assertThat(persisted).containsExactlyInAnyOrderElementsOf(catalogIds());
        persisted.forEach(Permission::byId);
    }

    @Test
    void aRolePermissionCannotNameAnUnapprovedCatalogId() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sm_control.role_permission (role_id, permission_id) "
                        + "VALUES ((SELECT role_id FROM sm_control.role WHERE name = 'Viewer'), 'instance.explode')",
                Map.of())).satisfies(error -> assertSqlState(error, "23503"));
    }

    @Test
    void aBindingMustReferenceAnExistingCanonicalScope() {
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                        + "VALUES (:id, :organizationId, 'idp', :username)",
                new MapSqlParameterSource().addValue("id", principalId).addValue("organizationId", organizationId)
                        .addValue("username", "role-binding-" + principalId + "@example.test"));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO sm_control.role_binding "
                        + "(binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        + "(SELECT role_id FROM sm_control.role WHERE name = 'Viewer'), :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", principalId)
                        .addValue("scopeId", UUID.randomUUID())))
                .satisfies(error -> assertSqlState(error, "23503"));
    }

    @Test
    void theActualRuntimeRoleCanReadDefinitionsButCannotWriteRolesOrDeletePrincipals() {
        assertThat(runtime.queryForObject("SELECT count(*) FROM sm_control.role", Map.of(), Integer.class))
                .isEqualTo(3);
        assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute(
                "INSERT INTO sm_control.role (role_id, name, built_in) VALUES "
                        + "('00000000-0000-4000-8000-000000000099', 'Unapproved', true)"));
        assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute("DELETE FROM sm_control.principal"));
    }

    @Test
    void mutationGrantingRoleInsertMakesTheRuntimeRefusalTestFail() {
        assertRefusalFailsAfter("GRANT INSERT ON sm_control.role TO sm_cp_runtime",
                "REVOKE INSERT ON sm_control.role FROM sm_cp_runtime", () -> runtime.getJdbcTemplate().execute(
                "INSERT INTO sm_control.role (role_id, name, built_in) VALUES "
                        + "('00000000-0000-4000-8000-000000000098', 'Mutation role', true)"),
                () -> jdbc.getJdbcTemplate().execute(
                        "DELETE FROM sm_control.role WHERE role_id = '00000000-0000-4000-8000-000000000098'"));
    }

    @Test
    void mutationGrantingPrincipalDeleteMakesTheRuntimeRefusalTestFail() {
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        assertRefusalFailsAfter("GRANT DELETE ON sm_control.principal TO sm_cp_runtime",
                "REVOKE DELETE ON sm_control.principal FROM sm_cp_runtime", () -> {
            jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                    new MapSqlParameterSource("id", organizationId));
            jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                            + "VALUES (:id, :organizationId, 'idp', :username)",
                    new MapSqlParameterSource().addValue("id", principalId).addValue("organizationId", organizationId)
                            .addValue("username", "mutation-" + principalId + "@example.test"));
        }, () -> deletePrincipalAndRollBackAsRuntime(principalId), () -> { });
    }

    @Test
    void mutationDroppingTheCatalogForeignKeyMakesTheUnknownPermissionRefusalTestFail() {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute(
                    "ALTER TABLE sm_control.role_permission DROP CONSTRAINT role_permission_catalog_fk");
            assertThatThrownBy(() -> aRolePermissionCannotNameAnUnapprovedCatalogId())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
            status.setRollbackOnly();
        });
    }

    private List<String> roleNames() {
        return jdbc.queryForList("SELECT name FROM sm_control.role ORDER BY name", Map.of(), String.class);
    }

    private Set<String> permissionsOf(String roleName) {
        return Set.copyOf(jdbc.queryForList("SELECT rp.permission_id FROM sm_control.role_permission rp "
                        + "JOIN sm_control.role r ON r.role_id = rp.role_id WHERE r.name = :roleName",
                Map.of("roleName", roleName), String.class));
    }

    private static Set<String> catalogIds() {
        return Arrays.stream(Permission.values()).map(Permission::id).collect(Collectors.toUnmodifiableSet());
    }

    private static void assertRefused(SqlAction action) {
        assertThatThrownBy(action::run).satisfies(error -> assertSqlState(error, "42501"));
    }

    private void assertRefusalFailsAfter(String grant, String revoke, SqlAction action, SqlAction cleanup) {
        assertRefusalFailsAfter(grant, revoke, () -> { }, action, cleanup);
    }

    private void assertRefusalFailsAfter(
            String grant, String revoke, SqlAction setup, SqlAction action, SqlAction cleanup) {
        jdbc.getJdbcTemplate().execute(grant);
        try {
            setup.run();
            assertThatThrownBy(() -> assertRefused(action))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
        } finally {
            try {
                cleanup.run();
            } finally {
                jdbc.getJdbcTemplate().execute(revoke);
            }
        }
    }

    private void assertRuntimeRefused(SqlAction action) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            assertRefused(action);
            status.setRollbackOnly();
        });
    }

    private void deletePrincipalAndRollBackAsRuntime(UUID principalId) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            assertThat(runtime.update("DELETE FROM sm_control.principal WHERE id = :id", Map.of("id", principalId)))
                    .as("the disposable principal is deleted only when the DELETE grant mutation is present")
                    .isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    private static void assertSqlState(Throwable error, String sqlState) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isEqualTo(sqlState);
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException", error);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run();
    }
}
