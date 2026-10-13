package com.solace.sm.cp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.identity.CredentialRotation;
import com.solace.sm.cp.api.identity.ForcedRotationGate;
import com.solace.sm.cp.api.identity.IdentityDirectory;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.session.SessionAuthentication;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.SmControlProperties;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import com.solace.sm.cp.events.StubMeshPublisherConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@link ControlPlaneConfiguration} as Spring actually builds it — mirroring dp-core's
 * {@code ApplyPersistenceConfigurationIT}: an application that CANNOT migrate must still refuse to
 * serve a schema nobody has migrated, and a migrated one must come up wired with every port present
 * and no migrator credential anywhere in the context.
 */
class ControlPlaneConfigurationIT {

    @Test
    void aMigratedDatabaseWiresAllSixPortsWithNoFlywayBeanAndNoMigratorCredential() {
        runner(SmControlPostgres.runtimeUrlAtVersion(SmControlPostgres.LATEST_VERSION), SmControlPostgres.runtimePasswordAtVersion(SmControlPostgres.LATEST_VERSION))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();

                    assertThat(context.getBean(SessionAuthentication.class)).isNotNull();
                    assertThat(context.getBean(LocalAuthenticationService.class)).isNotNull();
                    assertThat(context.getBean(CredentialRotation.class)).isNotNull();
                    assertThat(context.getBean(ForcedRotationGate.class)).isNotNull();
                    assertThat(context.getBean(IdentityDirectory.class)).isNotNull();
                    assertThat(context.getBean(AuthorizationService.class)).isNotNull();

                    assertThat(context.getBeanNamesForType(Flyway.class))
                            .as("no Flyway bean belongs in the runtime context")
                            .isEmpty();

                    List<String> components = Arrays.stream(
                                    context.getBean(SmControlProperties.class).getClass().getRecordComponents())
                            .map(RecordComponent::getName).toList();
                    assertThat(components).as("no migrator/owner credential component exists to leak")
                            .containsExactly("runtime", "login", "loginAddress");
                });
    }

    @Test
    void aDatabaseWithoutTheAuditTriggersRefusesToStart() {
        // OR-19 is a completeness guarantee: every state change leaves an audit row. A database
        // migrated only to V010 has every business table but no change_audit and no triggers, so
        // an application that started against it would change state with NO audit trail at all —
        // silently. The readiness check must refuse it, not just check that the tables exist.
        runner(SmControlPostgres.runtimeUrlAtVersion("010"), SmControlPostgres.runtimePasswordAtVersion("010"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure)
                            .as("a context over sm_control WITHOUT the OR-19 audit triggers must not start")
                            .isNotNull();
                    assertThat(causeChain(failure)).contains("audit");
                });
    }

    @Test
    void aDatabaseThatPredatesV016RefusesToStartNamingTheFiveVerbsAndTheOperatorGrants() {
        // OR-121 / plan 012 T1: a build that mirrors a 43-verb catalog must not run against a 38-verb database, where
        // every operation that names one of the five would fail a foreign key instead of being authorized.
        // V015 (OR-120 names) is checked first, so the database that predates V016 is a V015 one.
        // A V015 schema, not V014: readiness names the FIRST missing piece, and a V014 schema is refused for V015.
        runner(SmControlPostgres.runtimeUrlAtVersion("015"), SmControlPostgres.runtimePasswordAtVersion("015"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V015 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V016").contains("instance.undeclare");
                });
    }

    @Test
    void aV016DatabaseWhoseOperatorLostOneOfTheThreeNewVerbsRefusesToStart() {
        String cacheKey = "readiness-operator-verbs-v016-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().execute("DELETE FROM sm_control.role_permission WHERE role_id = "
                + "'00000000-0000-4000-8000-000000000003' AND permission_id = 'instance.stop'");

        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("an Operator without instance.stop is not a V016 Operator").isNotNull();
                    assertThat(causeChain(failure)).contains("V016").contains("Operator");
                });
    }

    @Test
    void aDatabaseThatPredatesV017RefusesToStartNamingTheRetirementColumnsAndTheUndeclareAudit() {
        // Plan 012 T4: the control plane's half of undeclare writes instance.retired_at/retired_by as the runtime role
        // and audits it as INSTANCE_UNDECLARED; a build that does that must not start against a V016 schema.
        runner(SmControlPostgres.runtimeUrlAtVersion("016"), SmControlPostgres.runtimePasswordAtVersion("016"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V016 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V017").contains("retired_at")
                            .contains("INSTANCE_UNDECLARED");
                });
    }

    @Test
    void aV017DatabaseThatLostItsTerminalTriggerItsRuntimeUpdateOrItsAuditFunctionRefusesToStart() {
        String cacheKey = "readiness-v017-pieces-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey,
                SmControlPostgres.LATEST_VERSION);
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).as("fully migrated starts").isNull());

        // A retired instance must stay retired: without the guard trigger a later write could revive it.
        owner.getJdbcTemplate().execute("DROP TRIGGER instance_retired_is_terminal ON sm_control.instance");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("no terminal-row guard").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("instance_retired_is_terminal");
                });
        owner.getJdbcTemplate().execute("CREATE TRIGGER instance_retired_is_terminal BEFORE UPDATE ON "
                + "sm_control.instance FOR EACH ROW WHEN (OLD.retired_at IS NOT NULL) "
                + "EXECUTE FUNCTION sm_control.instance_retired_is_terminal()");

        // The runtime role cannot retire anything without its column UPDATE.
        owner.getJdbcTemplate().execute("REVOKE UPDATE (retired_at) ON TABLE sm_control.instance FROM sm_cp_runtime");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("no runtime UPDATE on retired_at").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V017").contains("UPDATE");
                });
        owner.getJdbcTemplate().execute("GRANT UPDATE (retired_at) ON TABLE sm_control.instance TO sm_cp_runtime");

        // An owner who put V014's body back is still fail-closed (every earlier probe passes), but OR-25 would refuse
        // INSTANCE_UNDECLARED as unknown, so the first undeclare would fail at runtime instead of at start.
        owner.getJdbcTemplate().execute(functionBodyOf("V014__dashboards_and_operator_metrics_verbs.sql"));
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("record_change_audit() reverted to V014").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V017")
                            .contains("INSTANCE_UNDECLARED");
                });
    }

    @Test
    void aDatabaseThatPredatesV018RefusesToStartNamingTheRegistrationGrantsTheSevenOperationsAndTheNameIndex() {
        // Plan 013 T2.1: a build that creates sub-organizations, principals and bindings as the runtime role and audits
        // them under seven new operations must not start against a V017 schema, where the first request would be
        // refused by OR-25 (an unknown operation) or by a missing grant instead of by readiness.
        runner(SmControlPostgres.runtimeUrlAtVersion("017"), SmControlPostgres.runtimePasswordAtVersion("017"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V017 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V018").contains("HIERARCHY_NODE_CREATED")
                            .contains("target_name_once").contains("SmControlMigrationMain");
                });
    }

    @Test
    void aV018DatabaseThatLostItsAuditBodyAGrantOrThePartialNameIndexRefusesToStart() {
        String cacheKey = "readiness-v018-pieces-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).as("fully migrated starts").isNull());

        // A revoke is a regression the application would only meet at the first unbinding.
        owner.getJdbcTemplate().execute("REVOKE DELETE ON TABLE sm_control.role_binding FROM sm_cp_runtime");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("no DELETE on role_binding").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V018").contains("grant");
                });
        owner.getJdbcTemplate().execute("GRANT DELETE ON TABLE sm_control.role_binding TO sm_cp_runtime");

        // The depth rule is a privilege: a runtime that could set parent_unit_id could nest sub-organizations.
        owner.getJdbcTemplate().execute("GRANT INSERT (parent_unit_id) ON TABLE sm_control.organization_unit "
                + "TO sm_cp_runtime");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("INSERT on parent_unit_id").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V018").contains("parent_unit_id");
                });
        owner.getJdbcTemplate().execute("REVOKE INSERT (parent_unit_id) ON TABLE sm_control.organization_unit "
                + "FROM sm_cp_runtime");

        // The partial index put back as V013's full constraint: a retired target would hold its name again.
        owner.getJdbcTemplate().execute("DROP INDEX sm_control.target_name_once");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target ADD CONSTRAINT target_name_once "
                + "UNIQUE (organization_id, name)");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("the full unique constraint").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V018").contains("target_name_once");
                });
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target DROP CONSTRAINT target_name_once");
        owner.getJdbcTemplate().execute("CREATE UNIQUE INDEX target_name_once ON sm_control.target "
                + "(organization_id, name) WHERE retired_at IS NULL");

        // An owner who put V017's body back is still fail-closed (every earlier probe passes), but OR-25 would refuse
        // HIERARCHY_NODE_CREATED as unknown and the no-actor ENTITLEMENT_SET would be open to any client address.
        owner.getJdbcTemplate().execute(functionBodyOf("V017__instance_retirement.sql"));
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("record_change_audit() reverted to V017").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V018")
                            .contains("HIERARCHY_NODE_CREATED");
                });
    }

    @Test
    void aDatabaseThatPredatesV019RefusesToStartNamingThePerOrganizationUsernameIndex() {
        // Plan 013 T3.2 (OR-125 (4)): a build that logs in by (organization, username) and creates a username that
        // another organization already holds must not start against the V018 schema, where the install-wide
        // principal_username_unique would answer the second organization's create with a spurious 409.
        runner(SmControlPostgres.runtimeUrlAtVersion("018"), SmControlPostgres.runtimePasswordAtVersion("018"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V018 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V019").contains("principal_org_username_ci_uq")
                            .contains("SmControlMigrationMain");
                });
    }

    @Test
    void aDatabaseThatPredatesV020RefusesToStartNamingTheOperatorRenameGate() {
        // Fable F-2 (OR-131 Q1): the rename command writes a no-actor HIERARCHY_NODE_RENAMED from 'operator-cli'; a V019
        // body would refuse it (OR-25) at the first run, so a V019 schema must not be treated as ready.
        runner(SmControlPostgres.runtimeUrlAtVersion("019"), SmControlPostgres.runtimePasswordAtVersion("019"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V019 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V020").contains("HIERARCHY_NODE_RENAMED")
                            .contains("SmControlMigrationMain");
                });
    }

    @Test
    void aDatabaseThatPredatesV026RefusesToStartNamingTheAgentRegistrationAndTheAgentVerbs() {
        // Plan 015 T1.3 (OR-126 A8, BE-D70): a build that mirrors the 47-verb catalog and writes AGENT_* operations must
        // not start against a V020 schema, where the first enrolment would fail OR-25 or a foreign key.
        runner(SmControlPostgres.runtimeUrlAtVersion("020"), SmControlPostgres.runtimePasswordAtVersion("020"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V020 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V026").contains("agent.enrol")
                            .contains("SmControlMigrationMain");
                });
    }

    @Test
    void aV026DatabaseThatLostAnAgentPieceOrV020sOperatorRenameGateRefusesToStart() {
        String cacheKey = "readiness-v026-pieces-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).as("fully migrated starts").isNull());

        // A Viewer that lost agent.view, an Operator that lost agent.drain: each is a stale role the grants would hide.
        owner.getJdbcTemplate().execute("DELETE FROM sm_control.role_permission WHERE role_id = "
                + "'00000000-0000-4000-8000-000000000002' AND permission_id = 'agent.view'");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("a Viewer without agent.view").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V026").contains("Viewer");
                });
        owner.getJdbcTemplate().execute("INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES "
                + "('00000000-0000-4000-8000-000000000002', 'agent.view')");
        owner.getJdbcTemplate().execute("DELETE FROM sm_control.role_permission WHERE role_id = "
                + "'00000000-0000-4000-8000-000000000003' AND permission_id = 'agent.drain'");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("an Operator without agent.drain").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V026").contains("Operator");
                });
        owner.getJdbcTemplate().execute("INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES "
                + "('00000000-0000-4000-8000-000000000003', 'agent.drain')");

        // The transport put back as V013's: the CP target row written at enrolment would be refused.
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target DROP CONSTRAINT target_transport");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target ADD CONSTRAINT target_transport "
                + "CHECK (transport IN ('ssh-docker'))");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("the SSH-only transport").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V026").contains("target_transport");
                });
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target DROP CONSTRAINT target_transport");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target ADD CONSTRAINT target_transport "
                + "CHECK (transport IN ('ssh-docker', 'agent'))");

        // An owner who put V020's body back keeps every V020 marker (so the V020 stage passes) but loses the agent actor.
        owner.getJdbcTemplate().execute(functionBodyOf("V020__operator_rename_no_actor.sql"));
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("record_change_audit() reverted to V020").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V026").contains("AGENT_");
                });

        // V026's body without V020's marker comment (V018's body plus the agent edits is what the earlier draft rebuilt):
        // refused by the V020 stage, which V026 does not replace.
        String v026 = functionBodyOf("V026__agent_registration_and_verbs.sql");
        owner.getJdbcTemplate().execute(v026.replace("no-actor HIERARCHY_NODE_RENAMED", "no-actor RENAMED"));
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("V026's body without V020's markers").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V020")
                            .contains("HIERARCHY_NODE_RENAMED");
                });
    }

    @Test
    void aV026DatabaseMissingTheAuditColumnTheWidenedCheckThePrivilegesOrTheTruncateGuardRefusesToStart() {
        // Plan 015 T1.3 review finding 4: each piece below would otherwise fail only at the first agent write.
        String cacheKey = "readiness-v026-structure-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).as("fully migrated starts").isNull());

        // the widened audit_operation CHECK put back without the agent operations: the first agent write is a 23514
        String check = owner.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname ="
                + " 'change_audit_audit_operation'", Map.of(), String.class);
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit DROP CONSTRAINT change_audit_audit_operation");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_audit_operation "
                + check.replace(", 'AGENT_INSTANCE_REGISTERED'", ""));
        assertRefuses(cacheKey, "a CHECK without AGENT_INSTANCE_REGISTERED", "change_audit_audit_operation");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit DROP CONSTRAINT change_audit_audit_operation");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_audit_operation "
                + check);

        // the runtime grants: a withdrawn UPDATE column, a withdrawn INSERT, an extra column, and a DELETE
        owner.getJdbcTemplate().execute("REVOKE UPDATE (fleet_revocation_epoch) ON sm_control.agent_registration FROM sm_cp_runtime");
        assertRefuses(cacheKey, "a withdrawn UPDATE column", "eleven granted columns");
        owner.getJdbcTemplate().execute("GRANT UPDATE (fleet_revocation_epoch) ON sm_control.agent_registration TO sm_cp_runtime");
        owner.getJdbcTemplate().execute("GRANT UPDATE (allowed_config_root) ON sm_control.agent_registration TO sm_cp_runtime");
        assertRefuses(cacheKey, "a twelfth UPDATE column", "eleven granted columns");
        owner.getJdbcTemplate().execute("REVOKE UPDATE (allowed_config_root) ON sm_control.agent_registration FROM sm_cp_runtime");
        owner.getJdbcTemplate().execute("GRANT DELETE ON sm_control.agent_registration TO sm_cp_runtime");
        assertRefuses(cacheKey, "a DELETE grant", "eleven granted columns");
        owner.getJdbcTemplate().execute("REVOKE DELETE ON sm_control.agent_registration FROM sm_cp_runtime");
        owner.getJdbcTemplate().execute("REVOKE INSERT ON sm_control.agent_registration FROM sm_cp_runtime");
        assertRefuses(cacheKey, "a withdrawn INSERT", "eleven granted columns");
        owner.getJdbcTemplate().execute("GRANT INSERT ON sm_control.agent_registration TO sm_cp_runtime");

        // the TRUNCATE guard gone
        owner.getJdbcTemplate().execute("DROP TRIGGER truncate_refused ON sm_control.agent_registration");
        assertRefuses(cacheKey, "no truncate_refused trigger", "truncate_refused");
        owner.getJdbcTemplate().execute("CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.agent_registration "
                + "FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation()");

        // the agent actor column gone (its foreign key goes with it)
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit DROP COLUMN actor_agent_id");
        assertRefuses(cacheKey, "no actor_agent_id", "actor_agent_id");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit ADD COLUMN actor_agent_id uuid NULL");

        // the body without the RAISE-text marker of the scoped agent-actor gate
        String v026 = functionBodyOf("V026__agent_registration_and_verbs.sql");
        owner.getJdbcTemplate().execute(v026.replace("an agent actor is only permitted for", "an actor is only permitted for"));
        assertRefuses(cacheKey, "a body without the agent-actor RAISE marker", "AGENT_");
    }

    private void assertRefuses(String cacheKey, String why, String named) {
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as(why).isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V026").contains(named);
                });
    }

    @Test
    void aDatabaseThatPredatesV027RefusesToStartNamingTheSystemActor() {
        // Plan 015 T4.6 (OR-140): a build whose scheduled enrolment sweep audits to the system actor must not start against a
        // V026 schema, where its first revoke would fail OR-25 as a write with no principal.
        runner(SmControlPostgres.runtimeUrlAtVersion("026"), SmControlPostgres.runtimePasswordAtVersion("026"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a V026 schema must not be treated as ready").isNotNull();
                    assertThat(causeChain(failure)).contains("V027").contains("system actor")
                            .contains("SmControlMigrationMain");
                });
    }

    @Test
    void aV027DatabaseThatLostASystemActorPieceRefusesToStart() {
        String cacheKey = "readiness-v027-pieces-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).as("fully migrated starts").isNull());

        // the body put back as V026's: every V020 and V026 marker is there, the system-actor gates are not
        String v027 = functionBodyOf("V027__system_actor.sql");
        owner.getJdbcTemplate().execute(functionBodyOf("V026__agent_registration_and_verbs.sql"));
        assertRefusesV027(cacheKey, "record_change_audit() reverted to V026", "system actor");
        owner.getJdbcTemplate().execute(v027);

        // the body without the RAISE-text marker of the unknown-actor gate
        owner.getJdbcTemplate().execute(v027.replace("is not a known system actor", "is not known"));
        assertRefusesV027(cacheKey, "a body without the unknown-actor gate", "system actor");
        owner.getJdbcTemplate().execute(v027);

        // the one-kind check put back over the two older kinds: a row naming a system actor and a principal would be accepted
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit DROP CONSTRAINT change_audit_actor_one_kind");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_actor_one_kind "
                + "CHECK (actor_principal_id IS NULL OR actor_agent_id IS NULL)");
        assertRefusesV027(cacheKey, "a one-kind check without the system actor", "change_audit_actor_one_kind");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit DROP CONSTRAINT change_audit_actor_one_kind");
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_actor_one_kind "
                + "CHECK (num_nonnulls(actor_principal_id, actor_agent_id, actor_system_id) <= 1)");

        // the runtime role granted the registry: it could mint an actor
        owner.getJdbcTemplate().execute("GRANT INSERT ON sm_control.system_actor TO sm_cp_runtime");
        assertRefusesV027(cacheKey, "a runtime INSERT grant on the registry", "holds a grant on system_actor");
        owner.getJdbcTemplate().execute("REVOKE INSERT ON sm_control.system_actor FROM sm_cp_runtime");

        // the column gone (its foreign key and the check go with it)
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.change_audit DROP COLUMN actor_system_id");
        assertRefusesV027(cacheKey, "no actor_system_id", "actor_system_id");
    }

    private void assertRefusesV027(String cacheKey, String why, String named) {
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as(why).isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V027").contains(named);
                });
    }

    @Test
    void aV019DatabaseWhoseUsernameIndexWasDroppedOrWhoseInstallWideConstraintWasPutBackRefusesToStart() {
        String cacheKey = "readiness-v019-pieces-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).as("fully migrated starts").isNull());

        // The per-organization index gone: two organizations' users could not be told apart and one could be doubled.
        owner.getJdbcTemplate().execute("DROP INDEX sm_control.principal_org_username_ci_uq");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("no per-organization username index").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V019")
                            .contains("principal_org_username_ci_uq");
                });

        // The index put back WITHOUT lower(): login compares without regard to case, so two spellings would coexist.
        owner.getJdbcTemplate().execute("CREATE UNIQUE INDEX principal_org_username_ci_uq ON sm_control.principal "
                + "(organization_id, username)");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("a case-sensitive index").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V019").contains("lower");
                });
        owner.getJdbcTemplate().execute("DROP INDEX sm_control.principal_org_username_ci_uq");
        owner.getJdbcTemplate().execute("CREATE UNIQUE INDEX principal_org_username_ci_uq ON sm_control.principal "
                + "(organization_id, lower(username))");

        // W5D-8: the index put back as a PARTIAL index: it would let two spellings coexist among the rows its predicate skips.
        owner.getJdbcTemplate().execute("DROP INDEX sm_control.principal_org_username_ci_uq");
        owner.getJdbcTemplate().execute("CREATE UNIQUE INDEX principal_org_username_ci_uq ON sm_control.principal "
                + "(organization_id, lower(username)) WHERE status = 'active'");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("a partial username index").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V019")
                            .contains("principal_org_username_ci_uq");
                });
        owner.getJdbcTemplate().execute("DROP INDEX sm_control.principal_org_username_ci_uq");
        owner.getJdbcTemplate().execute("CREATE UNIQUE INDEX principal_org_username_ci_uq ON sm_control.principal "
                + "(organization_id, lower(username))");

        // V002's install-wide constraint back beside it: the other organization's create would 409 again.
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.principal ADD CONSTRAINT principal_username_unique "
                + "UNIQUE (username)");
        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    assertThat(context.getStartupFailure()).as("install-wide uniqueness back").isNotNull();
                    assertThat(causeChain(context.getStartupFailure())).contains("V019")
                            .contains("principal_username_unique");
                });
    }

    /**
     * m1: a trigger the owner outright DISABLED ({@code tgenabled = 'D'}) must refuse startup — the
     * new {@code tgenabled IN ('O','A')} check still catches this exactly as the old {@code <> 'D'}
     * one did. Own isolated container ({@code readiness-disabled-trigger-it}) so disabling a trigger
     * here never poisons the shared latest-version container other tests share.
     */
    @Test
    void aDatabaseWithAChangeAuditTriggerDisabledByTheOwnerRefusesToStart() {
        String cacheKey = "readiness-disabled-trigger-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().execute("ALTER TABLE sm_control.session DISABLE TRIGGER change_audit_trigger");

        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure)
                            .as("a change_audit_trigger the owner disabled must not be treated as ready")
                            .isNotNull();
                    assertThat(causeChain(failure)).contains("audit");
                });
    }

    /**
     * m1: a trigger set to {@code ENABLE REPLICA} ({@code tgenabled = 'R'}) passed the OLD
     * {@code <> 'D'} check but never actually fires for an ordinary application connection, which runs
     * under the default {@code session_replication_role = origin} — silently losing every audit row
     * on that table. Own isolated container, same reason as above.
     */
    @Test
    void aDatabaseWithAChangeAuditTriggerSetToFireOnlyForReplicaSessionsRefusesToStart() {
        String cacheKey = "readiness-replica-trigger-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().execute("ALTER TABLE sm_control.login_throttle ENABLE REPLICA TRIGGER change_audit_trigger");

        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure)
                            .as("a change_audit_trigger set ENABLE REPLICA never fires under the default "
                                    + "session_replication_role and must not be treated as ready")
                            .isNotNull();
                    assertThat(causeChain(failure)).contains("audit");
                });
    }

    /**
     * Fix 5 (MINOR): readiness must also require the 13 {@code truncate_refused} triggers (on the 13
     * OR-19 business tables — never fired by row-level DML, only by {@code TRUNCATE}) to be present
     * and enabled, the same {@code tgenabled IN ('O','A')} way the immutability triggers are checked.
     * Own isolated container ({@code readiness-dropped-truncate-refused-it}) so dropping a trigger
     * here never poisons the shared latest-version container other tests share.
     */
    @Test
    void aDatabaseWhereTheOwnerDroppedTruncateRefusedOnSessionRefusesToStart() {
        String cacheKey = "readiness-dropped-truncate-refused-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().execute("DROP TRIGGER truncate_refused ON sm_control.session");

        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure)
                            .as("a truncate_refused trigger the owner dropped must not be treated as ready")
                            .isNotNull();
                    assertThat(causeChain(failure)).contains("truncate");
                });
    }

    /**
     * (m2) {@code V012}'s fail-closed {@code OR-25} block keys on {@code session_user =
     * 'sm_cp_runtime'} — a name hard-coded into the trigger, never read from {@code
     * sm.control.runtime.username}. A connection under ANY other role — even the container's own
     * superuser, which trivially satisfies every OTHER readiness check this method makes — must
     * still be refused to start: connecting under any name but the literal {@code 'sm_cp_runtime'}
     * would silently fail OPEN every {@code OR-25} guarantee for that connection.
     */
    @Test
    void aRuntimeConnectionUnderAnyOtherRoleNameRefusesToStart() {
        new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + SmControlPostgres.superuserUrlAtVersion(SmControlPostgres.LATEST_VERSION),
                        "sm.control.runtime.username=" + SmControlPostgres.superuserUsernameAtVersion(SmControlPostgres.LATEST_VERSION),
                        "sm.control.runtime.password=" + SmControlPostgres.superuserPasswordAtVersion(SmControlPostgres.LATEST_VERSION))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure)
                            .as("a connection under a role other than sm_cp_runtime must not be treated as "
                                    + "ready, even one (like the container superuser) with every grant the "
                                    + "other readiness checks need")
                            .isNotNull();
                    assertThat(causeChain(failure)).contains("session_user").contains("sm_cp_runtime");
                });
    }

    @Test
    void anUnmigratedPhaseZeroOnlyDatabaseRefusesToStartWithAnActionableMessage() {
        String url = phaseZeroOnlyDatabase();

        runner(url, "phase0-runtime-password").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).as("a context over an UNMIGRATED sm_control must not start").isNotNull();
            String chain = causeChain(failure);
            assertThat(chain).contains("sm_control is not migrated to the shape this build expects")
                    .contains("SmControlMigrationMain with the sm_cp_migrator credential");
        });
    }

    /**
     * External finding: readiness must fail CLOSED against a V011 schema (and anything earlier) —
     * every OTHER check (tables, OR-19 triggers, immutability, truncate-refused, SECURITY DEFINER,
     * session_user) is fully satisfiable at V011, but V012's login_throttle.refusal_recorded_for
     * column (OR-24) and record_change_audit()'s fail-closed OR-25 block are both missing. The
     * failure must name the version this build actually requires and the one-shot remediation
     * command, not a bare "not migrated" with no version — sm_cp_runtime cannot read
     * flyway_schema_history to say which version it is looking at.
     */
    @Test
    void aV011DatabaseRefusesToStartNamingV012AndTheMigrationMain() {
        runner(SmControlPostgres.runtimeUrlAtVersion("011"), SmControlPostgres.runtimePasswordAtVersion("011"))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("a context over a V011 sm_control must not start").isNotNull();
                    String chain = causeChain(failure);
                    assertThat(chain).contains("V012").contains("SmControlMigrationMain");
                });
    }

    /**
     * External finding, BEHAVIOUR not presence: an owner who ran V012's {@code ALTER TABLE} (so
     * {@code refusal_recorded_for} exists and every earlier check, including the new column check,
     * passes) but reverted {@code record_change_audit()} to V011's fail-open body must still be
     * refused — a column's PRESENCE is not proof that OR-25's fail-closed BEHAVIOUR is actually in
     * force. Own disposable container ({@code readiness-fail-open-audit-it}) so reverting the
     * function here never poisons the shared latest-version container other tests share.
     */
    @Test
    void aV012SchemaWithTheFunctionRevertedToV011sFailOpenBodyRefusesToStartWithTheFailClosedMessage() {
        String cacheKey = "readiness-fail-open-audit-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().execute(V011_FAIL_OPEN_RECORD_CHANGE_AUDIT_BODY);

        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure)
                            .as("a record_change_audit() reverted to V011's fail-open body must not be "
                                    + "treated as ready, even though the V012 column still exists")
                            .isNotNull();
                    assertThat(causeChain(failure)).contains("audit trigger is not fail-closed: OR-25 not in force");
                });
    }

    /**
     * External finding: the OR-25 readiness probe inserts a throwaway {@code login_throttle} row
     * inside a transaction it ALWAYS rolls back, win or lose. Own disposable container so this
     * count-based assertion is never affected by any other test's traffic.
     */
    @Test
    void aSuccessfulStartLeavesNoThrowawayLoginThrottleOrChangeAuditRowBehind() {
        String cacheKey = "readiness-leaves-nothing-behind-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        // V014's migrator-side insert of the Operator's two verbs (OR-80) is audited with NULL context, so a freshly
        // migrated database is not empty: what must hold is that STARTING leaves the count exactly where it was.
        int auditRowsBeforeStart =
                jdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit", Map.of(), Integer.class);

        runner(SmControlPostgres.runtimeUrlAtVersion(cacheKey), SmControlPostgres.runtimePasswordAtVersion(cacheKey))
                .run(context -> assertThat(context.getStartupFailure()).isNull());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.login_throttle", Map.of(), Integer.class))
                .as("the readiness probe's own throwaway row must not persist").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit", Map.of(), Integer.class))
                .as("the readiness probe's own refused write must leave no change_audit row")
                .isEqualTo(auditRowsBeforeStart);
    }

    /**
     * External finding: V012 must not just START — it must actually SERVE the auth path OR-24/OR-25
     * protect. Drives a real address lockout through the wired {@link LocalAuthenticationService}
     * bean, confirms the probe path every subsequent refusal against that SAME lockout takes runs
     * with no SQL error (the exact failure mode a V011 schema would hit on
     * {@code refusal_recorded_for}), leaves exactly ONE {@code LOGIN_REFUSED} probe audit row for
     * the whole lockout (OR-24), and that a correct login from a DIFFERENT, unlocked address still
     * succeeds. Own disposable container so the row-count assertion is never affected by any other
     * test's traffic.
     */
    @Test
    void aRunningV012ContextServesTheAddressLockoutProbePathWithExactlyOneAuditRowPerLockout() {
        String cacheKey = "readiness-serves-auth-path-it";
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        String lockedAddress = "203.0.113.240";
        String unlockedAddress = "203.0.113.241";

        new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + SmControlPostgres.runtimeUrlAtVersion(cacheKey),
                        "sm.control.runtime.username=" + SmControlPostgres.RUNTIME_USERNAME,
                        "sm.control.runtime.password=" + SmControlPostgres.runtimePasswordAtVersion(cacheKey),
                        "sm.control.login-address.failure-threshold=1",
                        "sm.control.login-address.window=1m",
                        "sm.control.login-address.lockout=5m")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    LocalAuthenticationService login = context.getBean(LocalAuthenticationService.class);
                    String unknownUsername = "readiness-auth-path-" + UUID.randomUUID() + "@example.test";
                    RequestOrigin lockedOrigin = new RequestOrigin(lockedAddress, UUID.randomUUID());

                    // One wrong attempt locks the address (threshold 1) via recordFailure's own INSERT.
                    assertRefused(login, unknownUsername, lockedOrigin);
                    // Three further refusals against this SAME lockout each take the PROBE path
                    // (isLocked short-circuits before any principal lookup) — the exact path that
                    // would have thrown a SQL error against refusal_recorded_for on a V011 schema.
                    assertRefused(login, unknownUsername, lockedOrigin);
                    assertRefused(login, unknownUsername, lockedOrigin);
                    assertRefused(login, unknownUsername, lockedOrigin);

                    String addressSubjectHash = LocalLoginService.addressSubjectHash(lockedAddress);
                    assertThat(jdbc.queryForObject("""
                            SELECT count(*) FROM sm_control.change_audit
                             WHERE table_name = 'login_throttle' AND operation = 'UPDATE'
                               AND audit_operation = 'LOGIN_REFUSED' AND row_key ->> 'subject_hash' = :subjectHash
                            """, Map.of("subjectHash", addressSubjectHash), Integer.class))
                            .as("three refusals against the ONE active lockout leave exactly one probe audit row")
                            .isEqualTo(1);

                    // Principal creation is an OWNER-level operation (sm_cp_runtime holds only
                    // UPDATE(credential_hash, must_rotate) on principal, per V008's runtime grants —
                    // never INSERT) — mirrors LocalLoginIT/ChangeAuditIT's own createLocal helpers,
                    // which likewise insert through the migrator/owner jdbc template, never the
                    // context's runtime-backed PrincipalRepository bean.
                    CredentialHasher hasher = context.getBean(CredentialHasher.class);
                    UUID organizationId = UUID.randomUUID();
                    jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:id, 'fixture-' || gen_random_uuid()::text)",
                            Map.of("id", organizationId));
                    String realUsername = "readiness-auth-path-real-" + UUID.randomUUID() + "@example.test";
                    new JdbcPrincipalRepository(jdbc).createLocal(organizationId, realUsername,
                            hasher.hash("correct credential".toCharArray()), false);

                    LocalLoginResult result = login.authenticate(
                            new LocalCredentials(organizationId.toString(), realUsername, "correct credential".toCharArray()),
                            new RequestOrigin(unlockedAddress, UUID.randomUUID()));
                    assertThat(result).as("a correct login from a DIFFERENT, unlocked address still succeeds")
                            .isNotNull();
                });
    }

    private static void assertRefused(LocalAuthenticationService login, String username, RequestOrigin origin) {
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials("no-such-organization", username, "wrong credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
    }

    /**
     * V011's {@code record_change_audit()} body, verbatim minus its OR-25 fail-closed block —
     * exactly what an owner would be left with after reverting V012's {@code CREATE OR REPLACE
     * FUNCTION} while keeping V012's {@code ALTER TABLE} (the column) and every trigger. Used only
     * to prove readiness checks BEHAVIOUR, not just the column's presence.
     */
    private static final String V011_FAIL_OPEN_RECORD_CHANGE_AUDIT_BODY = """
            CREATE OR REPLACE FUNCTION sm_control.record_change_audit() RETURNS trigger
                LANGUAGE plpgsql
                SECURITY DEFINER
                SET search_path = sm_control, pg_temp
                AS $$
            DECLARE
                v_secret_cols text[] := ARRAY['credential_hash', 'token_hash'];
                v_actor uuid;
                v_operation text;
                v_correlation uuid;
                v_client text;
                v_row_key jsonb;
                v_old jsonb;
                v_new jsonb;
                v_changed text[];
                v_secret_changed text[];
                v_new_json jsonb;
                v_old_json jsonb;
            BEGIN
                IF TG_OP = 'DELETE' THEN
                    v_new_json := NULL;
                    v_old_json := to_jsonb(OLD);
                ELSIF TG_OP = 'INSERT' THEN
                    v_new_json := to_jsonb(NEW);
                    v_old_json := NULL;
                ELSE
                    v_new_json := to_jsonb(NEW);
                    v_old_json := to_jsonb(OLD);
                END IF;

                IF TG_TABLE_NAME = 'session' AND TG_OP = 'UPDATE'
                   AND (v_new_json - ARRAY['last_used_at', 'idle_expires_at']::text[])
                     = (v_old_json - ARRAY['last_used_at', 'idle_expires_at']::text[]) THEN
                    RETURN NEW;
                END IF;

                v_actor := sm_control.try_uuid(NULLIF(current_setting('sm.audit.actor', true), ''));
                v_operation := NULLIF(current_setting('sm.audit.operation', true), '');
                v_correlation := sm_control.try_uuid(NULLIF(current_setting('sm.audit.correlation_id', true), ''));
                v_client := NULLIF(current_setting('sm.audit.client_address', true), '');
                -- V011's fail-open body stops here: no OR-25 session_user = 'sm_cp_runtime' block.

                v_row_key := CASE TG_TABLE_NAME
                    WHEN 'organization' THEN
                        jsonb_build_object('organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'))
                    WHEN 'organization_unit' THEN
                        jsonb_build_object(
                            'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                            'organization_unit_id',
                                COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'))
                    WHEN 'environment' THEN
                        jsonb_build_object(
                            'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                            'organization_unit_id',
                                COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'),
                            'environment_id', COALESCE(v_new_json ->> 'environment_id', v_old_json ->> 'environment_id'))
                    WHEN 'solution' THEN
                        jsonb_build_object(
                            'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                            'organization_unit_id',
                                COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'),
                            'environment_id', COALESCE(v_new_json ->> 'environment_id', v_old_json ->> 'environment_id'),
                            'solution_id', COALESCE(v_new_json ->> 'solution_id', v_old_json ->> 'solution_id'))
                    WHEN 'instance' THEN
                        jsonb_build_object(
                            'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                            'organization_unit_id',
                                COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'),
                            'environment_id', COALESCE(v_new_json ->> 'environment_id', v_old_json ->> 'environment_id'),
                            'solution_id', COALESCE(v_new_json ->> 'solution_id', v_old_json ->> 'solution_id'),
                            'instance_id', COALESCE(v_new_json ->> 'instance_id', v_old_json ->> 'instance_id'))
                    WHEN 'scope' THEN
                        jsonb_build_object('scope_id', COALESCE(v_new_json ->> 'scope_id', v_old_json ->> 'scope_id'))
                    WHEN 'principal' THEN
                        jsonb_build_object('id', COALESCE(v_new_json ->> 'id', v_old_json ->> 'id'))
                    WHEN 'permission_catalog' THEN
                        jsonb_build_object('permission_id', COALESCE(v_new_json ->> 'permission_id', v_old_json ->> 'permission_id'))
                    WHEN 'role' THEN
                        jsonb_build_object('role_id', COALESCE(v_new_json ->> 'role_id', v_old_json ->> 'role_id'))
                    WHEN 'role_permission' THEN
                        jsonb_build_object(
                            'role_id', COALESCE(v_new_json ->> 'role_id', v_old_json ->> 'role_id'),
                            'permission_id', COALESCE(v_new_json ->> 'permission_id', v_old_json ->> 'permission_id'))
                    WHEN 'role_binding' THEN
                        jsonb_build_object('binding_id', COALESCE(v_new_json ->> 'binding_id', v_old_json ->> 'binding_id'))
                    WHEN 'session' THEN
                        jsonb_build_object('session_id', COALESCE(v_new_json ->> 'session_id', v_old_json ->> 'session_id'))
                    WHEN 'login_throttle' THEN
                        jsonb_build_object('subject_hash', COALESCE(v_new_json ->> 'subject_hash', v_old_json ->> 'subject_hash'))
                    ELSE
                        '{}'::jsonb
                END;

                IF TG_OP = 'INSERT' THEN
                    v_old := NULL;
                    v_new := v_new_json - v_secret_cols;
                    SELECT array_agg(k ORDER BY k) INTO v_changed FROM jsonb_object_keys(v_new_json) AS k;
                ELSIF TG_OP = 'DELETE' THEN
                    v_old := v_old_json - v_secret_cols;
                    v_new := NULL;
                    SELECT array_agg(k ORDER BY k) INTO v_changed FROM jsonb_object_keys(v_old_json) AS k;
                ELSE
                    v_old := v_old_json - v_secret_cols;
                    v_new := v_new_json - v_secret_cols;
                    SELECT array_agg(n.key ORDER BY n.key) INTO v_changed
                      FROM jsonb_each(v_new_json) AS n
                      JOIN jsonb_each(v_old_json) AS o ON o.key = n.key
                     WHERE n.value IS DISTINCT FROM o.value;
                END IF;

                SELECT array_agg(c ORDER BY c) INTO v_secret_changed
                  FROM unnest(COALESCE(v_changed, ARRAY[]::text[])) AS c
                 WHERE c = ANY (v_secret_cols);

                INSERT INTO sm_control.change_audit (
                    recorded_at, txid, table_name, operation, row_key, changed_columns,
                    old_values, new_values, secret_columns_changed, actor_principal_id,
                    audit_operation, correlation_id, client_address, db_role)
                VALUES (
                    clock_timestamp(), txid_current(), TG_TABLE_NAME, TG_OP, v_row_key,
                    COALESCE(v_changed, ARRAY[]::text[]), v_old, v_new,
                    COALESCE(v_secret_changed, ARRAY[]::text[]), v_actor, v_operation, v_correlation, v_client,
                    session_user::text);

                RETURN COALESCE(NEW, OLD);
            END;
            $$;
            """;

    private static ApplicationContextRunner runner(String url, String password) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + url,
                        "sm.control.runtime.username=" + SmControlPostgres.RUNTIME_USERNAME,
                        "sm.control.runtime.password=" + password);
    }

    /** A fresh container carrying only Phase 0's roles and schema — Flyway never touches it. */
    private static String phaseZeroOnlyDatabase() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:14");
        container.start();
        DataSource superuser = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        try (Connection connection = superuser.getConnection()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/sm_control_phase0.sql"))
                    .populate(connection);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("unable to run sm_control Phase 0", e);
        }
        new JdbcTemplate(superuser).execute(
                "ALTER ROLE " + SmControlPostgres.RUNTIME_USERNAME + " PASSWORD 'phase0-runtime-password'");
        // A bare location, never container.getJdbcUrl(): this URL is bound into SmControlProperties
        // below, which now enforces JdbcUrls' allowlist, and Testcontainers' own URL carries
        // ?loggerLevel=OFF, which that allowlist does not admit.
        return SmControlPostgres.jdbcUrl(container);
    }

    private static String functionBodyOf(String migration) {
        try (java.io.InputStream in = ControlPlaneConfigurationIT.class.getClassLoader()
                .getResourceAsStream("db/migration/sm_control/" + migration)) {
            String sql = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int start = sql.indexOf("CREATE OR REPLACE FUNCTION sm_control.record_change_audit()");
            return sql.substring(start, sql.indexOf("$$;", start) + "$$;".length());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String causeChain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            b.append(c).append(" | ");
        }
        return b.toString();
    }
}
