package com.solace.sm.cp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.ConsequenceClass;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.persistence.JdbcAuditLog;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AuditLogIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private NamedParameterJdbcTemplate jdbc;
    private NamedParameterJdbcTemplate runtime;
    private AuditLog auditLog;
    private final List<FixturePrincipal> fixturePrincipals = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("006");
        runtime = SmControlPostgres.runtimeJdbcAtVersion("006");
        auditLog = new JdbcAuditLog(jdbc);
    }

    @AfterEach
    void tearDown() {
        for (FixturePrincipal fixture : fixturePrincipals) {
            jdbc.update("DELETE FROM sm_control.audit_log WHERE actor_principal_id = :principalId",
                    Map.of("principalId", fixture.principalId()));
            jdbc.update("DELETE FROM sm_control.principal WHERE id = :principalId",
                    Map.of("principalId", fixture.principalId()));
            jdbc.update("DELETE FROM sm_control.organization WHERE organization_id = :organizationId",
                    Map.of("organizationId", fixture.organizationId()));
        }
    }

    @Test
    void theActualRuntimeRoleCanInsertAndSelectButNeverUpdateOrDelete() {
        FixturePrincipal fixture = fixturePrincipal();
        UUID resourceId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_control.audit_log "
                        + "(at, actor_principal_id, verb, resource_kind, resource_id, tenant, outcome, detail) "
                        + "VALUES (:at, :actorId, :verb, :resourceKind, :resourceId, :tenant, :outcome, CAST(:detail AS jsonb))",
                parameters(fixture, resourceId));

        Long auditId = runtime.queryForObject("SELECT audit_id FROM sm_control.audit_log WHERE actor_principal_id = :actorId "
                        + "AND resource_id = :resourceId",
                Map.of("actorId", fixture.principalId(), "resourceId", resourceId), Long.class);
        assertThat(auditId).isPositive();
        assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute(
                "UPDATE sm_control.audit_log SET outcome = 'deny' WHERE actor_principal_id = '"
                        + fixture.principalId() + "'"));
        assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute(
                "DELETE FROM sm_control.audit_log WHERE actor_principal_id = '" + fixture.principalId() + "'"));
    }

    @Test
    void anEntryIsWrittenInsideTheCallersOwnTransactionAndRolledBackWithIt() {
        AuditEntry entry = entry();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));

        transaction.executeWithoutResult(status -> {
            auditLog.record(entry);
            assertThat(count(entry)).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(count(entry)).isZero();
    }

    @Test
    void aValidatedDetailRoundTripsAsJson() throws Exception {
        AuditEntry entry = entry(Map.of("reason_code", "operator_request", "count", 2));

        auditLog.record(entry);

        String detail = jdbc.queryForObject("SELECT detail::text FROM sm_control.audit_log "
                        + "WHERE actor_principal_id = :actorId AND resource_id = :resourceId",
                Map.of("actorId", entry.actor().id(), "resourceId", entry.resourceId()), String.class);
        assertThat(JSON.readTree(detail).get("reason_code").asText()).isEqualTo("operator_request");
        assertThat(JSON.readTree(detail).get("count").asLong()).isEqualTo(2);
    }

    @Test
    void noAuditDetailPayloadMayCarryACredentialOrSessionToken() {
        assertThatThrownBy(() -> entry(Map.of("credential", "not-for-storage")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential");
        assertThatThrownBy(() -> entry(Map.of("context", Map.of("sessionToken", "not-for-storage"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context");
        assertThatThrownBy(() -> entry(Map.of("reason_code", Map.of("code", "operator_request"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flat scalar");
        assertThatThrownBy(() -> entry(Map.of("reason_code", "secret-token-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid value");
    }

    @Test
    void everyHighConsequenceVerbHasAnAuditEntryKind() {
        for (Permission permission : Permission.values()) {
            if (permission.consequence() == ConsequenceClass.HIGH) {
                assertThat(AuditEntry.kindFor(permission)).isNotNull();
            }
        }
    }

    @Test
    void standardVerbsNeedNoAuditEntryKind() {
        assertThat(AuditEntry.kindFor(Permission.SOLUTION_VIEW)).isNull();
    }

    @Test
    void kindForRefusesAnUndecidedVerbRatherThanTreatingItAsStandard() {
        assertThatThrownBy(() -> AuditEntry.kindFor(ConsequenceClass.UNDECIDED))
                .isInstanceOf(ConsequenceClassUndecidedException.class);
    }

    @Test
    void userDeactivateHasAnAuditKindBecauseItIsHighConsequence() {
        assertThat(AuditEntry.kindFor(Permission.USER_DEACTIVATE)).isNotNull();
    }

    @Test
    void everyVerbsClassIsOneOfTheThreeWithNoNullAndNoDefault() {
        for (Permission permission : Permission.values()) {
            assertThat(permission.consequence()).isIn(
                    ConsequenceClass.STANDARD, ConsequenceClass.HIGH, ConsequenceClass.UNDECIDED);
        }
    }

    @Test
    void mutationGrantingUpdateMakesTheRuntimeRefusalTestFail() {
        FixturePrincipal fixture = fixturePrincipal();
        UUID resourceId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_control.audit_log "
                        + "(at, actor_principal_id, verb, resource_kind, resource_id, tenant, outcome, detail) "
                        + "VALUES (:at, :actorId, :verb, :resourceKind, :resourceId, :tenant, :outcome, CAST(:detail AS jsonb))",
                parameters(fixture, resourceId));
        jdbc.getJdbcTemplate().execute("GRANT UPDATE ON sm_control.audit_log TO sm_cp_runtime");
        try {
            assertThatThrownBy(() -> assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute(
                    "UPDATE sm_control.audit_log SET outcome = 'deny' WHERE actor_principal_id = '"
                            + fixture.principalId() + "'")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
        } finally {
            jdbc.getJdbcTemplate().execute("REVOKE UPDATE ON sm_control.audit_log FROM sm_cp_runtime");
        }
    }

    private AuditEntry entry() {
        return entry(Map.of("reason_code", "operator_request"));
    }

    private AuditEntry entry(Map<String, Object> detail) {
        FixturePrincipal fixture = fixturePrincipal();
        return new AuditEntry(Instant.parse("2026-09-17T00:00:00Z"), new PrincipalRef(fixture.principalId()),
                Permission.USER_DEACTIVATE, ResourceKind.PRINCIPAL, UUID.randomUUID(),
                new TenantId(fixture.organizationId()), AuditEntry.Outcome.ALLOW, detail);
    }

    private int count(AuditEntry entry) {
        return jdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE actor_principal_id = :actorId "
                        + "AND resource_id = :resourceId",
                Map.of("actorId", entry.actor().id(), "resourceId", entry.resourceId()), Integer.class);
    }

    private FixturePrincipal fixturePrincipal() {
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:organizationId)",
                Map.of("organizationId", organizationId));
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                        + "VALUES (:principalId, :organizationId, 'idp', :username)",
                new MapSqlParameterSource().addValue("principalId", principalId)
                        .addValue("organizationId", organizationId)
                        .addValue("username", "audit-" + principalId + "@example.test"));
        FixturePrincipal fixture = new FixturePrincipal(organizationId, principalId);
        fixturePrincipals.add(fixture);
        return fixture;
    }

    private static MapSqlParameterSource parameters(FixturePrincipal fixture, UUID resourceId) {
        return new MapSqlParameterSource()
                .addValue("at", Timestamp.from(Instant.parse("2026-09-17T00:00:00Z")))
                .addValue("actorId", fixture.principalId())
                .addValue("verb", Permission.USER_DEACTIVATE.id())
                .addValue("resourceKind", ResourceKind.PRINCIPAL.name().toLowerCase())
                .addValue("resourceId", resourceId)
                .addValue("tenant", fixture.organizationId())
                .addValue("outcome", "allow")
                .addValue("detail", "{}");
    }

    private void assertRuntimeRefused(SqlAction action) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            assertThatThrownBy(action::run).satisfies(error -> assertSqlState(error, "42501"));
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

    private record FixturePrincipal(UUID organizationId, UUID principalId) { }
}
