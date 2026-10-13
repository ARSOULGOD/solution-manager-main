package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.EffectivePermissionView;
import com.solace.sm.cp.api.identity.IdentityView;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcRoleBindingRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link IdentityService} against the actual sm_cp_runtime role — no credential hash ever crosses it. */
class IdentityServiceIT {

    private static final UUID BOOTSTRAP_PRINCIPAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final UUID VIEWER_ROLE_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.212", UUID.randomUUID());

    private NamedParameterJdbcTemplate jdbc;
    private NamedParameterJdbcTemplate runtime;
    private IdentityService identity;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion(SmControlPostgres.LATEST_VERSION);
        runtime = SmControlPostgres.runtimeJdbcAtVersion(SmControlPostgres.LATEST_VERSION);
        identity = new IdentityService(new JdbcPrincipalRepository(runtime),
                new JdbcAuthorizationGrantRepository(runtime), new JdbcRoleBindingRepository(runtime));
    }

    @Test
    void bootstrapAdminIsDescribedWithEveryCatalogPermissionAtTheInstallScope() {
        Session session = bootstrapSession();

        IdentityView view = identity.describe(session);

        assertThat(view.principal()).isEqualTo(session.principal());
        assertThat(view.username()).isEqualTo("bootstrap-admin");
        assertThat(view.source()).isEqualTo(PrincipalSource.LOCAL);
        assertThat(view.mustRotate()).isEqualTo(session.mustRotate());
        assertThat(view.permissions()).hasSize(Permission.values().length);
        assertThat(view.permissions()).extracting(EffectivePermissionView::permission)
                .containsExactlyElementsOf(Arrays.asList(Permission.values()));
        assertThat(view.permissions()).allSatisfy(
                p -> assertThat(p.scopes()).containsExactly(ScopePath.INSTALL));
    }

    @Test
    void aScopedPrincipalShowsOnlyItsOwnBindingsInPermissionOrdinalOrder() {
        UUID organizationId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:id, 'fixture-' || gen_random_uuid()::text)",
                new MapSqlParameterSource("id", organizationId));
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id) "
                        + "VALUES (:scopeId, 'organization', :organizationId)",
                new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("organizationId", organizationId));
        UUID createdPrincipalId = new JdbcPrincipalRepository(jdbc)
                .createLocal(organizationId, "scoped-" + principalId + "@example.test", "{argon2id}stored", false);
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, :roleId, :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", bindingId).addValue("principalId", createdPrincipalId)
                        .addValue("roleId", VIEWER_ROLE_ID).addValue("scopeId", scopeId));
        Session session = openSession(createdPrincipalId);
        ScopePath organizationScope = ScopePath.organization(organizationId);

        IdentityView view = identity.describe(session);

        assertThat(view.username()).startsWith("scoped-");
        assertThat(view.permissions()).extracting(EffectivePermissionView::permission)
                .containsExactly(Permission.SOLUTION_VIEW, Permission.INSTANCE_VIEW, Permission.CONFIG_VIEW,
                        Permission.HEALTH_VIEW, Permission.LOGS_READ, Permission.METRICS_QUERY,
                        Permission.DASHBOARD_VIEW, Permission.TARGET_VIEW, Permission.CONNECTION_VIEW,
                        Permission.AGENT_VIEW);
        assertThat(view.permissions()).allSatisfy(p -> assertThat(p.scopes()).containsExactly(organizationScope));
    }

    @Test
    void noCredentialHashIsReachableFromTheDescribedIdentity() {
        String storedHash = jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                new MapSqlParameterSource("id", BOOTSTRAP_PRINCIPAL_ID), String.class);
        Session session = bootstrapSession();

        IdentityView view = identity.describe(session);

        assertThat(view.toString()).doesNotContain(storedHash);
        assertThat(IdentityView.class.getRecordComponents()).noneMatch(
                component -> component.getName().toLowerCase(java.util.Locale.ROOT).contains("credential")
                        || component.getName().toLowerCase(java.util.Locale.ROOT).contains("hash"));
    }

    private Session bootstrapSession() {
        return openSession(BOOTSTRAP_PRINCIPAL_ID);
    }

    private Session openSession(UUID principalId) {
        // .revoke() is never exercised in this class, but .open() now sets its own OR-25 audit
        // context (LOGIN_SUCCEEDED) as part of its own write — against the REAL sm_cp_runtime role,
        // that requires a REAL AuditContext and a REAL transaction, not the no-op/no-transaction pair
        // this used before OR-25 existed.
        TransactionOperations transactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        SessionService runtimeSessions = new SessionService(new JdbcSessionRepository(runtime),
                new JdbcPrincipalRepository(runtime), new JdbcAuditContext(runtime), transactions, Clock.systemUTC());
        SessionToken token = runtimeSessions.open(new PrincipalRef(principalId), ORIGIN);
        return runtimeSessions.resolve(token).orElseThrow();
    }
}
