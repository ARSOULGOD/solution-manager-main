package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.identity.AuditOperations;
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
 * {@code V021} (plan 015 T1.3, {@code OR-126} A8, {@code OR-128} (4) (5), {@code BE-D70}, {@code BE-D71}): the scoped
 * agent registration with its target envelope, the agent as an audit actor on exactly the agent operations, the
 * {@code agent} target transport, and the {@code V020} operator-rename gate surviving the rebuild of
 * {@code record_change_audit()}.
 *
 * <p><b>Every write under test runs as {@code sm_cp_runtime}</b> unless it says it is a structural probe: an owner or
 * migrator write is exempt from {@code OR-25} and proves nothing about the role the application connects as. Each
 * refusal asserts its SQLSTATE and wording, so a different guard firing is not a pass.
 */
class AgentRegistrationSchemaIT {

    private static final String KEY = "agent-registration-schema-it";
    private static final String CLIENT = "203.0.113.70";
    private static final String PEER = "198.51.100.9";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "026");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
    private final SecureRandom random = new SecureRandom();

    private record Fixture(UUID organization, UUID unit, UUID environment, UUID principal, UUID catalog, UUID scope) {
    }

    private record Refusal(String sqlState, String message) {
    }

    // ------------------------------------------------------------------ the V020 gate survives the rebuild

    @Test
    void aNoActorOperatorCliRenameOfAnOrganizationIsStillAcceptedAfterV021() {
        Fixture f = fixture();
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.HIERARCHY_NODE_RENAMED, null, null, correlation, "operator-cli");
            runtime.getJdbcTemplate().update("UPDATE sm_control.organization SET name = ? WHERE organization_id = ?",
                    "Renamed " + f.organization, f.organization);
        });

        Map<String, Object> row = auditRow("organization", correlation);
        assertThat(row.get("audit_operation")).isEqualTo("HIERARCHY_NODE_RENAMED");
        assertThat(row.get("actor_principal_id")).isNull();
        assertThat(row.get("actor_agent_id")).isNull();
        assertThat(row.get("client_address")).isEqualTo("operator-cli");
    }

    @Test
    void theNoActorRenameIsStillRefusedOnAnyOtherTableAndFromAnyOtherClientAddress() {
        Fixture f = fixture();

        Refusal onUnit = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.HIERARCHY_NODE_RENAMED, null, null, UUID.randomUUID(), "operator-cli");
            runtime.getJdbcTemplate().update(
                    "UPDATE sm_control.organization_unit SET name = 'u' WHERE organization_unit_id = ?", f.unit);
        }));
        Refusal fromHttp = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.HIERARCHY_NODE_RENAMED, null, null, UUID.randomUUID(), CLIENT);
            runtime.getJdbcTemplate().update("UPDATE sm_control.organization SET name = 'x' WHERE organization_id = ?",
                    f.organization);
        }));

        for (Refusal refusal : List.of(onUnit, fromHttp)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("requires an actor principal");
        }
    }

    // ------------------------------------------------------------------ the registration table

    @Test
    void aRegistrationWithoutOrganizationEnvironmentOrEnvelopeIsRefusedWithNotNullViolation() {
        Fixture f = fixture();
        for (String column : List.of("organization_id", "environment_id", "organization_unit_id", "allowed_config_root",
                "allowed_container_prefix", "target_name", "created_by")) {
            Refusal refusal = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), column));
            assertThat(refusal.sqlState).as(column + ": " + refusal.message).isEqualTo("23502");
            assertThat(refusal.message).contains(column);
        }
        // The package trigger steps aside when either column is missing, so NOT NULL answers (finding 3: not a misleading
        // "has not loaded" 23503 that names package_version when catalog_solution_id is the missing one).
        for (String column : List.of("catalog_solution_id", "package_version")) {
            Refusal refusal = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), column));
            assertThat(refusal.sqlState).as(column + ": " + refusal.message).isEqualTo("23502");
            assertThat(refusal.message).contains("\"" + column + "\"");
        }
    }

    @Test
    void theEnvelopeGrammarsAndTheTokenDigestLengthAreEnforced() {
        Fixture f = fixture();
        Refusal root = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), null,
                "allowed_config_root", "'/opt/../etc'"));
        Refusal prefix = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), null,
                "allowed_container_prefix", "'-bad prefix'"));
        Refusal digest = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), null,
                "enrollment_token_digest", "decode('abcd', 'hex')"));

        assertThat(root.sqlState).as(root.message).isEqualTo("23514");
        assertThat(root.message).contains("agent_registration_config_root");
        assertThat(prefix.sqlState).as(prefix.message).isEqualTo("23514");
        assertThat(prefix.message).contains("agent_registration_container_prefix");
        assertThat(digest.sqlState).as(digest.message).isEqualTo("23514");
        assertThat(digest.message).contains("agent_registration_token_digest");
    }

    @Test
    void aRegistrationNamingAPackageVersionTheSolutionHasNotLoadedIsRefused() {
        Fixture f = fixture();
        Refusal refusal = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), null,
                "package_version", "'9'"));
        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("23503");
        assertThat(refusal.message).contains("has not loaded");
    }

    @Test
    void thePackageVersionIsOneLoadedMemberNotAnArrayOrAnEmptyOne() {
        // Finding 2: jsonb @> is containment, so '[]' (contained in any array) and '[1]' (a sub-array) used to pass.
        Fixture f = fixture();
        for (String array : List.of("'[]'", "'[1]'", "'[1, 1]'", "'{}'", "'null'", "'true'")) {
            Refusal refusal = refusalOf(() -> insertRegistration(migrator, f, UUID.randomUUID(), null,
                    "package_version", array));
            assertThat(refusal.sqlState).as(array + ": " + refusal.message).isEqualTo("23514");
            assertThat(refusal.message).contains("one version");
        }
        insertRegistration(migrator, f, UUID.randomUUID(), null, "package_version", "'1'");
    }

    @Test
    void anAgentIdIsGloballyUniqueAndATokenDigestIsUsableOnce() {
        Fixture f = fixture();
        Fixture other = fixture();
        UUID agent = UUID.randomUUID();
        String digest = "decode('" + randomHex(32) + "', 'hex')";
        insertRegistration(migrator, f, agent, null, "enrollment_token_digest", digest);

        Refusal sameAgentOtherOrganization = refusalOf(() -> insertRegistration(migrator, other, agent, null));
        Refusal sameToken = refusalOf(() ->
                insertRegistration(migrator, other, UUID.randomUUID(), null, "enrollment_token_digest", digest));

        assertThat(sameAgentOtherOrganization.sqlState).as(sameAgentOtherOrganization.message).isEqualTo("23505");
        assertThat(sameAgentOtherOrganization.message).contains("agent_registration_agent_id_global");
        assertThat(sameToken.sqlState).as(sameToken.message).isEqualTo("23505");
        assertThat(sameToken.message).contains("agent_registration_token_once");
    }

    @Test
    void revocationIsStickyAndEpochsOnlyRise() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        migrator.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked',"
                + " revoked_at = now(), agent_revocation_epoch = 2 WHERE agent_id = ?", agent);

        Refusal back = refusalOf(() -> migrator.getJdbcTemplate().update(
                "UPDATE sm_control.agent_registration SET status = 'active', revoked_at = NULL WHERE agent_id = ?",
                agent));
        Refusal lower = refusalOf(() -> migrator.getJdbcTemplate().update(
                "UPDATE sm_control.agent_registration SET agent_revocation_epoch = 1 WHERE agent_id = ?", agent));

        assertThat(back.sqlState).as(back.message).isEqualTo("23514");
        assertThat(back.message).contains("sticky");
        assertThat(lower.sqlState).as(lower.message).isEqualTo("23514");
    }

    @Test
    void theRuntimeRoleHoldsOnlyTheColumnScopedUpdatesTheTwoLaterWritersNeedAndNoDelete() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);

        for (String sql : List.of(
                "UPDATE sm_control.agent_registration SET created_by = created_by",
                "UPDATE sm_control.agent_registration SET allowed_config_root = '/x'",
                "UPDATE sm_control.agent_registration SET enrollment_token_digest = enrollment_token_digest",
                "UPDATE sm_control.agent_registration SET organization_id = organization_id",
                "DELETE FROM sm_control.agent_registration")) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                context(AuditOperations.AGENT_REVOKED, f.principal, null, UUID.randomUUID(), CLIENT);
                runtime.getJdbcTemplate().update(sql);
            }));
            assertThat(refusal.sqlState).as(sql + ": " + refusal.message).isEqualTo("42501");
        }
    }

    @Test
    void theRuntimeRoleCanUpdateExactlyTheElevenGrantedColumnsAndNoOther() {
        // Finding 5: the set, not a sample - a twelfth column granted later (or one of the 11 withdrawn) turns this red.
        List<String> updatable = migrator.queryForList("SELECT a.attname FROM pg_attribute a WHERE a.attrelid ="
                + " 'sm_control.agent_registration'::regclass AND a.attnum > 0 AND NOT a.attisdropped AND"
                + " has_column_privilege('sm_cp_runtime', 'sm_control.agent_registration', a.attname, 'UPDATE')"
                + " ORDER BY a.attname", Map.of(), String.class);
        assertThat(updatable).containsExactlyInAnyOrder("enrollment_used_at", "enrollment_csr_digest",
                "certificate_chain_der", "trust_bundle_pem", "valid_from", "valid_to", "target_id", "status",
                "revoked_at", "agent_revocation_epoch", "fleet_revocation_epoch");
        assertThat(migrator.queryForObject("SELECT has_table_privilege('sm_cp_runtime',"
                + " 'sm_control.agent_registration', 'DELETE') OR has_table_privilege('sm_cp_runtime',"
                + " 'sm_control.agent_registration', 'TRUNCATE')", Map.of(), Boolean.class)).isFalse();
    }

    // ------------------------------------------------------------------ the principal's acts

    @Test
    void aPrincipalIssuesAnEnrolmentAndRevokesAnAgentAndNeitherAuditRowCopiesTheTokenDigest() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        UUID issued = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLMENT_ISSUED, f.principal, null, issued, CLIENT);
            insertRegistration(runtime, f, agent, null);
        });
        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_REVOKED, f.principal, null, revoked, CLIENT);
            runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked',"
                    + " revoked_at = now(), agent_revocation_epoch = agent_revocation_epoch + 1 WHERE agent_id = ?",
                    agent);
        });

        Map<String, Object> issue = auditRow("agent_registration", issued);
        assertThat(issue.get("audit_operation")).isEqualTo("AGENT_ENROLMENT_ISSUED");
        assertThat(issue.get("actor_principal_id")).isEqualTo(f.principal);
        assertThat(issue.get("actor_agent_id")).isNull();
        assertThat(issue.get("new_values").toString()).doesNotContain("enrollment_token_digest")
                .doesNotContain("certificate_chain_der");
        assertThat(issue.get("secret_columns_changed").toString()).contains("enrollment_token_digest");
        assertThat(issue.get("row_key").toString()).contains(agent.toString());
        Map<String, Object> revoke = auditRow("agent_registration", revoked);
        assertThat(revoke.get("audit_operation")).isEqualTo("AGENT_REVOKED");
        assertThat(revoke.get("actor_principal_id")).isEqualTo(f.principal);
    }

    @Test
    void theTwoPrincipalAgentOperationsStillFailClosedWithoutAPrincipalOrWithOnlyAnAgent() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);

        for (String operation : List.of(AuditOperations.AGENT_ENROLMENT_ISSUED, AuditOperations.AGENT_REVOKED)) {
            Refusal none = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                context(operation, null, null, UUID.randomUUID(), CLIENT);
                insertRegistration(runtime, f, UUID.randomUUID(), null);
            }));
            Refusal onlyAgent = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
                context(operation, null, agent, UUID.randomUUID(), CLIENT);
                insertRegistration(runtime, f, UUID.randomUUID(), null);
            }));
            assertThat(none.sqlState).as(operation + ": " + none.message).isEqualTo("OR025");
            assertThat(none.message).contains("requires an actor principal");
            assertThat(onlyAgent.sqlState).as(operation + ": " + onlyAgent.message).isEqualTo("OR025");
            assertThat(onlyAgent.message).contains("an agent actor is only permitted");
        }
    }

    // ------------------------------------------------------------------ the agent's acts (BE-D70)

    @Test
    void anAgentEnrolsItsTargetAndConsumesItsTokenAndTheRowsCarryTheAgentAsActor() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);

        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, correlation, PEER);
            insertTarget(target, f, "agent-" + agent);
            consume(agent, target);
        });

        List<Map<String, Object>> rows = migrator.queryForList("SELECT table_name, actor_principal_id, actor_agent_id,"
                + " audit_operation, client_address, new_values::text AS new_values FROM sm_control.change_audit"
                + " WHERE correlation_id = :c ORDER BY table_name", Map.of("c", correlation));
        assertThat(rows).extracting(r -> r.get("table_name")).containsExactly("agent_registration", "target");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("audit_operation")).isEqualTo("AGENT_ENROLLED");
            assertThat(r.get("actor_principal_id")).isNull();
            assertThat(r.get("actor_agent_id")).isEqualTo(agent);
            assertThat(r.get("client_address")).isEqualTo(PEER);
        });
        assertThat(rows.get(0).get("new_values").toString()).doesNotContain("certificate_chain_der")
                .doesNotContain("trust_bundle_pem").doesNotContain("enrollment_token_digest");
    }

    @Test
    void aConsumedAgentRegistersItsGovernanceNodesWithAnAgentActor() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            insertTarget(target, f, "agent-" + agent);
            consume(agent, target);
        });
        UUID solution = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent, correlation, PEER);
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                    + " environment_id, solution_id, catalog_solution_id) VALUES (?, ?, ?, ?, ?)", f.organization,
                    f.unit, f.environment, solution, f.catalog);
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.instance (organization_id, organization_unit_id,"
                    + " environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", f.organization, f.unit,
                    f.environment, solution, instance);
        });

        List<Map<String, Object>> rows = migrator.queryForList("SELECT table_name, actor_agent_id FROM"
                + " sm_control.change_audit WHERE correlation_id = :c ORDER BY table_name", Map.of("c", correlation));
        assertThat(rows).extracting(r -> r.get("table_name")).containsExactly("instance", "solution");
        assertThat(rows).extracting(r -> r.get("actor_agent_id")).containsOnly(agent);
    }

    @Test
    void everyAgentOperationWithoutExactlyAnAgentActorOrWithoutContextIsRefused() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        String update = "UPDATE sm_control.agent_registration SET agent_revocation_epoch = agent_revocation_epoch + 1"
                + " WHERE agent_id = ?";

        for (String operation : List.of(AuditOperations.AGENT_ENROLLED, AuditOperations.AGENT_INSTANCE_REGISTERED)) {
            Refusal neither = refusalOf(() -> write(operation, null, null, UUID.randomUUID(), PEER, update, agent));
            Refusal both = refusalOf(() -> write(operation, f.principal, agent, UUID.randomUUID(), PEER, update, agent));
            Refusal noAddress = refusalOf(() -> write(operation, null, agent, UUID.randomUUID(), null, update, agent));
            Refusal noCorrelation = refusalOf(() -> write(operation, null, agent, null, PEER, update, agent));

            assertThat(neither.sqlState).as(operation + ": " + neither.message).isEqualTo("OR025");
            assertThat(neither.message).contains("requires exactly an agent actor and no principal");
            assertThat(both.sqlState).as(operation + ": " + both.message).isEqualTo("OR025");
            assertThat(both.message).contains("requires exactly an agent actor and no principal");
            assertThat(noAddress.sqlState).as(operation + ": " + noAddress.message).isEqualTo("OR025");
            assertThat(noAddress.message).contains("no client address");
            assertThat(noCorrelation.sqlState).as(operation + ": " + noCorrelation.message).isEqualTo("OR025");
            assertThat(noCorrelation.message).contains("correlation id");
        }
    }

    @Test
    void noOtherOperationAcceptsAnAgentActorEvenWithAPrincipalOrFromTheOperatorCommand() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution, max_instances,"
                + " set_by, set_at) VALUES (?, 'cache', 5, ?, now())", f.organization, f.principal);
        String rename = "UPDATE sm_control.organization SET name = 'laundered' WHERE organization_id = ?";

        // the setting is only a claim: it must not launder a principal's act, nor the no-actor operator exemption
        Refusal noActorRename = refusalOf(() ->
                write(AuditOperations.HIERARCHY_NODE_RENAMED, null, agent, UUID.randomUUID(), "operator-cli", rename,
                        f.organization));
        Refusal principalAndAgent = refusalOf(() ->
                write(AuditOperations.HIERARCHY_NODE_RENAMED, f.principal, agent, UUID.randomUUID(), CLIENT, rename,
                        f.organization));
        Refusal entitlement = refusalOf(() ->
                write(AuditOperations.ENTITLEMENT_SET, null, agent, UUID.randomUUID(), "operator-cli",
                        "UPDATE sm_control.entitlement SET max_instances = 2 WHERE organization_id = ?",
                        f.organization));

        for (Refusal refusal : List.of(noActorRename, principalAndAgent, entitlement)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("an agent actor is only permitted for AGENT_ENROLLED");
        }
        assertThat(migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", f.organization), String.class)).isNotEqualTo("laundered");
    }

    @Test
    void anAgentOperationIsScopedToItsTablesItsOrganizationAndAnActiveRegistration() {
        Fixture f = fixture();
        Fixture other = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);

        Refusal wrongTable = refusalOf(() -> write(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER,
                "UPDATE sm_control.organization SET name = 'x' WHERE organization_id = ?", f.organization));
        Refusal instanceOnEnrol = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent, UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), f);
        }));
        Refusal otherOrganization = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), other);
        }));
        Refusal notConsumed = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent, UUID.randomUUID(), PEER);
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                    + " environment_id, solution_id, catalog_solution_id) VALUES (?, ?, ?, ?, ?)", f.organization,
                    f.unit, f.environment, UUID.randomUUID(), f.catalog);
        }));
        migrator.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked',"
                + " revoked_at = now(), agent_revocation_epoch = 2 WHERE agent_id = ?", agent);
        Refusal revoked = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), f);
        }));
        Refusal unknown = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, UUID.randomUUID(), UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), f);
        }));

        assertThat(wrongTable.message).contains("is not permitted on this table");
        assertThat(instanceOnEnrol.message).contains("is not permitted on this table");
        for (Refusal refusal : List.of(otherOrganization, notConsumed, revoked, unknown)) {
            assertThat(refusal.message).contains("not an active registration of the row's organization");
        }
        for (Refusal refusal : List.of(wrongTable, instanceOnEnrol, otherOrganization, notConsumed, revoked, unknown)) {
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
        }
    }

    // ------------------------------------------------------------------ the agent actor is scoped to its OWN acts (finding 1)

    private static final String OWN_ACTS_ONLY = "the agent actor may write only";

    private UUID enrolledAgent(Fixture f) {
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            UUID target = UUID.randomUUID();
            insertTarget(target, f, "agent-" + agent);
            consume(agent, target);
        });
        return agent;
    }

    private void assertOwnActsOnly(Refusal refusal) {
        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
        assertThat(refusal.message).contains(OWN_ACTS_ONLY);
    }

    @Test
    void anAgentCannotMintAnEnrolmentRegistration() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            insertRegistration(runtime, f, UUID.randomUUID(), null);
        }));
        assertOwnActsOnly(refusal);
    }

    @Test
    void anAgentCannotUpdateAnotherAgentsRegistration() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        insertRegistration(migrator, f, victim, null);
        UUID target = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                + " VALUES (?, ?, ?, 'victim-t', 'docker', 'agent', ?, now())", target, f.organization, f.scope,
                f.principal);

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            consume(victim, target);
        }));
        assertOwnActsOnly(refusal);
    }

    @Test
    void anAgentCannotUpdateItsOwnRegistrationOnceItsTokenIsConsumed() {
        Fixture f = fixture();
        UUID agent = enrolledAgent(f);

        Refusal reconsume = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET"
                    + " trust_bundle_pem = ARRAY[decode('2d2d', 'hex')] WHERE agent_id = ?", agent);
        }));
        assertOwnActsOnly(reconsume);
    }

    @Test
    void anAgentCannotRetireATargetOrInsertAnythingButItsOneAgentTarget() {
        Fixture f = fixture();
        UUID agent = enrolledAgent(f);
        UUID pending = UUID.randomUUID();
        insertRegistration(migrator, f, pending, null);

        Refusal retire = refusalOf(() -> write(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER,
                "UPDATE sm_control.target SET retired_at = now() WHERE organization_id = ?", f.organization));
        Refusal second = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, agent, UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), f, "second-" + agent);
        }));
        assertOwnActsOnly(retire);
        assertOwnActsOnly(second);

        // before consumption: an SSH-transport target, a target under another name, and a second one under its own name
        Refusal ssh = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, pending, UUID.randomUUID(), PEER);
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                    + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?, 'docker', 'ssh-docker', ?, now())", UUID.randomUUID(), f.organization,
                    f.scope, "agent-" + pending, f.principal);
        }));
        Refusal otherName = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, pending, UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), f, "someone-elses-name");
        }));
        Refusal twice = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_ENROLLED, null, pending, UUID.randomUUID(), PEER);
            insertTarget(UUID.randomUUID(), f, "agent-" + pending);
            insertTarget(UUID.randomUUID(), f, "agent-" + pending);
        }));
        assertOwnActsOnly(ssh);
        assertOwnActsOnly(otherName);
        assertThat(twice.sqlState).as(twice.message).isEqualTo("23505");
    }

    @Test
    void aRegisteredAgentCannotRetireAnInstance() {
        Fixture f = fixture();
        UUID agent = enrolledAgent(f);
        UUID solution = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                + " environment_id, solution_id, catalog_solution_id) VALUES (?, ?, ?, ?, ?)", f.organization, f.unit,
                f.environment, solution, f.catalog);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.instance (organization_id, organization_unit_id,"
                + " environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", f.organization, f.unit,
                f.environment, solution, instance);

        Refusal refusal = refusalOf(() -> write(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent,
                UUID.randomUUID(), PEER, "UPDATE sm_control.instance SET retired_at = now(), retired_by = ?"
                        + " WHERE instance_id = ?", f.principal, instance));
        assertOwnActsOnly(refusal);
    }

    @Test
    void aRegisteredAgentCannotRegisterIntoAnotherEnvironmentOrAnotherCatalogSolution() {
        Fixture f = fixture();
        UUID agent = enrolledAgent(f);
        UUID otherEnvironment = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                + " environment_id, name) VALUES (?, ?, ?, 'e2-' || gen_random_uuid()::text)", f.organization, f.unit,
                otherEnvironment);
        UUID otherCatalog = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id,"
                + " catalog_solution_id, solution, created_at) VALUES (?, ?, 'other', now())", f.organization,
                otherCatalog);
        UUID foreignSolution = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                + " environment_id, solution_id, catalog_solution_id) VALUES (?, ?, ?, ?, ?)", f.organization, f.unit,
                otherEnvironment, foreignSolution, otherCatalog);

        String solutionSql = "INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id,"
                + " solution_id, catalog_solution_id) VALUES (?, ?, ?, ?, ?)";
        Refusal solutionElsewhere = refusalOf(() -> write(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent,
                UUID.randomUUID(), PEER, solutionSql, f.organization, f.unit, otherEnvironment, UUID.randomUUID(),
                f.catalog));
        Refusal otherSolution = refusalOf(() -> write(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent,
                UUID.randomUUID(), PEER, solutionSql, f.organization, f.unit, f.environment, UUID.randomUUID(),
                otherCatalog));
        Refusal instanceElsewhere = refusalOf(() -> write(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent,
                UUID.randomUUID(), PEER, "INSERT INTO sm_control.instance (organization_id, organization_unit_id,"
                        + " environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", f.organization, f.unit,
                otherEnvironment, foreignSolution, UUID.randomUUID()));
        Refusal scopeElsewhere = refusalOf(() -> write(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent,
                UUID.randomUUID(), PEER, "INSERT INTO sm_control.scope (scope_id, kind, organization_id,"
                        + " organization_unit_id, environment_id, solution_id) VALUES (?, 'solution', ?, ?, ?, ?)",
                UUID.randomUUID(), f.organization, f.unit, otherEnvironment, foreignSolution));
        for (Refusal refusal : List.of(solutionElsewhere, otherSolution, instanceElsewhere, scopeElsewhere)) {
            assertOwnActsOnly(refusal);
        }

        // and the positive: its own environment, its own catalog solution, solution then instance then scope
        UUID solution = UUID.randomUUID();
        runtimeTx.executeWithoutResult(status -> {
            context(AuditOperations.AGENT_INSTANCE_REGISTERED, null, agent, UUID.randomUUID(), PEER);
            runtime.getJdbcTemplate().update(solutionSql, f.organization, f.unit, f.environment, solution, f.catalog);
            runtime.getJdbcTemplate().update("INSERT INTO sm_control.scope (scope_id, kind, organization_id,"
                    + " organization_unit_id, environment_id, solution_id) VALUES (?, 'solution', ?, ?, ?, ?)",
                    UUID.randomUUID(), f.organization, f.unit, f.environment, solution);
        });
    }

    // ------------------------------------------------------------------ transport, vocabulary and constraints

    @Test
    void theTargetTransportIsWidenedToTheAgentTransportAndNoFurther() {
        Fixture f = fixture();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                + " VALUES (?, ?, ?, 'agent-t', 'docker', 'agent', ?, now())", UUID.randomUUID(), f.organization,
                f.scope, f.principal);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                + " VALUES (?, ?, ?, 'ssh-t', 'docker', 'ssh-docker', ?, now())", UUID.randomUUID(), f.organization,
                f.scope, f.principal);

        Refusal refusal = refusalOf(() -> migrator.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id,"
                + " organization_id, ownership_scope_id, name, kind, transport, created_by, created_at)"
                + " VALUES (?, ?, ?, 'k8s-t', 'docker', 'kubernetes-api', ?, now())", UUID.randomUUID(),
                f.organization, f.scope, f.principal));
        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("23514");
        assertThat(refusal.message).contains("target_transport");
    }

    @Test
    void anAgentTargetIsADockerTargetInV1() {
        // Finding 10 (OR-111): v1 agent targets are Docker only.
        Fixture f = fixture();
        for (String kind : List.of("vm", "kubernetes")) {
            Refusal refusal = refusalOf(() -> migrator.getJdbcTemplate().update("INSERT INTO sm_control.target"
                    + " (target_id, organization_id, ownership_scope_id, name, kind, transport, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, 'agent', ?, now())", UUID.randomUUID(), f.organization, f.scope,
                    "agent-" + kind, kind, f.principal));
            assertThat(refusal.sqlState).as(kind + ": " + refusal.message).isEqualTo("23514");
            assertThat(refusal.message).contains("target_agent_is_docker");
        }
    }

    @Test
    void theAuditOperationVocabularyAcceptsTheFourAgentOperationsAndStillRefusesAnUnknownOne() {
        for (String operation : List.of("AGENT_ENROLMENT_ISSUED", "AGENT_REVOKED", "AGENT_ENROLLED",
                "AGENT_INSTANCE_REGISTERED")) {
            assertThat(migrator.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname ="
                    + " 'change_audit_audit_operation' AND pg_get_constraintdef(oid) LIKE :p",
                    Map.of("p", "%'" + operation + "'%"), Integer.class)).as(operation).isEqualTo(1);
        }
        Refusal unknown = refusalOf(() -> migrator.getJdbcTemplate().update("INSERT INTO sm_control.change_audit"
                + " (recorded_at, txid, table_name, operation, row_key, changed_columns, secret_columns_changed,"
                + " audit_operation, db_role) VALUES (now(), 1, 't', 'INSERT', '{}', '{}', '{}', 'AGENT_EXPLODED',"
                + " 'x')"));
        assertThat(unknown.sqlState).as(unknown.message).isEqualTo("23514");
    }

    @Test
    void anAuditRowNamesAtMostOneKindOfActor() {
        Fixture f = fixture();
        UUID agent = UUID.randomUUID();
        insertRegistration(migrator, f, agent, null);
        Refusal both = refusalOf(() -> migrator.getJdbcTemplate().update("INSERT INTO sm_control.change_audit"
                + " (recorded_at, txid, table_name, operation, row_key, changed_columns, secret_columns_changed,"
                + " actor_principal_id, actor_agent_id, db_role) VALUES (now(), 1, 't', 'INSERT', '{}', '{}', '{}',"
                + " ?, ?, 'x')", f.principal, agent));
        assertThat(both.sqlState).as(both.message).isEqualTo("23514");
        assertThat(both.message).contains("change_audit_actor_one_kind");
    }

    // ------------------------------------------------------------------ fixtures

    private Fixture fixture() {
        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                organization, "Org-" + organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit"
                + " (organization_id, organization_unit_id, name) VALUES (?, ?, 'u-' || gen_random_uuid()::text)",
                organization, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                + " environment_id, name) VALUES (?, ?, ?, 'e-' || gen_random_uuid()::text)", organization, unit,
                environment);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "agent-schema-" + principal);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.scope (scope_id, kind, organization_id)"
                + " VALUES (?, 'organization', ?)", scope, organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id,"
                + " catalog_solution_id, solution, created_at) VALUES (?, ?, 'cache', now())", organization, catalog);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution_package (organization_id,"
                + " catalog_solution_id, package_version, applies_to, digest, signature_status, document, loaded_by,"
                + " loaded_at) VALUES (?, ?, '[1]', '>=1', ?, 'unsigned', '{}'::jsonb, ?, now())", organization,
                catalog, "sha256:" + "0".repeat(64), principal);
        return new Fixture(organization, unit, environment, principal, catalog, scope);
    }

    /** Inserts a registration; {@code omit} leaves one column out, {@code override} replaces one column's SQL value. */
    private void insertRegistration(NamedParameterJdbcTemplate jdbc, Fixture f, UUID agent, String omit,
            String... override) {
        java.util.LinkedHashMap<String, String> columns = new java.util.LinkedHashMap<>();
        columns.put("organization_id", "'" + f.organization + "'");
        columns.put("organization_unit_id", "'" + f.unit + "'");
        columns.put("environment_id", "'" + f.environment + "'");
        columns.put("agent_id", "'" + agent + "'");
        columns.put("catalog_solution_id", "'" + f.catalog + "'");
        columns.put("package_version", "'1'");
        columns.put("target_name", "'agent-" + agent + "'");
        columns.put("allowed_config_root", "'/opt/sm/config'");
        columns.put("allowed_container_prefix", "'sm-'");
        columns.put("enrollment_token_digest", "decode('" + randomHex(32) + "', 'hex')");
        columns.put("expires_at", "now() + interval '1 hour'");
        columns.put("created_by", "'" + f.principal + "'");
        columns.put("created_at", "now()");
        for (int i = 0; i + 1 < override.length; i += 2) {
            columns.put(override[i], override[i + 1]);
        }
        if (omit != null) {
            columns.remove(omit);
        }
        jdbc.getJdbcTemplate().update("INSERT INTO sm_control.agent_registration (" + String.join(", ", columns.keySet())
                + ") VALUES (" + String.join(", ", columns.values()) + ")");
    }

    private void insertTarget(UUID target, Fixture f) {
        insertTarget(target, f, "t-" + target);
    }

    private void insertTarget(UUID target, Fixture f, String name) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, 'docker', 'agent', ?, now())", target, f.organization, f.scope,
                name, f.principal);
    }

    private void consume(UUID agent, UUID target) {
        runtime.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET enrollment_used_at = now(),"
                + " enrollment_csr_digest = decode('" + randomHex(32) + "', 'hex'),"
                + " certificate_chain_der = ARRAY[decode('3082', 'hex')], trust_bundle_pem = ARRAY[decode('2d2d', 'hex')],"
                + " valid_from = now(), valid_to = now() + interval '1 day', target_id = ? WHERE agent_id = ?",
                target, agent);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    // ------------------------------------------------------------------ context and observation

    private void write(String operation, UUID principal, UUID agent, UUID correlation, String client, String sql,
            Object... args) {
        runtimeTx.executeWithoutResult(status -> {
            context(operation, principal, agent, correlation, client);
            runtime.getJdbcTemplate().update(sql, args);
        });
    }

    /** The same transaction-local settings {@code JdbcAuditContext} writes, plus {@code sm.audit.actor_agent}. */
    private void context(String operation, UUID principal, UUID agent, UUID correlation, String client) {
        runtime.getJdbcTemplate().queryForList(
                "SELECT set_config('sm.audit.actor', ?, true), set_config('sm.audit.operation', ?, true),"
                        + " set_config('sm.audit.correlation_id', ?, true),"
                        + " set_config('sm.audit.client_address', ?, true),"
                        + " set_config('sm.audit.actor_agent', ?, true)",
                principal == null ? "" : principal.toString(), operation,
                correlation == null ? "" : correlation.toString(), client == null ? "" : client,
                agent == null ? "" : agent.toString());
    }

    private Map<String, Object> auditRow(String table, UUID correlation) {
        List<Map<String, Object>> rows = migrator.queryForList("SELECT row_key::text AS row_key,"
                + " new_values::text AS new_values, secret_columns_changed::text AS secret_columns_changed,"
                + " audit_operation, actor_principal_id, actor_agent_id, client_address FROM sm_control.change_audit"
                + " WHERE table_name = :t AND correlation_id = :c", Map.of("t", table, "c", correlation));
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
