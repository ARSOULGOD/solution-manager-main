package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.SystemActors;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code V022} (plan 015 T4.6, {@code OR-140}): the system actor, and the {@code OR-25} amendment that lets a write be audited
 * to it for exactly the two named job operations - the enrolment sweep's {@code AGENT_REVOKED} (an active registration becomes
 * revoked) and {@code TARGET_RETIRED} (an agent target becomes retired) - and nothing else.
 *
 * <p>Every write runs as {@code sm_cp_runtime}: an owner or migrator write is exempt from {@code OR-25} and proves nothing about
 * the role the application connects as. Each refusal asserts its SQLSTATE and wording, so a different guard firing is not a pass.
 */
class SystemActorSchemaIT {

    private static final String KEY = "system-actor-schema-it";
    private static final String JOB = SystemActors.CLIENT_ADDRESS;
    private static final String HTTP = "203.0.113.70";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "027");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
    private final SecureRandom random = new SecureRandom();

    private record Fixture(UUID organization, UUID principal, UUID scope, UUID agent, UUID target) {
    }

    private record Refusal(String sqlState, String message) {
    }

    @Test
    void theSystemActorIsOneSeededRowNamedByTheJavaConstant() {
        List<Map<String, Object>> rows = migrator.queryForList("SELECT system_actor_id, name FROM sm_control.system_actor", Map.of());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("system_actor_id")).isEqualTo(SystemActors.AGENT_JOBS);
        assertThat(rows.get(0).get("name")).isEqualTo("agent-jobs");
    }

    @Test
    void theRevokeOfAnActiveRegistrationIsAcceptedUnderTheSystemActorAndAuditedToIt() {
        Fixture f = fixture();
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.AGENT_REVOKED, correlation, JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now(),"
                    + " agent_revocation_epoch = agent_revocation_epoch + 1 WHERE agent_id = ?", f.agent);
        });

        Map<String, Object> row = auditRow("agent_registration", correlation);
        assertThat(row.get("audit_operation")).isEqualTo("AGENT_REVOKED");
        assertThat(row.get("actor_system_id")).isEqualTo(SystemActors.AGENT_JOBS);
        assertThat(row.get("actor_principal_id")).as("never the token's issuer").isNull();
        assertThat(row.get("actor_agent_id")).isNull();
        assertThat(row.get("client_address")).isEqualTo(JOB);
    }

    @Test
    void theRetirementOfAnAgentTargetIsAcceptedUnderTheSystemActorAndAuditedToIt() {
        Fixture f = fixture();
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.TARGET_RETIRED, correlation, JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        });

        Map<String, Object> row = auditRow("target", correlation);
        assertThat(row.get("audit_operation")).isEqualTo("TARGET_RETIRED");
        assertThat(row.get("actor_system_id")).isEqualTo(SystemActors.AGENT_JOBS);
        assertThat(row.get("actor_principal_id")).isNull();
    }

    @Test
    void aWriteWithNoActorAtAllStillFailsClosedForTheSameOperations() {
        Fixture f = fixture();

        Refusal revoke = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            principalContext(AuditOperations.AGENT_REVOKED, null, UUID.randomUUID(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now(),"
                    + " agent_revocation_epoch = agent_revocation_epoch + 1 WHERE agent_id = ?", f.agent);
        }));
        Refusal retire = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            principalContext(AuditOperations.TARGET_RETIRED, null, UUID.randomUUID(), HTTP);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        }));
        Refusal other = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            principalContext(AuditOperations.TARGET_REGISTERED, null, UUID.randomUUID(), HTTP);
            insertTarget(UUID.randomUUID(), f, "ssh-docker", "plain-" + UUID.randomUUID());
        }));

        for (Refusal refusal : List.of(revoke, retire, other)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("requires an actor principal");
        }
    }

    @Test
    void theSystemActorIsRefusedOnEveryOtherOperation() {
        Fixture f = fixture();
        for (String operation : List.of(AuditOperations.TARGET_REGISTERED, AuditOperations.ENTITLEMENT_SET,
                AuditOperations.PRINCIPAL_CREATED, AuditOperations.ROLE_BINDING_GRANTED, AuditOperations.AGENT_ENROLLED,
                AuditOperations.AGENT_INSTANCE_REGISTERED, AuditOperations.AGENT_ENROLMENT_ISSUED,
                AuditOperations.HIERARCHY_NODE_RENAMED, AuditOperations.LOGIN_REFUSED)) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                system(operation, UUID.randomUUID(), JOB);
                insertTarget(UUID.randomUUID(), f, "ssh-docker", "n-" + UUID.randomUUID());
            }));
            assertThat(refusal.sqlState).as(operation + ": " + refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).as(operation).contains("a system actor is only permitted for");
        }
    }

    @Test
    void theSystemActorIsRefusedOnTheWrongTableOrTheWrongKindOfWrite() {
        Fixture f = fixture();
        UUID ssh = UUID.randomUUID();
        insertTargetAsMigrator(ssh, f, "ssh-docker", "ssh-" + ssh);

        Refusal revokeOnTarget = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.AGENT_REVOKED, UUID.randomUUID(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        }));
        Refusal retireRegistration = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.TARGET_RETIRED, UUID.randomUUID(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now(),"
                    + " agent_revocation_epoch = agent_revocation_epoch + 1 WHERE agent_id = ?", f.agent);
        }));
        Refusal retireSshTarget = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.TARGET_RETIRED, UUID.randomUUID(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", ssh);
        }));
        Refusal insertAgentTarget = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.TARGET_RETIRED, UUID.randomUUID(), JOB);
            insertTarget(UUID.randomUUID(), f, "agent", "sneaky-" + UUID.randomUUID());
        }));

        for (Refusal refusal : List.of(revokeOnTarget, retireRegistration, retireSshTarget, insertAgentTarget)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("the system actor may write only");
        }
    }

    @Test
    void theSystemActorMayNotBumpTheEpochWithoutRevoking() {
        Fixture f = fixture();
        Refusal changesTheName = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.AGENT_REVOKED, UUID.randomUUID(), JOB);
            // the runtime role may update the epoch columns; a system revoke must change status (active -> revoked) and nothing
            // beyond the revocation columns, so an epoch bump with the status unchanged is not a revoke
            runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET agent_revocation_epoch = agent_revocation_epoch + 1"
                    + " WHERE agent_id = ?", f.agent);
        }));
        assertThat(changesTheName.sqlState).as(changesTheName.message).isEqualTo("OR025");
        assertThat(changesTheName.message).contains("the system actor may write only");
    }

    @Test
    void aSystemRevokeThatAlsoChangesAnyColumnBeyondTheRevocationIsRefused() {
        Fixture f = fixture();
        for (String also : List.of("trust_bundle_pem = ARRAY[decode('2d2e', 'hex')]", "valid_to = now() + interval '9 days'", "fleet_revocation_epoch = fleet_revocation_epoch + 1")) {
            Refusal refusal;
            try {
                refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                    system(AuditOperations.AGENT_REVOKED, UUID.randomUUID(), JOB);
                    runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now(),"
                            + " agent_revocation_epoch = agent_revocation_epoch + 1, " + also + " WHERE agent_id = ?", f.agent);
                }));
            } catch (AssertionError accepted) {
                throw new AssertionError("accepted a system revoke that also set: " + also, accepted);
            }
            assertThat(refusal.sqlState).as(also + ": " + refusal.message).isEqualTo("OR025");
        }
    }

    @Test
    void theSystemActorNeedsItsOwnClientAddressAndNoOtherKindOfActor() {
        Fixture f = fixture();
        Refusal fromHttp = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            system(AuditOperations.TARGET_RETIRED, UUID.randomUUID(), HTTP);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        }));
        Refusal withPrincipal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            runtime.getJdbcTemplate().queryForList("SELECT set_config('sm.audit.actor', ?, true),"
                    + " set_config('sm.audit.actor_system', ?, true), set_config('sm.audit.operation', ?, true),"
                    + " set_config('sm.audit.correlation_id', ?, true), set_config('sm.audit.client_address', ?, true)",
                    f.principal.toString(), SystemActors.AGENT_JOBS.toString(), AuditOperations.TARGET_RETIRED,
                    UUID.randomUUID().toString(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        }));
        Refusal withAgent = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            runtime.getJdbcTemplate().queryForList("SELECT set_config('sm.audit.actor', '', true),"
                    + " set_config('sm.audit.actor_system', ?, true), set_config('sm.audit.actor_agent', ?, true),"
                    + " set_config('sm.audit.operation', ?, true), set_config('sm.audit.correlation_id', ?, true),"
                    + " set_config('sm.audit.client_address', ?, true)",
                    SystemActors.AGENT_JOBS.toString(), f.agent.toString(), AuditOperations.TARGET_RETIRED,
                    UUID.randomUUID().toString(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        }));
        Refusal unknownActor = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            runtime.getJdbcTemplate().queryForList("SELECT set_config('sm.audit.actor', '', true),"
                    + " set_config('sm.audit.actor_system', ?, true), set_config('sm.audit.operation', ?, true),"
                    + " set_config('sm.audit.correlation_id', ?, true), set_config('sm.audit.client_address', ?, true)",
                    UUID.randomUUID().toString(), AuditOperations.TARGET_RETIRED, UUID.randomUUID().toString(), JOB);
            runtime.getJdbcTemplate().update("UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", f.target);
        }));

        for (Refusal refusal : List.of(fromHttp, withPrincipal, withAgent, unknownActor)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
        }
        assertThat(fromHttp.message).contains("a system actor writes only from");
        assertThat(withPrincipal.message).contains("exactly a system actor and no other");
        assertThat(withAgent.message).contains("exactly a system actor and no other");
        assertThat(unknownActor.message).contains("not a known system actor");
    }

    @Test
    void anAuditRowNamesAtMostOneKindOfActor() {
        Fixture f = fixture();
        Refusal both = refusalOf(() -> migrator.getJdbcTemplate().update("INSERT INTO sm_control.change_audit (recorded_at, txid,"
                + " table_name, operation, row_key, changed_columns, secret_columns_changed, actor_principal_id, actor_system_id,"
                + " audit_operation, correlation_id, client_address, db_role) VALUES (now(), 1, 'target', 'UPDATE', '{}'::jsonb,"
                + " ARRAY[]::text[], ARRAY[]::text[], ?, ?, 'TARGET_RETIRED', gen_random_uuid(), 'x', 'sm_cp_owner')",
                f.principal, SystemActors.AGENT_JOBS));
        assertThat(both.sqlState).as(both.message).isEqualTo("23514");
        assertThat(both.message).contains("change_audit_actor_one_kind");
    }

    @Test
    void theRuntimeRoleCannotWriteTheSystemActorTable() {
        Refusal insert = refusalOf(() -> runtime.getJdbcTemplate().update(
                "INSERT INTO sm_control.system_actor (system_actor_id, name) VALUES (gen_random_uuid(), 'rogue')"));
        Refusal update = refusalOf(() -> runtime.getJdbcTemplate().update("UPDATE sm_control.system_actor SET name = 'x'"));
        assertThat(insert.sqlState).isEqualTo("42501");
        assertThat(update.sqlState).isEqualTo("42501");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private Fixture fixture() {
        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                organization, "Org-" + organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                + " (organization_id, organization_unit_id, name) VALUES (?, ?, 'u-' || gen_random_uuid()::text)", organization, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                + " environment_id, name) VALUES (?, ?, ?, 'e-' || gen_random_uuid()::text)", organization, unit, environment);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "sys-schema-" + principal);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.scope (scope_id, kind, organization_id)"
                + " VALUES (?, 'organization', ?)", scope, organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id,"
                + " catalog_solution_id, solution, created_at) VALUES (?, ?, 'cache', now())", organization, catalog);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution_package (organization_id,"
                + " catalog_solution_id, package_version, applies_to, digest, signature_status, document, loaded_by,"
                + " loaded_at) VALUES (?, ?, '[1]', '>=1', ?, 'unsigned', '{}'::jsonb, ?, now())", organization,
                catalog, "sha256:" + "0".repeat(64), principal);
        Fixture f = new Fixture(organization, principal, scope, agent, target);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.agent_registration (organization_id, organization_unit_id,"
                + " environment_id, agent_id, catalog_solution_id, package_version, target_name, allowed_config_root,"
                + " allowed_container_prefix, enrollment_token_digest, expires_at, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, ?, '1', ?, '/opt/sm/config', 'sm-', decode(?, 'hex'), now() + interval '1 hour', ?, now())",
                organization, unit, environment, agent, catalog, "agent-" + agent, randomHex(32), principal);
        insertTargetAsMigrator(target, f, "agent", "agent-" + agent);
        migrator.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET enrollment_used_at = now(),"
                + " enrollment_csr_digest = decode(?, 'hex'), certificate_chain_der = ARRAY[decode('3082', 'hex')],"
                + " trust_bundle_pem = ARRAY[decode('2d2d', 'hex')], valid_from = now(), valid_to = now() + interval '1 day',"
                + " target_id = ? WHERE agent_id = ?", randomHex(32), target, agent);
        return f;
    }

    private void insertTargetAsMigrator(UUID target, Fixture f, String transport, String name) {
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name,"
                + " kind, transport, created_by, created_at) VALUES (?, ?, ?, ?, 'docker', ?, ?, now())", target, f.organization,
                f.scope, name, transport, f.principal);
    }

    private void insertTarget(UUID target, Fixture f, String transport, String name) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name,"
                + " kind, transport, created_by, created_at) VALUES (?, ?, ?, ?, 'docker', ?, ?, now())", target, f.organization,
                f.scope, name, transport, f.principal);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    /** The settings {@code JdbcSystemAuditContext} writes: the system actor, no principal, no agent. */
    private void system(String operation, UUID correlation, String client) {
        runtime.getJdbcTemplate().queryForList("SELECT set_config('sm.audit.actor', '', true),"
                + " set_config('sm.audit.actor_agent', '', true), set_config('sm.audit.actor_system', ?, true),"
                + " set_config('sm.audit.operation', ?, true), set_config('sm.audit.correlation_id', ?, true),"
                + " set_config('sm.audit.client_address', ?, true)",
                SystemActors.AGENT_JOBS.toString(), operation, correlation.toString(), client);
    }

    private void principalContext(String operation, UUID principal, UUID correlation, String client) {
        runtime.getJdbcTemplate().queryForList("SELECT set_config('sm.audit.actor', ?, true),"
                + " set_config('sm.audit.actor_system', '', true), set_config('sm.audit.operation', ?, true),"
                + " set_config('sm.audit.correlation_id', ?, true), set_config('sm.audit.client_address', ?, true)",
                principal == null ? "" : principal.toString(), operation, correlation.toString(), client);
    }

    private Map<String, Object> auditRow(String table, UUID correlation) {
        List<Map<String, Object>> rows = migrator.queryForList("SELECT audit_operation, actor_principal_id, actor_agent_id,"
                + " actor_system_id, client_address FROM sm_control.change_audit WHERE table_name = :t AND correlation_id = :c",
                Map.of("t", table, "c", correlation));
        assertThat(rows).as("exactly one audit row for " + table).hasSize(1);
        return rows.get(0);
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
}
