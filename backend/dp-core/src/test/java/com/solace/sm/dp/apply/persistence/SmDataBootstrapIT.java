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

        assertEquals(7, outcomes.size(),
                "Phase 0 is seven statements — the count evidence/ddl-execution-pg14.md §2.2 measured");
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
    void flywayAppliesExactlySixMigrationsIntoSmDataAndRecordsThemThere() {
        MigrateResult result = SmDataPostgres.bootstrap();

        assertEquals(6, result.migrationsExecuted,
                "the Phase-1 split is six migrations: " + result.migrations.stream()
                        .map(m -> m.version + " " + m.description).toList());
        assertEquals(List.of("001", "002", "003", "004", "005", "006"),
                result.migrations.stream().map((MigrateOutput m) -> m.version).toList(),
                "applied in version order");
        assertEquals(SmDataMigrator.SCHEMA, result.schemaName);

        assertEquals(SmDataMigrator.SCHEMA,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relname = 'flyway_schema_history'",
                        Map.of(), String.class),
                "defaultSchema(sm_data) must put the history table in sm_data, not in public");
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
        assertEquals(6, tables, "five sm_data tables plus flyway_schema_history");

        List<Map<String, Object>> functions = su.queryForList(
                "SELECT p.proname, pg_get_userbyid(p.proowner) AS owner FROM pg_proc p"
                        + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = :s"
                        + " ORDER BY p.proname",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA));
        assertEquals(List.of("forbid_terminal_regression", "instance_watermark_only_rises",
                        "placement_watermark_only_rises"),
                functions.stream().map(r -> (String) r.get("proname")).toList(),
                "three functions — one guard per table, never a shared TG_TABLE_NAME branch (§3.1)");
        for (Map<String, Object> f : functions) {
            assertEquals("sm_dp_owner", f.get("owner"), "function " + f.get("proname"));
        }

        assertEquals(List.of("apply_request_terminal_guard", "apply_scope_monotonic",
                        "placement_watermark_monotonic"),
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
