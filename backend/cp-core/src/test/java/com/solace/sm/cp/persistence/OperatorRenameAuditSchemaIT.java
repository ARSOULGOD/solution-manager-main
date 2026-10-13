package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.identity.AuditOperations;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code V020} (Fable F-2, {@code OR-131} Q1): a no-actor {@code HIERARCHY_NODE_RENAMED} is admitted from the operator
 * command's client address on {@code organization}, and nowhere else. Every refusal carries the {@code OR025} SQLSTATE and
 * the actor wording, so a different guard firing is not a pass.
 */
class OperatorRenameAuditSchemaIT {

    private static final String KEY = "operator-rename-audit-schema-it";
    private static final String HTTP_ADDRESS = "203.0.113.50";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "020");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final TransactionTemplate runtimeTx = new TransactionTemplate(
            new DataSourceTransactionManager((DataSource) runtime.getJdbcTemplate().getDataSource()));

    private record Refusal(String sqlState, String message) {
    }

    private UUID organization() {
        UUID organization = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                organization, "Org-" + organization);
        return organization;
    }

    private void write(String operation, String client, String sql, Object... args) {
        UUID correlation = UUID.randomUUID();
        runtimeTx.executeWithoutResult(status -> {
            new JdbcAuditContext(runtime).set(null, operation, correlation, client);
            runtime.getJdbcTemplate().update(sql, args);
        });
    }

    private Refusal refusalOf(Runnable work) {
        try {
            work.run();
        } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql) {
                    return new Refusal(sql.getSQLState(), String.valueOf(sql.getMessage()));
                }
            }
            throw e;
        }
        throw new AssertionError("expected the write to be refused, but it succeeded");
    }

    @Test
    void anOperatorCliRenameOfAnOrganizationWithNoActorIsAcceptedAndAudited() {
        UUID organization = organization();

        write(AuditOperations.HIERARCHY_NODE_RENAMED, "operator-cli",
                "UPDATE sm_control.organization SET name = ? WHERE organization_id = ?", "Renamed " + organization,
                organization);

        List<Map<String, Object>> rows = migrator.queryForList(
                "SELECT audit_operation, actor_principal_id, client_address, db_role FROM sm_control.change_audit"
                        + " WHERE table_name = 'organization' AND operation = 'UPDATE' AND row_key ->> 'organization_id' = :o",
                Map.of("o", organization.toString()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("audit_operation")).isEqualTo("HIERARCHY_NODE_RENAMED");
        assertThat(rows.get(0).get("actor_principal_id")).isNull();
        assertThat(rows.get(0).get("client_address")).isEqualTo("operator-cli");
        assertThat(rows.get(0).get("db_role")).isEqualTo("sm_cp_runtime");
    }

    @Test
    void anHttpPathRenameWithNoActorIsStillRefused() {
        UUID organization = organization();

        Refusal refusal = refusalOf(() -> write(AuditOperations.HIERARCHY_NODE_RENAMED, HTTP_ADDRESS,
                "UPDATE sm_control.organization SET name = ? WHERE organization_id = ?", "X " + organization,
                organization));

        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
        assertThat(refusal.message).contains("requires an actor principal");
        assertThat(migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", organization), String.class)).isEqualTo("Org-" + organization);
    }

    @Test
    void theExemptionCoversNeitherAnotherTableNorAnotherOperation() {
        UUID organization = organization();
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'unit')",
                organization, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment "
                + "(organization_id, organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'env')", organization, unit,
                environment);

        Refusal onUnit = refusalOf(() -> write(AuditOperations.HIERARCHY_NODE_RENAMED, "operator-cli",
                "UPDATE sm_control.organization_unit SET name = 'u' WHERE organization_unit_id = ?", unit));
        Refusal onEnvironment = refusalOf(() -> write(AuditOperations.HIERARCHY_NODE_RENAMED, "operator-cli",
                "UPDATE sm_control.environment SET name = 'e' WHERE environment_id = ?", environment));
        Refusal otherOperation = refusalOf(() -> write(AuditOperations.HIERARCHY_NODE_CREATED, "operator-cli",
                "UPDATE sm_control.organization SET name = 'o' WHERE organization_id = ?", organization));

        for (Refusal refusal : List.of(onUnit, onEnvironment, otherOperation)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("requires an actor principal");
        }
    }
}
