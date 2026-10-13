package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The three obligations of the {@code sm_cp_runtime} role's boundary that no existing IT already
 * proves: {@code BootstrapAdminIT.runtimeCannotUpdateAPrincipalColumnOutsideTheAdditiveRotationGrant}
 * and {@code SessionIT.theActualRuntimeRoleCannotAlterImmutableSessionColumnsOrDeleteSessions} cover
 * the {@code principal}/{@code session} tables; this class covers schema-DDL and the role tables,
 * plus the runtime credential's inability to migrate at all.
 */
class RuntimeRoleBoundaryIT {

    @Test
    void theRuntimeRoleCannotCreateATableInSmControl() {
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("010");

        assertThatThrownBy(() -> runtime.getJdbcTemplate()
                .execute("CREATE TABLE sm_control.runtime_boundary_probe (id uuid PRIMARY KEY)"))
                .satisfies(error -> assertSqlState(error, "42501"));
    }

    @Test
    void theRuntimeRoleCannotWriteToRoleRolePermissionOrRoleBinding() {
        NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion("010");
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("010");
        UUID roleId = UUID.randomUUID();
        migrator.update("INSERT INTO sm_control.role (role_id, name, built_in) VALUES (:id, :name, false)",
                new MapSqlParameterSource().addValue("id", roleId).addValue("name", "probe-" + roleId));

        assertThatThrownBy(() -> runtime.update(
                "INSERT INTO sm_control.role (role_id, name, built_in) VALUES (:id, :name, false)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("name", "probe-insert")))
                .satisfies(error -> assertSqlState(error, "42501"));
        assertThatThrownBy(() -> runtime.update("UPDATE sm_control.role SET name = name WHERE role_id = :id",
                Map.of("id", roleId))).satisfies(error -> assertSqlState(error, "42501"));
        assertThatThrownBy(() -> runtime.update("DELETE FROM sm_control.role WHERE role_id = :id", Map.of("id", roleId)))
                .satisfies(error -> assertSqlState(error, "42501"));

        assertThatThrownBy(() -> runtime.update(
                "INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES (:id, 'audit.view')",
                Map.of("id", roleId))).satisfies(error -> assertSqlState(error, "42501"));
        assertThatThrownBy(() -> runtime.update(
                "DELETE FROM sm_control.role_permission WHERE role_id = :id", Map.of("id", roleId)))
                .satisfies(error -> assertSqlState(error, "42501"));

        UUID organizationId = UUID.randomUUID();
        migrator.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        UUID scopeId = UUID.randomUUID();
        migrator.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id) "
                        + "VALUES (:scopeId, 'organization', :organizationId)",
                new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("organizationId", organizationId));
        UUID principalId = new com.solace.sm.cp.persistence.JdbcPrincipalRepository(migrator)
                .createLocal(organizationId, "boundary-" + UUID.randomUUID() + "@example.test", "{argon2id}stored", false);

        assertThatThrownBy(() -> runtime.update(
                "INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, :roleId, :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", principalId)
                        .addValue("roleId", roleId).addValue("scopeId", scopeId)))
                .satisfies(error -> assertSqlState(error, "42501"));
    }

    @Test
    void aFlywayMigrateRunWithTheRuntimeCredentialFails() {
        String url = SmControlPostgres.runtimeUrlAtVersion("010");
        String password = SmControlPostgres.runtimePasswordAtVersion("010");

        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(url, SmControlPostgres.RUNTIME_USERNAME, password)
                .defaultSchema("sm_control")
                .locations("classpath:db/migration/sm_control")
                .load()
                .migrate())
                .isInstanceOf(FlywayException.class)
                .satisfies(error -> assertSqlState(error, "42501"));
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
}
