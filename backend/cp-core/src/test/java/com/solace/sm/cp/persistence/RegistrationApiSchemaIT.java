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
 * {@code V018} (plan 013 T2.1, {@code OR-121}): the control-plane half of the registration API's schema.
 *
 * <p><b>Every write under test runs as {@code sm_cp_runtime}</b> ({@link SmControlPostgres}): a write by the migrator
 * or the owner is exempt from {@code OR-25} and would prove nothing about the role the application connects as.
 * Fixture rows are seeded as the migrator. Each test is named for the fact it proves; the ones that can only fail if
 * the migration is wrong carry the refusal's SQLSTATE, so a different guard firing is not a pass.
 */
class RegistrationApiSchemaIT {

    private static final String CACHE_KEY = "registration-api-schema-it";
    private static final String VERSION = "018";
    private static final String V017 = "V017__instance_retirement.sql";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(CACHE_KEY, VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

    // ---------------------------------------------------------------- hierarchy writes

    @Test
    void theRuntimeRoleCreatesASubOrganizationAndAnEnvironmentAndBothWritesAreAuditedAsHierarchyNodeCreated() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID unitCorrelation = UUID.randomUUID();
        UUID environmentCorrelation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_CREATED, actor, unitCorrelation, "203.0.113.61");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                    + " (organization_id, organization_unit_id, name) VALUES (?, ?, 'Payments')",
                    org.organization, unit);
        });
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_CREATED, actor, environmentCorrelation, "203.0.113.61");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.environment"
                    + " (organization_id, organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'Dev')",
                    org.organization, unit, environment);
        });

        Map<String, Object> unitRow = auditRow(unitCorrelation);
        assertThat(unitRow).containsEntry("audit_operation", "HIERARCHY_NODE_CREATED")
                .containsEntry("table_name", "organization_unit").containsEntry("operation", "INSERT")
                .containsEntry("actor_principal_id", actor).containsEntry("db_role", "sm_cp_runtime")
                .containsEntry("client_address", "203.0.113.61");
        assertThat(unitRow.get("row_key").toString()).contains(unit.toString());
        Map<String, Object> environmentRow = auditRow(environmentCorrelation);
        assertThat(environmentRow).containsEntry("audit_operation", "HIERARCHY_NODE_CREATED")
                .containsEntry("table_name", "environment");
        assertThat(environmentRow.get("row_key").toString()).contains(environment.toString());
    }

    @Test
    void aSubOrganizationIsTopLevelOnlyBecauseTheRuntimeRoleHoldsNoInsertOnParentUnitId() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID parent = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                + " (organization_id, organization_unit_id, name) VALUES (?, ?, ?)", org.organization, parent,
                "parent-" + parent);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_CREATED, actor, UUID.randomUUID(), "203.0.113.61");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                    + " (organization_id, organization_unit_id, parent_unit_id, name) VALUES (?, ?, ?, 'Nested')",
                    org.organization, UUID.randomUUID(), parent);
        }));

        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("42501");
    }

    @Test
    void theRuntimeRoleRenamesAllThreeNodeKindsAndTheWriteIsAuditedAsHierarchyNodeRenamed() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_RENAMED, actor, correlation, "203.0.113.62");
            assertThat(runtime.getJdbcTemplate().update(
                    "UPDATE sm_control.organization SET name = ? WHERE organization_id = ?", "Org-" + correlation,
                    org.organization)).isEqualTo(1);
            assertThat(runtime.getJdbcTemplate().update(
                    "UPDATE sm_control.organization_unit SET name = ? WHERE organization_unit_id = ?",
                    "Unit-" + correlation, org.unit)).isEqualTo(1);
            assertThat(runtime.getJdbcTemplate().update(
                    "UPDATE sm_control.environment SET name = ? WHERE environment_id = ?", "Env-" + correlation,
                    org.environment)).isEqualTo(1);
        });

        List<Map<String, Object>> rows = migrator.queryForList("SELECT table_name, audit_operation, changed_columns::text"
                + " AS changed, actor_principal_id FROM sm_control.change_audit WHERE correlation_id = :c"
                + " ORDER BY table_name", Map.of("c", correlation));
        assertThat(rows).extracting(r -> r.get("table_name")).containsExactly("environment", "organization",
                "organization_unit");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r).containsEntry("audit_operation", "HIERARCHY_NODE_RENAMED")
                    .containsEntry("actor_principal_id", actor);
            assertThat(r.get("changed").toString()).isEqualTo("{name}");
        });
    }

    @Test
    void aRenameMayChangeTheNameAndNothingElseAndNoNodeIsEverDeletedByTheRuntimeRole() {
        Org org = org();
        UUID actor = principal(org.organization);

        List<String> forbidden = List.of(
                "UPDATE sm_control.organization_unit SET organization_id = organization_id",
                "UPDATE sm_control.organization_unit SET parent_unit_id = NULL",
                "UPDATE sm_control.environment SET organization_unit_id = organization_unit_id",
                "UPDATE sm_control.environment SET environment_id = environment_id",
                "DELETE FROM sm_control.organization_unit WHERE organization_unit_id = '" + org.unit + "'",
                "DELETE FROM sm_control.environment WHERE environment_id = '" + org.environment + "'",
                "DELETE FROM sm_control.organization WHERE organization_id = '" + org.organization + "'");
        for (String statement : forbidden) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                setContext(AuditOperations.HIERARCHY_NODE_RENAMED, actor, UUID.randomUUID(), "203.0.113.62");
                runtime.getJdbcTemplate().update(statement);
            }));
            assertThat(refusal.sqlState).as(statement + " :: " + refusal.message).isEqualTo("42501");
        }
    }

    @Test
    void anOrganizationCannotBeCreatedByTheRuntimeRoleBecauseOq5IsRenameOnly() {
        UUID actor = principal(org().organization);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_CREATED, actor, UUID.randomUUID(), "203.0.113.61");
            runtime.getJdbcTemplate().update(
                    "INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'Rogue')", UUID.randomUUID());
        }));

        assertThat(refusal.sqlState).isEqualTo("42501");
    }

    @Test
    void aHierarchyWriteWithoutAuditContextIsRefusedOr025AndNothingIsWritten() {
        Org org = org();

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status ->
                runtime.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                        + " (organization_id, organization_unit_id, name) VALUES (?, ?, 'No context')",
                        org.organization, UUID.randomUUID())));

        assertThat(refusal.sqlState).isEqualTo("OR025");
        assertThat(refusal.message).contains("no known audit_operation");
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.organization_unit WHERE name = :n",
                Map.of("n", "No context"), Integer.class)).isZero();
    }

    /**
     * W2 review W2D-3: the plan's "the trigger refuses each new and each of plan 012's operations' writes without
     * context (OR-25)" for <b>every</b> write V018 newly grants, not only the unit INSERT: each is run as the runtime
     * role with no audit context, refused with OR025, and leaves its row exactly as it was.
     */
    @Test
    void everyWriteV018NewlyGrantsIsRefusedOr025WithoutAuditContextAndChangesNothing() {
        Org org = org();
        UUID victim = principal(org.organization);
        UUID binding = UUID.randomUUID();
        // scope_one_organization: an organization has exactly one organization-kind scope, so both bindings share it.
        UUID bindingScope = organizationScope(org.organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id,"
                + " scope_id) VALUES (?, ?, '00000000-0000-4000-8000-000000000003', ?)", binding, victim,
                bindingScope);
        UUID newPrincipal = UUID.randomUUID();
        UUID newBinding = UUID.randomUUID();
        Map<String, Object[]> writes = new java.util.LinkedHashMap<>();
        writes.put("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                + " VALUES (?, ?, ?, 'No context')", new Object[] {org.organization, org.unit, UUID.randomUUID()});
        writes.put("UPDATE sm_control.organization SET name = 'No context' WHERE organization_id = ?",
                new Object[] {org.organization});
        writes.put("UPDATE sm_control.organization_unit SET name = 'No context' WHERE organization_unit_id = ?",
                new Object[] {org.unit});
        writes.put("UPDATE sm_control.environment SET name = 'No context' WHERE environment_id = ?",
                new Object[] {org.environment});
        writes.put("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash,"
                + " must_rotate) VALUES (?, ?, 'local', 'no-context', '{argon2id}x', true)",
                new Object[] {newPrincipal, org.organization});
        writes.put("UPDATE sm_control.principal SET status = 'deactivated', deactivated_at = now() WHERE id = ?",
                new Object[] {victim});
        writes.put("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                + " VALUES (?, ?, '00000000-0000-4000-8000-000000000003', ?)",
                new Object[] {newBinding, principal(org.organization), bindingScope});
        writes.put("DELETE FROM sm_control.role_binding WHERE binding_id = ?", new Object[] {binding});

        for (Map.Entry<String, Object[]> write : writes.entrySet()) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(
                    status -> runtime.getJdbcTemplate().update(write.getKey(), write.getValue())));
            assertThat(refusal.sqlState).as(write.getKey() + " :: " + refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("no known audit_operation");
        }

        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.environment WHERE name = 'No context'",
                Map.of(), Integer.class)).isZero();
        assertThat(migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", org.organization), String.class)).isEqualTo(org.organizationName);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.organization_unit"
                + " WHERE name = 'No context'", Map.of(), Integer.class)).isZero();
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.principal WHERE id = :p",
                Map.of("p", newPrincipal), Integer.class)).isZero();
        assertThat(migrator.queryForMap("SELECT status, must_rotate FROM sm_control.principal WHERE id = :p",
                Map.of("p", victim))).containsEntry("status", "active").containsEntry("must_rotate", false);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.role_binding WHERE binding_id = :b",
                Map.of("b", newBinding), Integer.class)).isZero();
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.role_binding WHERE binding_id = :b",
                Map.of("b", binding), Integer.class)).as("the revoke without context did not delete").isEqualTo(1);
    }

    // ---------------------------------------------------------------- per-parent names (OR-123's indexes, used through the runtime role)

    @Test
    void theRuntimeRoleCannotCreateADuplicateSiblingNameIgnoringCaseButMayReuseANameUnderAnotherParent() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID otherUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                + " (organization_id, organization_unit_id, name) VALUES (?, ?, ?)", org.organization, otherUnit,
                "other-" + otherUnit);
        createEnvironmentAsRuntime(actor, org.organization, org.unit, "Staging");

        // The same name under a different parent is accepted: the clash is among siblings only.
        createEnvironmentAsRuntime(actor, org.organization, otherUnit, "Staging");

        Refusal environment = refusalOf(() -> createEnvironmentAsRuntime(actor, org.organization, org.unit, "STAGING"));
        assertThat(environment.sqlState).isEqualTo("23505");
        assertThat(environment.message).contains("environment_name_ci_uq");
        Refusal unit = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_CREATED, actor, UUID.randomUUID(), "203.0.113.61");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                    + " (organization_id, organization_unit_id, name) VALUES (?, ?, ?)", org.organization,
                    UUID.randomUUID(), ("other-" + otherUnit).toUpperCase(java.util.Locale.ROOT));
        }));
        assertThat(unit.sqlState).isEqualTo("23505");
        assertThat(unit.message).contains("organization_unit_name_ci_uq");
        Refusal rename = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_RENAMED, actor, UUID.randomUUID(), "203.0.113.62");
            runtime.getJdbcTemplate().update("UPDATE sm_control.organization SET name = ? WHERE organization_id = ?",
                    org.organizationName.toUpperCase(java.util.Locale.ROOT), org().organization);
        }));
        assertThat(rename.sqlState).isEqualTo("23505");
        assertThat(rename.message).contains("organization_name_ci_uq");
    }

    // ---------------------------------------------------------------- the operator user (W3)

    @Test
    void theRuntimeRoleCreatesAPrincipalAndDeactivatesItAndBothAreAuditedWithNoCredentialHashCopied() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID created = UUID.randomUUID();
        UUID createCorrelation = UUID.randomUUID();
        UUID deactivateCorrelation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.PRINCIPAL_CREATED, actor, createCorrelation, "203.0.113.63");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username,"
                    + " credential_hash, must_rotate) VALUES (?, ?, 'local', ?, '{argon2id}secret-looking-hash', true)",
                    created, org.organization, "operator-" + created);
        });
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.PRINCIPAL_DEACTIVATED, actor, deactivateCorrelation, "203.0.113.63");
            assertThat(runtime.getJdbcTemplate().update("UPDATE sm_control.principal SET status = 'deactivated',"
                    + " deactivated_at = now() WHERE id = ?", created)).isEqualTo(1);
        });

        Map<String, Object> createdRow = auditRow(createCorrelation);
        assertThat(createdRow).containsEntry("audit_operation", "PRINCIPAL_CREATED")
                .containsEntry("table_name", "principal").containsEntry("actor_principal_id", actor);
        assertThat(createdRow.get("new_values").toString()).doesNotContain("secret-looking-hash")
                .doesNotContain("credential_hash\":");
        assertThat(createdRow.get("secret_columns_changed").toString()).contains("credential_hash");
        assertThat(auditRow(deactivateCorrelation)).containsEntry("audit_operation", "PRINCIPAL_DEACTIVATED");
    }

    @Test
    void aCredentialResetIsAuditedAsPrincipalCredentialResetThroughTheExistingColumnGrant() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID target = principal(org.organization);
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.PRINCIPAL_CREDENTIAL_RESET, actor, correlation, "203.0.113.63");
            assertThat(runtime.getJdbcTemplate().update("UPDATE sm_control.principal SET credential_hash ="
                    + " '{argon2id}reset', must_rotate = true WHERE id = ?", target)).isEqualTo(1);
        });

        Map<String, Object> row = auditRow(correlation);
        assertThat(row).containsEntry("audit_operation", "PRINCIPAL_CREDENTIAL_RESET");
        assertThat(row.get("secret_columns_changed").toString()).contains("credential_hash");
    }

    @Test
    void thePrincipalGrantsAreExactlyInsertAndTwoUpdateColumnsAndNeverDeleteOrAnyOtherColumn() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID victim = principal(org.organization);

        List<String> forbidden = List.of(
                "UPDATE sm_control.principal SET username = 'renamed' WHERE id = '" + victim + "'",
                "UPDATE sm_control.principal SET organization_id = organization_id WHERE id = '" + victim + "'",
                "UPDATE sm_control.principal SET source = source WHERE id = '" + victim + "'",
                "DELETE FROM sm_control.principal WHERE id = '" + victim + "'");
        for (String statement : forbidden) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                setContext(AuditOperations.PRINCIPAL_CREATED, actor, UUID.randomUUID(), "203.0.113.63");
                runtime.getJdbcTemplate().update(statement);
            }));
            assertThat(refusal.sqlState).as(statement + " :: " + refusal.message).isEqualTo("42501");
        }
    }

    @Test
    void theRuntimeRoleGrantsAndRevokesARoleBindingAndRoleBindingIsTheOnlyTableItMayDeleteFrom() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID subject = principal(org.organization);
        UUID scope = organizationScope(org.organization);
        UUID binding = UUID.randomUUID();
        UUID grantCorrelation = UUID.randomUUID();
        UUID revokeCorrelation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ROLE_BINDING_GRANTED, actor, grantCorrelation, "203.0.113.64");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id,"
                    + " scope_id) VALUES (?, ?, '00000000-0000-4000-8000-000000000003', ?)", binding, subject, scope);
        });
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ROLE_BINDING_REVOKED, actor, revokeCorrelation, "203.0.113.64");
            assertThat(runtime.getJdbcTemplate().update("DELETE FROM sm_control.role_binding WHERE binding_id = ?",
                    binding)).isEqualTo(1);
        });

        assertThat(auditRow(grantCorrelation)).containsEntry("audit_operation", "ROLE_BINDING_GRANTED")
                .containsEntry("table_name", "role_binding").containsEntry("operation", "INSERT");
        Map<String, Object> revoked = auditRow(revokeCorrelation);
        assertThat(revoked).containsEntry("audit_operation", "ROLE_BINDING_REVOKED")
                .containsEntry("operation", "DELETE");
        assertThat(revoked.get("old_values").toString()).contains(binding.toString());

        // No other table gained a DELETE, and a binding cannot be edited in place (a change is revoke then grant).
        for (String statement : List.of(
                "DELETE FROM sm_control.role", "DELETE FROM sm_control.role_permission",
                "DELETE FROM sm_control.scope", "DELETE FROM sm_control.session",
                "DELETE FROM sm_control.target", "DELETE FROM sm_control.entitlement",
                "UPDATE sm_control.role_binding SET role_id = role_id")) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                setContext(AuditOperations.ROLE_BINDING_REVOKED, actor, UUID.randomUUID(), "203.0.113.64");
                runtime.getJdbcTemplate().update(statement);
            }));
            assertThat(refusal.sqlState).as(statement + " :: " + refusal.message).isEqualTo("42501");
        }
    }

    // ---------------------------------------------------------------- the merged audit function

    @Test
    void everyOperationV017AcceptedIsStillAcceptedByTheRuntimeRoleAndEachOfTheSevenNewOnesIsToo() {
        List<String> previous = allowListOf(V017);
        assertThat(previous).as("V017's own allow-list, read from its file, not typed here")
                .contains("LOGIN_SUCCEEDED", "ENTITLEMENT_SET", "DASHBOARD_RESET", "INSTANCE_UNDECLARED")
                .hasSizeGreaterThanOrEqualTo(16);
        List<String> expected = new ArrayList<>(previous);
        expected.addAll(List.of("HIERARCHY_NODE_CREATED", "HIERARCHY_NODE_RENAMED", "PRINCIPAL_CREATED",
                "PRINCIPAL_DEACTIVATED", "PRINCIPAL_CREDENTIAL_RESET", "ROLE_BINDING_GRANTED",
                "ROLE_BINDING_REVOKED"));
        assertThat(allowListOf("V018__registration_api.sql")).as("V018's allow-list is V017's plus exactly the seven")
                .containsExactlyInAnyOrderElementsOf(expected);
        UUID actor = principal(org().organization);
        String functionBody = migrator.queryForObject("SELECT p.prosrc FROM pg_catalog.pg_proc p JOIN"
                + " pg_catalog.pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'sm_control'"
                + " AND p.proname = 'record_change_audit'", Map.of(), String.class);
        String vocabulary = migrator.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint"
                + " WHERE conname = 'change_audit_audit_operation'", Map.of(), String.class);

        List<String> unaccepted = new ArrayList<>();
        for (String operation : expected) {
            if (!functionBody.contains("'" + operation + "'") || !vocabulary.contains("'" + operation + "'")) {
                unaccepted.add(operation + " (missing from the function or the CHECK)");
                continue;
            }
            // Behaviour, not text: the runtime role performs an audited write under this operation and the trigger
            // accepts it. login_throttle is the one table every operation of the list may legally touch with an actor.
            try {
                runtimeTx.executeWithoutResult(status -> {
                    setContext(operation, actor, UUID.randomUUID(), "203.0.113.65");
                    runtime.getJdbcTemplate().update("INSERT INTO sm_control.login_throttle (subject_hash,"
                            + " window_started_at, failure_count, locked_until) VALUES (?, now(), 0, NULL)",
                            randomSubjectHash());
                    status.setRollbackOnly();
                });
            } catch (DataAccessException e) {
                unaccepted.add(operation + " (" + e.getMostSpecificCause().getMessage() + ")");
            }
        }

        assertThat(unaccepted).as("an operation V017 accepted, or one of the seven, that V018 does not").isEmpty();
    }

    @Test
    void anOperationNobodyApprovedIsStillRefusedAfterTheReplacement() {
        UUID actor = principal(org().organization);

        for (String unknown : List.of("HIERARCHY_NODE_CREATE", "HIERARCHY_NODE_DELETED", "ROLE_BINDING_UPDATED",
                "PRINCIPAL_DELETED")) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                setContext(unknown, actor, UUID.randomUUID(), "203.0.113.65");
                runtime.getJdbcTemplate().update("INSERT INTO sm_control.login_throttle (subject_hash,"
                        + " window_started_at, failure_count, locked_until) VALUES (?, now(), 0, NULL)",
                        randomSubjectHash());
            }));
            assertThat(refusal.sqlState).as(unknown).isEqualTo("OR025");
            assertThat(refusal.message).contains("no known audit_operation");
        }
    }

    @Test
    void theAuditOperationVocabularyIsClosedOnTheTableToo() {
        Refusal refusal = refusalOf(() -> migrator.getJdbcTemplate().update("INSERT INTO sm_control.change_audit"
                + " (recorded_at, txid, table_name, operation, row_key, changed_columns, audit_operation, db_role)"
                + " VALUES (now(), 1, 'principal', 'INSERT', '{}', ARRAY[]::text[], 'PRINCIPAL_DELETED', 'x')"));

        assertThat(refusal.sqlState).isEqualTo("23514");
        assertThat(refusal.message).contains("change_audit_audit_operation");
    }

    // ---------------------------------------------------------------- ENTITLEMENT_SET with no actor (R16)

    @Test
    void aNoActorEntitlementSetIsAcceptedFromTheOperatorCommandAndRefusedFromAnyOtherClientAddress() {
        Org org = org();
        UUID setBy = principal(org.organization);
        UUID command = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, null, command, "operator-cli");
            insertEntitlement(org.organization, "cache", 4, setBy);
        });
        assertThat(auditRow(command)).containsEntry("audit_operation", "ENTITLEMENT_SET")
                .containsEntry("client_address", "operator-cli").containsEntry("actor_principal_id", null);

        for (String address : List.of("203.0.113.66", "127.0.0.1", "OPERATOR-CLI", "operator-cli ")) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                setContext(AuditOperations.ENTITLEMENT_SET, null, UUID.randomUUID(), address);
                runtime.getJdbcTemplate().update("UPDATE sm_control.entitlement SET max_instances = 9, set_by = ?,"
                        + " set_at = now() WHERE organization_id = ?", setBy, org.organization);
            }));
            assertThat(refusal.sqlState).as("client address '" + address + "'").isEqualTo("OR025");
            assertThat(refusal.message).contains("requires an actor principal");
        }
        assertThat(migrator.queryForObject("SELECT max_instances FROM sm_control.entitlement WHERE organization_id = :o",
                Map.of("o", org.organization), Integer.class)).as("none of the refused updates landed").isEqualTo(4);
    }

    @Test
    void anEntitlementSetWithAnActorIsAcceptedFromAnyClientAddressAndTheExemptionStaysOnTheEntitlementTable() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, actor, correlation, "203.0.113.67");
            insertEntitlement(org.organization, "cache", 2, actor);
        });
        assertThat(auditRow(correlation)).containsEntry("actor_principal_id", actor);

        // operator-cli with no actor is still the entitlement table only.
        Refusal elsewhere = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, null, UUID.randomUUID(), "operator-cli");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.login_throttle (subject_hash, window_started_at,"
                    + " failure_count, locked_until) VALUES (?, now(), 0, NULL)", randomSubjectHash());
        }));
        assertThat(elsewhere.sqlState).isEqualTo("OR025");
        assertThat(elsewhere.message).contains("requires an actor principal");
    }

    // ---------------------------------------------------------------- the target name (OQ-11 (b))

    @Test
    void aRetiredTargetFreesItsNameButTwoLiveTargetsStillCannotShareOne() {
        Org org = org();
        UUID actor = principal(org.organization);
        UUID scope = organizationScope(org.organization);
        UUID first = UUID.randomUUID();
        insertTarget(actor, first, org.organization, scope, "edge-1");

        Refusal live = refusalOf(() -> insertTarget(actor, UUID.randomUUID(), org.organization, scope, "edge-1"));
        assertThat(live.sqlState).isEqualTo("23505");
        assertThat(live.message).as("the index keeps the constraint's old name").contains("target_name_once");

        retireTarget(actor, first);
        UUID second = UUID.randomUUID();
        insertTarget(actor, second, org.organization, scope, "edge-1");
        retireTarget(actor, second);
        insertTarget(actor, UUID.randomUUID(), org.organization, scope, "edge-1");

        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.target WHERE organization_id = :o"
                + " AND name = 'edge-1'", Map.of("o", org.organization), Integer.class)).isEqualTo(3);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.target WHERE organization_id = :o"
                + " AND name = 'edge-1' AND retired_at IS NULL", Map.of("o", org.organization), Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theTargetNameIsStillUniquePerOrganizationAndSharedAcrossTwo() {
        Org one = org();
        Org two = org();
        UUID actorOne = principal(one.organization);
        UUID actorTwo = principal(two.organization);
        insertTarget(actorOne, UUID.randomUUID(), one.organization, organizationScope(one.organization), "shared-name");

        insertTarget(actorTwo, UUID.randomUUID(), two.organization, organizationScope(two.organization),
                "shared-name");

        assertThat(migrator.queryForObject("SELECT indexdef FROM pg_catalog.pg_indexes WHERE schemaname = 'sm_control'"
                + " AND indexname = 'target_name_once'", Map.of(), String.class))
                .contains("UNIQUE").contains("(organization_id, name)").contains("WHERE (retired_at IS NULL)");
        assertThat(migrator.queryForObject("SELECT count(*) FROM pg_catalog.pg_constraint WHERE conname ="
                + " 'target_name_once'", Map.of(), Integer.class)).as("no table constraint is left behind").isZero();
    }

    // ---------------------------------------------------------------- harness

    private record Org(UUID organization, String organizationName, UUID unit, UUID environment) {
    }

    private record Refusal(String sqlState, String message) {
    }

    private Org org() {
        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        String name = "Org-" + organization;
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                organization, name);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                + " (organization_id, organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                organization, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                + " environment_id, name) VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", organization, unit,
                environment);
        return new Org(organization, name, unit, environment);
    }

    private UUID principal(UUID organization) {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", id, organization, "registration-" + id);
        return id;
    }

    private UUID organizationScope(UUID organization) {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.scope (scope_id, kind, organization_id)"
                + " VALUES (?, 'organization', ?)", id, organization);
        return id;
    }

    private void createEnvironmentAsRuntime(UUID actor, UUID organization, UUID unit, String name) {
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.HIERARCHY_NODE_CREATED, actor, UUID.randomUUID(), "203.0.113.61");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id,"
                    + " organization_unit_id, environment_id, name) VALUES (?, ?, ?, ?)", organization, unit,
                    UUID.randomUUID(), name);
        });
    }

    private void insertEntitlement(UUID organization, String solution, int max, UUID setBy) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution,"
                + " max_instances, set_by, set_at) VALUES (?, ?, ?, ?, now())", organization, solution, max, setBy);
    }

    private void insertTarget(UUID actor, UUID targetId, UUID organization, UUID scope, String name) {
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.TARGET_REGISTERED, actor, UUID.randomUUID(), "203.0.113.68");
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                    + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?, 'docker', 'ssh-docker', ?, now())", targetId, organization, scope, name,
                    actor);
        });
    }

    private void retireTarget(UUID actor, UUID targetId) {
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.TARGET_RETIRED, actor, UUID.randomUUID(), "203.0.113.68");
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?",
                    targetId);
        });
    }

    private Map<String, Object> auditRow(UUID correlation) {
        return migrator.queryForMap("SELECT audit_operation, actor_principal_id, correlation_id, client_address,"
                + " db_role, operation, table_name, changed_columns::text AS changed, row_key::text AS row_key,"
                + " old_values::text AS old_values, new_values::text AS new_values,"
                + " secret_columns_changed::text AS secret_columns_changed FROM sm_control.change_audit"
                + " WHERE correlation_id = :c ORDER BY change_audit_id LIMIT 1", Map.of("c", correlation));
    }

    /** The same transaction-local settings {@code JdbcAuditContext} writes, spelled out. */
    private void setContext(String operation, UUID actor, UUID correlation, String clientAddress) {
        runtime.getJdbcTemplate().queryForList(
                "SELECT set_config('sm.audit.actor', ?, true), set_config('sm.audit.operation', ?, true),"
                        + " set_config('sm.audit.correlation_id', ?, true),"
                        + " set_config('sm.audit.client_address', ?, true)",
                actor == null ? "" : actor.toString(), operation, correlation.toString(), clientAddress);
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
        try (InputStream in = RegistrationApiSchemaIT.class.getClassLoader()
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
