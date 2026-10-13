package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcRoleBindingRepositoryIT {

    private static final UUID BOOTSTRAP_PRINCIPAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final UUID ADMINISTRATOR_ROLE_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcRoleBindingRepository roleBindings;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("010");
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("010");
        roleBindings = new JdbcRoleBindingRepository(runtime);
    }

    @Test
    void bootstrapAdminIsBoundToAdministrator() {
        var names = roleBindings.boundRoleNames(new PrincipalRef(BOOTSTRAP_PRINCIPAL_ID));

        assertThat(names).containsExactly("Administrator");
    }

    @Test
    void unboundPrincipalHasNoRoleNames() {
        var names = roleBindings.boundRoleNames(new PrincipalRef(UUID.randomUUID()));

        assertThat(names).isEmpty();
    }

    @Test
    void bootstrapAdminIsBoundToAdministratorAtTheInstallScope() {
        boolean result = roleBindings.boundToRoleAtInstallScope(new PrincipalRef(BOOTSTRAP_PRINCIPAL_ID), "Administrator");

        assertThat(result).isTrue();
    }

    @Test
    void unboundPrincipalIsNotBoundToAdministratorAtTheInstallScope() {
        boolean result = roleBindings.boundToRoleAtInstallScope(new PrincipalRef(UUID.randomUUID()), "Administrator");

        assertThat(result).isFalse();
    }

    /**
     * The fix this IT exists for (final review finding, plan 132, D2): a principal bound to
     * Administrator at an ORGANIZATION scope — not the install scope — must NOT be reported as
     * bound to Administrator at the install scope, even though {@link #bootstrapAdminIsBoundToAdministrator()}
     * shows the un-scoped {@code boundRoleNames} legitimately still lists "Administrator" for
     * display. Before this fix, {@code AuthController} derived {@code superAdmin} from exactly this
     * un-scoped signal.
     */
    @Test
    void principalBoundToAdministratorOnlyAtAnOrganizationScopeIsNotInstallScopeAdministrator() {
        UUID organizationId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id) "
                        + "VALUES (:scopeId, 'organization', :organizationId)",
                new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("organizationId", organizationId));
        UUID principalId = new JdbcPrincipalRepository(jdbc)
                .createLocal(organizationId, "org-admin-" + bindingId + "@example.test", "{argon2id}stored", false);
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, :roleId, :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", bindingId).addValue("principalId", principalId)
                        .addValue("roleId", ADMINISTRATOR_ROLE_ID).addValue("scopeId", scopeId));

        var names = roleBindings.boundRoleNames(new PrincipalRef(principalId));
        boolean installScopeAdministrator = roleBindings.boundToRoleAtInstallScope(new PrincipalRef(principalId), "Administrator");

        assertThat(names).containsExactly("Administrator");
        assertThat(installScopeAdministrator).isFalse();
    }
}
