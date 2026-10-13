package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.identity.AuditOperations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code V017} (plan 012 T4, {@code OR-121}): the control plane's half of undeclare. An instance's node is retired,
 * never deleted; the runtime role may set exactly two columns; the write is audited as {@code INSTANCE_UNDECLARED};
 * a retired row is terminal; and {@code record_change_audit()}'s replacement dropped nothing it accepted before.
 *
 * <p><b>Every write under test runs as {@code sm_cp_runtime}</b> ({@link SmControlPostgres}): a write by the migrator
 * or the owner is exempt from OR-25 and would prove nothing about the role the application connects as. Fixture rows
 * are seeded as the migrator.
 */
class InstanceRetirementSchemaIT {

    private static final String CACHE_KEY = "instance-retirement-schema-it";
    private static final String V014 = "V014__dashboards_and_operator_metrics_verbs.sql";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(CACHE_KEY, "017");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

    @Test
    void theRuntimeRoleRetiresAnInstanceAndTheWriteIsAuditedAsInstanceUndeclared() {
        Node node = node();
        UUID actor = principal(node.organization);
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.INSTANCE_UNDECLARED, actor, correlation);
            assertThat(runtime.getJdbcTemplate().update(
                    "UPDATE sm_control.instance SET retired_at = now(), retired_by = ? WHERE instance_id = ?",
                    actor, node.instance)).isEqualTo(1);
        });

        Map<String, Object> row = migrator.queryForMap("SELECT audit_operation, actor_principal_id, correlation_id,"
                + " client_address, db_role, operation, table_name, changed_columns::text AS changed,"
                + " row_key::text AS row_key FROM sm_control.change_audit WHERE correlation_id = :c",
                Map.of("c", correlation));
        assertThat(row.get("audit_operation")).isEqualTo("INSTANCE_UNDECLARED");
        assertThat(row.get("actor_principal_id")).isEqualTo(actor);
        assertThat(row.get("db_role")).isEqualTo("sm_cp_runtime");
        assertThat(row.get("table_name")).isEqualTo("instance");
        assertThat(row.get("operation")).isEqualTo("UPDATE");
        assertThat(row.get("changed")).asString().contains("retired_at").contains("retired_by")
                .doesNotContain("solution_id");
        assertThat(row.get("row_key")).asString().contains(node.instance.toString());
    }

    @Test
    void aRuntimeRetireWithoutAuditContextIsRefusedOr025() {
        Node node = node();
        UUID actor = principal(node.organization);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status ->
                runtime.getJdbcTemplate().update(
                        "UPDATE sm_control.instance SET retired_at = now(), retired_by = ? WHERE instance_id = ?",
                        actor, node.instance)));

        assertThat(refusal.sqlState).isEqualTo("OR025");
        assertThat(refusal.message).contains("no known audit_operation");
        assertThat(retiredAt(node)).as("nothing was written").isNull();
    }

    @Test
    void theRuntimeRoleMayUpdateOnlyTheTwoRetirementColumnsOfAnInstance() {
        Node node = node();
        UUID actor = principal(node.organization);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.INSTANCE_UNDECLARED, actor, UUID.randomUUID());
            runtime.getJdbcTemplate().update("UPDATE sm_control.instance SET solution_id = ? WHERE instance_id = ?",
                    UUID.randomUUID(), node.instance);
        }));
        Refusal delete = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.INSTANCE_UNDECLARED, actor, UUID.randomUUID());
            runtime.getJdbcTemplate().update("DELETE FROM sm_control.instance WHERE instance_id = ?", node.instance);
        }));

        assertThat(refusal.sqlState).as("column-scoped UPDATE: permission denied").isEqualTo("42501");
        assertThat(delete.sqlState).as("the control plane never deletes a governance node").isEqualTo("42501");
    }

    @Test
    void retiredAtAndRetiredByAreSetTogetherOrNotAtAll() {
        Node node = node();
        UUID actor = principal(node.organization);

        Refusal onlyAt = refusalOf(() -> migrator.getJdbcTemplate().update(
                "UPDATE sm_control.instance SET retired_at = now() WHERE instance_id = ?", node.instance));
        Refusal onlyBy = refusalOf(() -> migrator.getJdbcTemplate().update(
                "UPDATE sm_control.instance SET retired_by = ? WHERE instance_id = ?", actor, node.instance));

        assertThat(onlyAt.sqlState).isEqualTo("23514");
        assertThat(onlyAt.message).contains("instance_retired_together");
        assertThat(onlyBy.sqlState).isEqualTo("23514");
    }

    @Test
    void aRetiredInstanceRowIsTerminalEvenForTheOwnerBecauseATriggerFiresRegardlessOfPrivilege() {
        Node node = node();
        UUID actor = principal(node.organization);
        migrator.getJdbcTemplate().update(
                "UPDATE sm_control.instance SET retired_at = now(), retired_by = ? WHERE instance_id = ?",
                actor, node.instance);

        Refusal unretire = refusalOf(() -> migrator.getJdbcTemplate().update(
                "UPDATE sm_control.instance SET retired_at = NULL, retired_by = NULL WHERE instance_id = ?",
                node.instance));
        Refusal retireAgain = refusalOf(() -> migrator.getJdbcTemplate().update(
                "UPDATE sm_control.instance SET retired_at = now() WHERE instance_id = ?", node.instance));

        assertThat(unretire.sqlState).isEqualTo("23514");
        assertThat(unretire.message).contains("terminal");
        assertThat(retireAgain.sqlState).isEqualTo("23514");
        assertThat(retiredAt(node)).as("still retired").isNotNull();
    }

    @Test
    void everyOperationTheAuditFunctionAcceptedBeforeV017IsStillAcceptedByTheRuntimeRole() {
        List<String> previous = allowListOf(V014);
        assertThat(previous).as("the previous migration's own allow-list, read from its file, not typed here")
                .contains("LOGIN_SUCCEEDED", "ENTITLEMENT_SET", "DASHBOARD_RESET").hasSizeGreaterThanOrEqualTo(15);
        Node node = node();
        UUID actor = principal(node.organization);
        String functionBody = migrator.queryForObject("SELECT p.prosrc FROM pg_catalog.pg_proc p JOIN"
                + " pg_catalog.pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'sm_control'"
                + " AND p.proname = 'record_change_audit'", Map.of(), String.class);
        String vocabulary = migrator.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint"
                + " WHERE conname = 'change_audit_audit_operation'", Map.of(), String.class);

        List<String> unaccepted = new ArrayList<>();
        for (String operation : previous) {
            if (!functionBody.contains("'" + operation + "'") || !vocabulary.contains("'" + operation + "'")) {
                unaccepted.add(operation + " (missing from the function or the CHECK)");
                continue;
            }
            // Behaviour, not text: the runtime role performs an audited write under this operation and the trigger
            // accepts it. login_throttle is the one table every operation of the list may legally touch with an actor.
            try {
                runtimeTx.executeWithoutResult(status -> {
                    setContext(operation, actor, UUID.randomUUID());
                    runtime.getJdbcTemplate().update("INSERT INTO sm_control.login_throttle (subject_hash,"
                            + " window_started_at, failure_count, locked_until) VALUES (?, now(), 0, NULL)",
                            randomSubjectHash());
                    status.setRollbackOnly();
                });
            } catch (DataAccessException e) {
                unaccepted.add(operation + " (" + e.getMostSpecificCause().getMessage() + ")");
            }
        }

        assertThat(unaccepted).as("an operation V014 accepted and V017 does not").isEmpty();
        assertThat(functionBody).contains("'INSTANCE_UNDECLARED'");
        assertThat(vocabulary).contains("'INSTANCE_UNDECLARED'");
    }

    @Test
    void anOperationNobodyApprovedIsStillRefusedAfterTheReplacement() {
        Node node = node();
        UUID actor = principal(node.organization);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext("INSTANCE_UNDECLARE", actor, UUID.randomUUID());   // one letter short of the real name
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.login_throttle (subject_hash, window_started_at,"
                    + " failure_count, locked_until) VALUES (?, now(), 0, NULL)", randomSubjectHash());
        }));

        assertThat(refusal.sqlState).isEqualTo("OR025");
        assertThat(refusal.message).contains("no known audit_operation");
    }

    // ---------------------------------------------------------------- harness

    private record Node(UUID organization, UUID unit, UUID environment, UUID solution, UUID instance) {
    }

    private record Refusal(String sqlState, String message) {
    }

    private Node node() {
        Node n = new Node(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        var jdbc = migrator.getJdbcTemplate();
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)", n.organization);
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                n.organization, n.unit);
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                + " VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", n.organization, n.unit, n.environment);
        jdbc.update("INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id,"
                + " solution_id) VALUES (?, ?, ?, ?)", n.organization, n.unit, n.environment, n.solution);
        jdbc.update("INSERT INTO sm_control.instance (organization_id, organization_unit_id, environment_id,"
                + " solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", n.organization, n.unit, n.environment,
                n.solution, n.instance);
        return n;
    }

    private UUID principal(UUID organization) {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", id, organization, "retirement-" + id);
        return id;
    }

    private Object retiredAt(Node node) {
        return migrator.queryForMap("SELECT retired_at FROM sm_control.instance WHERE instance_id = :i",
                Map.of("i", node.instance)).get("retired_at");
    }

    /** The same transaction-local settings {@code JdbcAuditContext} writes, spelled out. */
    private void setContext(String operation, UUID actor, UUID correlation) {
        runtime.getJdbcTemplate().queryForList(
                "SELECT set_config('sm.audit.actor', ?, true), set_config('sm.audit.operation', ?, true),"
                        + " set_config('sm.audit.correlation_id', ?, true),"
                        + " set_config('sm.audit.client_address', ?, true)",
                actor == null ? "" : actor.toString(), operation, correlation.toString(), "203.0.113.60");
    }

    private Refusal refusalOf(Runnable work) {
        try {
            work.run();
        } catch (DataAccessException | IllegalStateException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql) {
                    return new Refusal(sql.getSQLState(), String.valueOf(sql.getMessage()));
                }
            }
            throw e;
        }
        throw new AssertionError("expected the write to be refused, but it succeeded");
    }

    private static String randomSubjectHash() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The OR-25 allow-list of a migration's {@code record_change_audit()}, read from the file itself. */
    private static List<String> allowListOf(String migration) {
        try (InputStream in = InstanceRetirementSchemaIT.class.getClassLoader()
                .getResourceAsStream("db/migration/sm_control/" + migration)) {
            String sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("v_operation NOT IN (");
            int end = sql.indexOf(") THEN", start);
            Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(sql.substring(start, end));
            List<String> operations = new ArrayList<>();
            while (quoted.find()) {
                operations.add(quoted.group(1));
            }
            return operations;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
