package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation <b>(s)</b> and obligation <b>(l)</b> of design §3.3, executed: the exact two-phase
 * script runs green on a fresh PostgreSQL 14 under exactly the stated credentials, and the topology
 * it leaves behind is <i>asserted</i>, not assumed.
 *
 * <p>The obligation is written as "the migration suite's own bootstrap test … standing because
 * execution evidence does not transfer to text it never ran". This class is that test: the DDL under
 * assertion is the DDL that ships, mirrored to the design by {@link SketchMirrorTest}.
 *
 * <p>The negative control is the discriminating half. The {@code REVOKE} is a silent WARNING no-op
 * for anyone but the grantor, so a run that only checks for absence of exceptions cannot tell a
 * correct install from a broken one. Roles are cluster-scoped, so — per the design's own harness note
 * — the control runs the {@code REVOKE} <b>in isolation</b>, in a second database owned by a
 * non-superuser installer.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmDataBootstrapIT {

    @Test
    void theContainerRunsThePinnedPostgres14FloorAsABootstrapSuperuserOwningSchemaPublic() {
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        String version = su.queryForObject("SELECT version()", Map.of(), String.class);
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL 14."),
                "SM-D26 pins PostgreSQL 14 as the floor; every obligation is a claim about 14. Was: "
                        + version);

        // Phase 0's last statement is only executable by the grantor of public's PUBLIC grant. The
        // whole (s) obligation is meaningless unless the executing identity really is that role.
        assertEquals(Boolean.TRUE,
                su.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                        Map.of(), Boolean.class),
                "Testcontainers' default user must be the bootstrap superuser");
        assertEquals(SmDataPostgres.container().getUsername(),
                su.queryForObject(
                        "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public'",
                        Map.of(), String.class),
                "on PG 14 schema public is owned by the bootstrap superuser — that is WHY the REVOKE "
                        + "works here and not for an installer");
    }

    @Test
    void phase0RunsCleanAsTheSuperuserWithNoErrorAndNoWarning() {
        List<SmDataPostgres.StatementOutcome> outcomes = SmDataPostgres.phase0Outcomes();

        assertEquals(11, outcomes.size(),
                "Phase 0 is the seven statements evidence/ddl-execution-pg14.md §2.2 measured, plus plan 010 T2.4's "
                        + "two (CREATE SCHEMA sm_secret, GRANT USAGE on it) and plan 011 P11-2.1's two (the same pair "
                        + "for sm_metrics, M-Q19)");
        for (SmDataPostgres.StatementOutcome o : outcomes) {
            assertTrue(o.warnings().isEmpty(),
                    "no statement of Phase 0 may warn when the executor is the bootstrap superuser; "
                            + o.sql() + " said " + o.warnings());
        }
    }

    @Test
    void afterPhase0TheRuntimeRoleCanNoLongerCreateAnythingInSchemaPublic() {
        SmDataPostgres.bootstrap();

        assertEquals(Boolean.FALSE,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT has_schema_privilege('sm_dp_runtime', 'public', 'CREATE')",
                        Map.of(), Boolean.class),
                "obligation (s)'s literal assertion");

        // The behavioural half: the privilege function could be read wrong; the CREATE cannot.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> SmDataPostgres.runtimeJdbc().getJdbcTemplate()
                        .execute("CREATE TABLE public.smuggled(x int)"));
    }

    @Test
    void phase0CreatesSmSecretOwnedBySmDpOwner() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        assertEquals("sm_dp_owner",
                su.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'sm_secret'",
                        Map.of(), String.class),
                "OR-30: credentials live in a schema of their own, owned like sm_data");
        assertEquals(Boolean.TRUE,
                su.queryForObject("SELECT has_schema_privilege('sm_dp_runtime', 'sm_secret', 'USAGE')",
                        Map.of(), Boolean.class),
                "without USAGE every table grant survives and every runtime query still fails");
        assertEquals(Boolean.FALSE,
                su.queryForObject("SELECT has_schema_privilege('sm_dp_runtime', 'sm_secret', 'CREATE')",
                        Map.of(), Boolean.class),
                "the runtime role may not create objects in the credential schema");
        assertEquals(List.of("kek_canary", "secret"),
                su.queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'sm_secret' AND c.relkind = 'r' ORDER BY c.relname",
                        Map.of(), String.class));
        assertEquals(List.of(), su.queryForList(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'sm_secret' AND c.relkind IN ('r','i')"
                        + "   AND pg_get_userbyid(c.relowner) <> 'sm_dp_owner'", Map.of(), String.class),
                "every sm_secret table and index is born owned by sm_dp_owner, not the migrator login");
    }

    @Test
    void phase0CreatesSmMetricsOwnedBySmDpOwnerWithRuntimeUsageOnly() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        assertEquals("sm_dp_owner",
                su.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'sm_metrics'",
                        Map.of(), String.class),
                "M-Q19 (OR-76): the metrics store is DP-owned, in a schema of its own, owned like sm_data");
        assertEquals(Boolean.TRUE,
                su.queryForObject("SELECT has_schema_privilege('sm_dp_runtime', 'sm_metrics', 'USAGE')",
                        Map.of(), Boolean.class));
        assertEquals(Boolean.FALSE,
                su.queryForObject("SELECT has_schema_privilege('sm_dp_runtime', 'sm_metrics', 'CREATE')",
                        Map.of(), Boolean.class),
                "the runtime role may not create objects in sm_metrics");
    }

    @Test
    void flywayRecordsSmDataHistoryInSmDataAndSmMetricsHistoryInSmMetrics() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        // Replaces the unfiltered relname lookup: once SmDataMigrationMain.run also migrates sm_metrics there are TWO
        // history tables, and the question is where each one is and who owns it.
        assertEquals(List.of("sm_data", "sm_metrics"),
                su.queryForList("SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relname = 'flyway_schema_history' ORDER BY n.nspname",
                        Map.of(), String.class),
                "one history table per migrated schema, none in public");
        for (String schema : List.of("sm_data", "sm_metrics")) {
            assertEquals("sm_dp_owner",
                    su.queryForObject("SELECT pg_get_userbyid(c.relowner) FROM pg_class c"
                                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE c.relname = 'flyway_schema_history' AND n.nspname = :s",
                            new MapSqlParameterSource("s", schema), String.class),
                    schema + ": initSql applies to the connection Flyway creates the history table on");
        }
    }

    @Test
    void aNonSuperuserInstallerGetsASilentWarningNoOpFromTheSameRevoke() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        // Roles are CLUSTER-scoped, so Phase 0 cannot be re-run literally — it would abort at
        // CREATE ROLE (42710) before reaching the statement under test. The design's harness note
        // prescribes exactly this shape: the REVOKE alone, in a second database owned by a
        // non-superuser. Idempotent so a re-run of the suite in a warm container still works.
        if (Boolean.FALSE.equals(su.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sm_installer')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE ROLE sm_installer LOGIN CREATEROLE "
                    + "PASSWORD 'installer-test-pw'");
        }
        if (Boolean.FALSE.equals(su.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'smdb2')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE smdb2 OWNER sm_installer");
        }

        DataSource installer = SmDataPostgres.dataSource(
                SmDataPostgres.jdbcUrl("smdb2"), "sm_installer", "installer-test-pw");
        NamedParameterJdbcTemplate installerJdbc = new NamedParameterJdbcTemplate(installer);

        assertEquals(Boolean.FALSE,
                installerJdbc.queryForObject(
                        "SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                        Map.of(), Boolean.class),
                "the control is only a control if the executor is NOT a superuser");
        assertEquals("sm_installer",
                installerJdbc.queryForObject(
                        "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'smdb2'",
                        Map.of(), String.class),
                "…and does own the database");

        List<String> warnings =
                SqlScript.execute(installer, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");

        assertEquals(1, warnings.size(), "expected exactly one warning, got: " + warnings);
        assertTrue(warnings.get(0).contains("no privileges could be revoked"),
                "the no-op must announce itself as a WARNING and nothing stronger. Was: " + warnings);
        assertEquals(Boolean.TRUE,
                installerJdbc.queryForObject(
                        "SELECT has_schema_privilege('sm_dp_runtime', 'public', 'CREATE')",
                        Map.of(), Boolean.class),
                "the control MUST leave the privilege in place — that is what discriminates it from "
                        + "the superuser run, and what makes the positive assertion meaningful");
    }

    @Test
    void flywayAppliesExactlyThirtySevenMigrationsIntoSmDataAndRecordsThemThere() {
        MigrateResult result = SmDataPostgres.bootstrap();

        assertEquals(37, result.migrationsExecuted,
                "the Phase-1 split is six migrations, plan 015 T4.1's V046 (agent_discovery_release), plan 015 T2.1b's V045 (agent_session_features), plan 015 T1.1's V043 (agent_gateway) and V044 (agent_gateway_maintenance) (written V028-V031, renumbered by OR-147), plan 013's V037 (connection_credential_secret) and plan 014's V038-V042 (connection runtime projection, connection-backed placement, the two deferrals, instance_role), plus plan 010 T2.1's V007, T2.2's V008, T2.4's V009, "
                        + "T5.2's V010, T6.8's V011, T7.1/T7.2's V012, OR-96's V013, T11.2's V014, OR-108's V015 and plan 011 "
                        + "P11-2.2's V016, and plan 011 P11-5.1's V017 (the integrator renumbered it from the lane's placeholder V018; no gap; T9.3 needed no "
                        + "migration), OR-112 (1)'s V018 (placement_start_observation) OR-112 (2)'s V019 (broker_connection_live_broker_once) and OR-112's second round V020 (apply_attempt_write_baseline) and plan 008 S2's V021 (intent_placement_identity, BE-D44) and plan 013 T1.3's V022 (target_connection_events, BE-D56) and plan 012 T3's V023 (instance_retirement, OPS-Q4) and plan 008 S4's V024 (apply_request_placement_guard, BE-D48) and plan 012 T7's V025 (apply_schedule, OPS-Q9) and OR-125 (1)'s V026 (placement_actor) and OR-125 (2)'s V027 (target_host_key_algorithm); "
                        + "V021-V023 were lane placeholders the integrator renumbered and V024/V025 are the combined schedule's allocations; V028-V036 is a gap (the old agent's, removed by OR-147): "
                        + result.migrations.stream().map(m -> m.version + " " + m.description).toList());
        assertEquals(List.of("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012", "013", "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "024", "025", "026", "027",
                        // V028-V036 are absent: the old agent's, removed by the OR-147 reconciliation
                        "037", "038", "039", "040", "041", "042", "043", "044", "045", "046"),
                result.migrations.stream().map((MigrateOutput m) -> m.version).toList(),
                "applied in version order");
        assertEquals(SmDataMigrator.SCHEMA, result.schemaName);

        assertEquals(SmDataMigrator.SCHEMA,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relname = 'flyway_schema_history' AND n.nspname = :s",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), String.class),
                "defaultSchema(sm_data) must put the history table in sm_data, not in public (schema-qualified: "
                        + "sm_metrics has a history table of its own since plan 011 P11-2.1)");
        assertEquals("sm_dp_owner",
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_get_userbyid(c.relowner) FROM pg_class c"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relname = 'flyway_schema_history' AND n.nspname = :s",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), String.class),
                "initSql must apply to the connection Flyway creates the history table on, or the "
                        + "history table is the one object the migrator owns");
    }

    @Test
    void everyObjectInSmDataIsOwnedBySmDpOwnerRatherThanByTheMigratorLogin() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        List<Map<String, Object>> notOwner = su.queryForList(
                "SELECT c.relname, c.relkind, pg_get_userbyid(c.relowner) AS owner"
                        + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = :s AND c.relkind IN ('r','i')"
                        + "   AND pg_get_userbyid(c.relowner) <> 'sm_dp_owner'",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA));
        assertTrue(notOwner.isEmpty(), "tables/indexes not owned by sm_dp_owner: " + notOwner);

        Integer tables = su.queryForObject(
                "SELECT count(*)::int FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = :s AND c.relkind = 'r'",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), Integer.class);
        assertEquals(35, tables, "five apply tables, V007's three inventory tables, V010's config_generation, V011's "
                + "apply_reconciliation, V012's target_host_key and placement_health, V013's designer_layout, V014's "
                + "fleet_action and fleet_member, V016's five metric-definition tables, V017's broker_connection, V018's placement_start_observation, V022's target_connection_event, V023's instance_retirement, V025's apply_schedule, V038's connection_runtime (plan 014), V043's eight agent tables (agent_identity, agent_session, agent_lease, agent_command, agent_report_watermark, agent_telemetry_watermark, agent_discovery, agent_event; plan 015's V028, renumbered by OR-147), plus flyway_schema_history");

        List<Map<String, Object>> functions = su.queryForList(
                "SELECT p.proname, pg_get_userbyid(p.proowner) AS owner FROM pg_proc p"
                        + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = :s"
                        + " ORDER BY p.proname",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA));
        assertEquals(List.of("agent_command_placement_on_agents_target", "agent_discovery_terminal_is_final", "agent_discovery_tombstone_on_retirement", "agent_gateway_maintenance",
                        "agent_identity_revocation_is_sticky", "agent_session_epoch_only_rises",
                        "apply_schedule_moves_forward", "apply_step_intent_placement_identity", "config_generation_protected",
                        "connection_runtime_not_retired_under_live_placements",
                        "endpoint_host_port_free_on_target",
                        "fleet_action_moves_forward", "fleet_member_guard",
                        "fleet_member_stamps_parent", "forbid_terminal_regression",
                        "instance_lifecycle_moves_forward", "instance_retirement_closed_once", "instance_watermark_only_rises",
                        "package_digest_matches_recorded", "placement_health_port_free_of_metrics_ports",
                        "placement_immutable_except_retirement", "placement_inside_target_envelope",
                        "placement_watermark_only_rises", "stamp_apply_request_state_version",
                        "stamp_apply_schedule_state_version", "stamp_config_generation_state_version", "stamp_fleet_action_state_version",
                        "stamp_instance_retirement_state_version", "stamp_instance_state_version",
                        "stamp_placement_health_state_version", "target_keys_follow_retirement", "target_not_retired_under_live_placements",
                        "target_pinned_key_recorded"),
                functions.stream().map(r -> (String) r.get("proname")).toList(),
                "one function per guarded table, never a shared TG_TABLE_NAME branch (§3.1; OR-147: plan 015's V028..V031 are V043..V046, and plan 014's V039 adds connection_runtime_not_retired_under_live_placements and with V040 replaces placement_inside_target_envelope's body; V031 adds one, the tombstone a placement's retirement writes for an agent-registered instance (and replaces agent_discovery_terminal_is_final's body with the release rule); V028 adds four, agent_command's own-target guard, agent_identity's sticky revocation, agent_session's rising-only fencing epoch and agent_discovery's final terminal states, and replaces target_pinned_key_recorded's body with a NULL guard; V029 adds the maintenance function; V025 adds two, the schedule's forward-only guard and its stamp; V023 adds two, the retirement record's closed-once guard and its stamp, and replaces "
                        + "instance_lifecycle_moves_forward's body; V021 adds one, "
                        + "apply_step_intent's placement identity; V007 adds five, V008 one, "
                        + "V010 two: config_generation's stamp and its delete protection; V012 two: a target's keys follow its "
                        + "pin and its retirement; V014 four: fleet_action's stamp and forward-only move, fleet_member's "
                        + "guard and its stamping of its parent; V016 three: one digest guard shared by both child tables, "
                        + "and one port guard per direction)");
        for (Map<String, Object> f : functions) {
            assertEquals("sm_dp_owner", f.get("owner"), "function " + f.get("proname"));
        }

        assertEquals(List.of("agent_command_placement", "agent_discovery_terminal", "agent_identity_revocation", "agent_session_epoch", "apply_request_stamp", "apply_request_terminal_guard", "apply_schedule_forward", "apply_schedule_stamp", "apply_scope_monotonic",
                        "apply_step_intent_placement_identity", "config_generation_protect", "config_generation_stamp",
                        // plan 014 V039 (OR-147 union): connection_runtime's retire guard
                        "connection_runtime_retire_guard",
                        "fleet_action_forward", "fleet_action_stamp", "fleet_member_guard", "fleet_member_stamps_parent",
                        "instance_retirement_guard", "instance_retirement_stamp", "instance_state_lifecycle", "instance_state_stamp", "metric_definition_digest",
                        "metric_restart_signal_digest", "placement_discovery_tombstone", "placement_envelope",
                        "placement_health_port_free", "placement_health_stamp", "placement_immutable",
                        "placement_metrics_endpoint_port_free", "placement_watermark_monotonic",
                        "target_connection_keys_retire",
                        "target_connection_pinned_key_insert", "target_connection_pinned_key_update",
                        "target_connection_retire_guard"),
                su.queryForList(
                        "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = :s AND NOT t.tgisinternal ORDER BY t.tgname",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), String.class));
    }

    @Test
    void theRuntimeRoleIsNotAnOwnerMemberAndCannotSetRoleToOne() {
        SmDataPostgres.bootstrap();

        assertEquals(Boolean.FALSE,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_has_role('sm_dp_runtime', 'sm_dp_owner', 'MEMBER')",
                        Map.of(), Boolean.class),
                "obligation (l): the topology must not drift silently");
        assertEquals(Boolean.TRUE,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_has_role('sm_dp_migrator', 'sm_dp_owner', 'MEMBER')",
                        Map.of(), Boolean.class),
                "…while the migrator IS a member, or Phase 1 could not have run");

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> SmDataPostgres.runtimeJdbc().getJdbcTemplate().execute("SET ROLE sm_dp_owner"));
    }

    @Test
    void migratingADatabaseThatSkippedPhase0FailsLoudlyRatherThanCreatingTheSchemaItself() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();
        if (Boolean.FALSE.equals(su.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'smdb_nophase0')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE smdb_nophase0");
        }

        DataSource noPhase0 = SmDataPostgres.dataSource(
                SmDataPostgres.jdbcUrl("smdb_nophase0"),
                "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);

        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> SmDataMigrator.migrate(noPhase0));

        // Measured shape, so the plan can quote it: Flyway logs a WARN about createSchemas being
        // false, then tries to create its history table in sm_data and dies on the driver's
        // 3F000. The top-level type is FlywaySqlScriptException whose message embeds the SQLSTATE;
        // the root cause is a PSQLException reading 'schema "sm_data" does not exist'.
        assertTrue(failure.getMessage().contains("3F000"),
                "expected SQLSTATE 3F000 (invalid_schema_name) in the message. Was: "
                        + failure.getClass().getName() + ": " + failure.getMessage());
        assertTrue(failure.getMessage().contains("schema \"" + SmDataMigrator.SCHEMA
                        + "\" does not exist"),
                "the failure must name the missing schema so an operator knows Phase 0 was skipped. "
                        + "Was: " + failure.getMessage());
        assertEquals("3F000", Refusals.unwrap(failure).getSQLState(),
                "…and the root cause must be the driver error, not a Flyway-side guess");
        assertFalse(Boolean.TRUE.equals(new NamedParameterJdbcTemplate(noPhase0).queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :s)",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), Boolean.class)),
                "createSchemas(false) must not have created sm_data on the way to failing");
    }
}
