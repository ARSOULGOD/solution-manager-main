package com.solace.sm.cp.persistence;

import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import com.solace.sm.cp.authz.PrincipalOrganizationResolver;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.hierarchy.HierarchyTreeRepository;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.LoginThrottleRepository;
import com.solace.sm.cp.identity.PrincipalRepository;
import com.solace.sm.cp.identity.RoleBindingRepository;
import com.solace.sm.cp.identity.SessionRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code sm_control} access graph as one bean set — mirroring dp-core's
 * {@code ApplyPersistenceConfiguration} (design §3.1's DP precedent, applied to CP): every query
 * served with the deliberately weak {@code sm_cp_runtime} credential, and holding no other.
 *
 * <p><b>Why the repositories live here too, and not in {@code ControlPlaneConfiguration}.</b>
 * {@code arch-tests}' {@code jdbcIsConfinedToPersistencePackages} confines every class that
 * references {@code java.sql}/{@code javax.sql}/{@code org.springframework.jdbc}/
 * {@code com.zaxxer.hikari}/{@code org.flywaydb} to {@code com.solace.sm.cp.persistence..} (or
 * dp-core's equivalent). A {@code @Bean} factory method taking {@link NamedParameterJdbcTemplate} or
 * {@link DataSource} as a parameter is itself such a reference — ArchUnit counts parameter types as
 * dependencies — so every repository whose constructor takes {@link NamedParameterJdbcTemplate} must
 * be built by a class that already resides in this package. {@code ControlPlaneConfiguration} builds
 * the SERVICES on top of these repository beans, using only cp-api/cp-core interface types that carry
 * no such reference.
 *
 * <p><b>Migration is not here.</b> {@code sm_cp_migrator} is a member of {@code sm_cp_owner}; giving
 * this process that credential would put a schema-owner password in a bean any component of the JVM
 * could read. Phase 1 is {@link SmControlMigrationMain}, a separate one-shot process the install
 * runbook runs before this one starts. What replaces the ordering edge is
 * {@link #smControlSchemaReadiness} — a check the runtime role CAN make, refusing to serve a schema
 * the migration step has not reached.
 *
 * <p>{@code I5} note: every statement issued through {@link #smControlJdbc} binds its parameters —
 * {@code NamedParameterJdbcTemplate} is the only template exposed, and no {@code JdbcTemplate} bean is
 * published.
 */
@Configuration(proxyBeanMethods = false)
public class ControlPlanePersistenceConfiguration {

    /**
     * The tables the six exposed ports read or write, and the whole of what
     * {@code sm_cp_runtime} needs to see for this configuration to be usable.
     *
     * <p>Presence, not version: the same posture {@code ApplyPersistenceConfiguration} takes and
     * documents — {@code sm_cp_runtime} has no visibility into {@code flyway_schema_history}
     * (granted only to {@code sm_cp_owner}/{@code sm_cp_migrator}), so a version comparison is not a
     * check this role can honestly make.
     */
    static final String READINESS_QUERY =
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_control'"
                    + " AND table_name IN ('organization','organization_unit','environment','solution',"
                    + "'instance','scope','principal','session','login_throttle','role','role_permission',"
                    + "'role_binding')";

    /** How many of them there must be. */
    static final int EXPECTED_TABLES = 12;

    /**
     * The tables ruling {@code OR-19} requires a {@code change_audit_trigger} on, and the query that
     * counts which of them actually carry one.
     *
     * <p><b>Why readiness checks triggers, not just tables.</b> {@code OR-19} is a completeness
     * guarantee: every state change leaves an audit row, written by trigger. A database at V010 has
     * every business table and no triggers, so the table check above passes — and an application
     * started against it would change state with no audit trail at all, silently. {@code pg_trigger}
     * is a catalog readable by any role, so {@code sm_cp_runtime} can make this check without any
     * grant beyond what it already holds.
     */
    static final java.util.List<String> AUDITED_TABLES = java.util.List.of(
            "organization", "organization_unit", "environment", "solution", "instance", "scope",
            "principal", "permission_catalog", "role", "role_permission", "role_binding", "session",
            "login_throttle");

    /**
     * {@code tgenabled IN ('O','A')} rather than {@code <> 'D'} (m1): a trigger an operator set to
     * {@code ENABLE REPLICA} ({@code 'R'}) passes {@code <> 'D'} but never actually fires under the
     * default {@code session_replication_role} ({@code origin}) a normal application connection runs
     * under — {@code 'O'} (origin, the default) and {@code 'A'} (always) are the only settings under
     * which this trigger is guaranteed to fire for this role's writes.
     */
    static final String AUDIT_READINESS_QUERY =
            "SELECT count(DISTINCT c.relname) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND t.tgname = 'change_audit_trigger'"
                    + " AND NOT t.tgisinternal AND t.tgenabled IN ('O','A')"
                    + " AND c.relname IN ('organization','organization_unit','environment','solution',"
                    + "'instance','scope','principal','permission_catalog','role','role_permission',"
                    + "'role_binding','session','login_throttle')";

    /**
     * The three triggers ({@code change_audit_immutable_update/delete/truncate}) that make
     * {@code sm_control.change_audit} itself append-only (m1). Missing or disabled, an owner-level
     * caller could edit or erase the very audit trail {@link #AUDIT_READINESS_QUERY} just confirmed is
     * being written — so readiness checks these too, the same {@code tgenabled IN ('O','A')} way.
     */
    static final String IMMUTABILITY_TRIGGERS_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'change_audit'"
                    + " AND NOT t.tgisinternal AND t.tgenabled IN ('O','A')"
                    + " AND t.tgname IN ('change_audit_immutable_update', 'change_audit_immutable_delete',"
                    + "'change_audit_immutable_truncate')";

    static final int EXPECTED_IMMUTABILITY_TRIGGERS = 3;

    /**
     * Fix 5: the 13 {@code truncate_refused} triggers on the OR-19 business tables themselves (M2:
     * {@code TRUNCATE} bypasses every row-level trigger, so a {@code TRUNCATE} on e.g. {@code
     * session} would erase state with no audit row at all unless refused outright). Missing or
     * disabled, an owner-level caller could truncate control-plane state silently — the same
     * {@code tgenabled IN ('O','A')} posture {@link #IMMUTABILITY_TRIGGERS_QUERY} uses for
     * change_audit's own triggers.
     */
    static final String TRUNCATE_REFUSED_TRIGGERS_QUERY =
            "SELECT count(DISTINCT c.relname) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND t.tgname = 'truncate_refused'"
                    + " AND NOT t.tgisinternal AND t.tgenabled IN ('O','A')"
                    + " AND c.relname IN ('organization','organization_unit','environment','solution',"
                    + "'instance','scope','principal','permission_catalog','role','role_permission',"
                    + "'role_binding','session','login_throttle')";

    static final int EXPECTED_TRUNCATE_REFUSED_TRIGGERS = 13;

    /**
     * {@code sm_cp_runtime} holds no {@code INSERT} on {@code change_audit} (V011's own grants) — the
     * audit trigger only writes there because it runs {@code SECURITY DEFINER} as the table owner. If
     * a future migration ever recreated this function without that property, every ordinary business
     * write would start failing outright rather than silently losing its audit row, but readiness
     * checks it anyway (m1) so that failure happens at startup, not on the first write in production.
     */
    static final String AUDIT_FUNCTION_SECURITY_DEFINER_QUERY =
            "SELECT p.prosecdef FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    /**
     * (m2) {@code V012}'s fail-closed block ({@code OR-25}) tests {@code session_user =
     * 'sm_cp_runtime'} — a name hard-coded into the trigger, not read from configuration. A
     * deployment that names its runtime login role anything else would connect successfully (given
     * the right grants) but silently fall OUTSIDE that block, so every OR-25 guarantee would fail
     * OPEN for it rather than closed. This has no SQL to query for "is my connection's role the one
     * the trigger keys on" other than asking Postgres directly what role this session actually is.
     */
    static final String SESSION_USER_QUERY = "SELECT session_user";

    /** The ONE role name {@code V012}'s {@code record_change_audit()} fail-closed block keys on. */
    static final String EXPECTED_RUNTIME_ROLE = "sm_cp_runtime";

    /**
     * External finding: a V011 schema satisfies every readiness check above — every table, every
     * OR-19 trigger, immutability, SECURITY DEFINER, and the correct {@code session_user} — yet
     * {@code login_throttle} lacks {@code refusal_recorded_for} (OR-24) and {@code
     * record_change_audit()} lacks OR-25's fail-closed block, both added by V012.
     * {@code information_schema.columns} is filtered by the querying role's own column privileges,
     * so this is readable by {@code sm_cp_runtime} without any grant beyond the {@code SELECT} V010
     * already gives it on {@code login_throttle}.
     */
    static final String LOGIN_THROTTLE_REFUSAL_COLUMN_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_control'"
                    + " AND table_name = 'login_throttle' AND column_name = 'refusal_recorded_for'";

    /**
     * The actionable message every V012-schema readiness failure carries: names the missing
     * decisions, the target version, and the exact remediation command — never a bare "run the
     * migration" with no version, since {@code sm_cp_runtime} cannot read {@code
     * flyway_schema_history} to say which version it is actually looking at.
     */
    static final String V012_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V012 (missing login_throttle.refusal_recorded_for — OR-24): "
                    + "this build requires V012, which also enforces OR-25's fail-closed audit trigger "
                    + "in sm_control.record_change_audit(). Run the one-shot migration — "
                    + "SmControlMigrationMain — as sm_cp_migrator before starting the application";

    // ---- V013 (plan 010 T2.0) -------------------------------------------------------------------------------
    //
    // V013 readiness is a SECOND stage, checked after everything above, rather than widening the V012-era
    // lists in place: a V010 or V011 schema must keep being refused with the message that names ITS missing
    // piece (the audit triggers, V012), not with a generic "found 12 of 16 tables" that would hide it.

    /** The four tables V013 adds — every one carries a {@code change_audit_trigger} and a {@code truncate_refused}. */
    static final java.util.List<String> V013_TABLES =
            java.util.List.of("catalog_solution", "solution_package", "entitlement", "target");

    static final String V013_TABLES_QUERY =
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_control'"
                    + " AND table_name = ANY (?)";

    /** V013's column on an existing table — the V012 {@code refusal_recorded_for} probe, one version on. */
    static final String V013_SOLUTION_COLUMN_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_control'"
                    + " AND table_name = 'solution' AND column_name = 'catalog_solution_id'";

    /**
     * Presence of V013's {@code record_change_audit()}: {@code pg_proc} is readable by every role, and V013's
     * body is the only one that names the entitlement and target tables. An owner who reverted the function
     * to V012's body would still pass every check above (V012's body IS fail-closed) while every V013 write
     * was refused by OR-25 as an unknown operation.
     */
    static final String V013_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%TARGET_RETIRED%' AND p.prosrc LIKE '%solution_package%' FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    /** Enabled ({@code 'O'}/{@code 'A'}) triggers of one name on the given tables — {@link #AUDIT_READINESS_QUERY}'s shape. */
    static final String V013_TRIGGER_QUERY =
            "SELECT count(DISTINCT c.relname) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND t.tgname = ?"
                    + " AND NOT t.tgisinternal AND t.tgenabled IN ('O','A') AND c.relname = ANY (?)";

    static final String V013_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V013 (the governance tables catalog_solution, solution_package, "
                    + "entitlement and target, solution.catalog_solution_id, and their audit-trigger body — "
                    + "plan 010 T2.0): this build requires V013. Run the one-shot migration — "
                    + "SmControlMigrationMain — as sm_cp_migrator before starting the application";

    private static int countWhere(DataSource dataSource, String query, String... leadingStrings)
            throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            int index = 1;
            for (int i = 0; i < leadingStrings.length; i++) {
                ps.setString(index++, leadingStrings[i]);
            }
            ps.setArray(index, c.createArrayOf("text", V013_TABLES.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The V013 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V013_TABLES}. */
    private static void verifyV013Governance(DataSource dataSource) throws SQLException {
        if (countWhere(dataSource, V013_TABLES_QUERY) != V013_TABLES.size()) {
            throw new IllegalStateException(V013_SCHEMA_MISSING_MESSAGE);
        }
        int column;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V013_SOLUTION_COLUMN_QUERY)) {
            rs.next();
            column = rs.getInt(1);
        }
        Boolean v013Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V013_AUDIT_FUNCTION_QUERY)) {
            v013Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (column != 1 || !Boolean.TRUE.equals(v013Function)) {
            throw new IllegalStateException(V013_SCHEMA_MISSING_MESSAGE);
        }
        if (countWhere(dataSource, V013_TRIGGER_QUERY, "change_audit_trigger") != V013_TABLES.size()) {
            throw new IllegalStateException("sm_control is not migrated to the shape this build expects: a "
                    + "V013 governance table is missing its enabled change_audit_trigger (ruling OR-19 requires "
                    + "every state change to be audited): run the migration step — SmControlMigrationMain with "
                    + "the sm_cp_migrator credential — before starting the application");
        }
        if (countWhere(dataSource, V013_TRIGGER_QUERY, "truncate_refused") != V013_TABLES.size()) {
            throw new IllegalStateException("sm_control is not migrated to the shape this build expects: a "
                    + "V013 governance table is missing its enabled truncate_refused trigger (TRUNCATE bypasses "
                    + "every row-level audit trigger, so it must be refused outright): run the migration step — "
                    + "SmControlMigrationMain with the sm_cp_migrator credential — before starting the "
                    + "application");
        }
    }

    // ---- V014 (plan 011 P11-2.3) -------------------------------------------------------------------------------
    //
    // A THIRD stage, for the same reason V013 is a second: a V013 schema keeps being refused with the message that
    // names its own missing piece rather than a generic count.

    static final String V014_DASHBOARD_TABLE_QUERY =
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_control'"
                    + " AND table_name = 'dashboard'";

    /**
     * Presence of V014's {@code record_change_audit()}: V014's body is the only one that names the dashboard
     * operations and the document digest. An owner who reverted the function to V013's body would still pass every
     * check above (V013's body IS fail-closed) while every dashboard write was refused by OR-25 as unknown.
     */
    static final String V014_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%DASHBOARD_RESET%' AND p.prosrc LIKE '%document_sha256%' FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    static final String V014_TRIGGER_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND t.tgname = ? AND NOT t.tgisinternal"
                    + " AND t.tgenabled IN ('O','A') AND c.relname = 'dashboard'";

    /** OR-80: the built-in Operator (V003's id) holds both verbs. The runtime role may read {@code role_permission}. */
    static final String V014_OPERATOR_VERBS_QUERY =
            "SELECT count(*) FROM sm_control.role_permission"
                    + " WHERE role_id = '00000000-0000-4000-8000-000000000003'"
                    + " AND permission_id IN ('metrics.export', 'metrics.forceRaw')";

    static final String V014_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V014 (the dashboard table, its audit-trigger body, and the Operator role's "
                    + "metrics.export and metrics.forceRaw verbs - plan 011 P11-2.3, OR-80): this build requires V014. "
                    + "Run the one-shot migration - SmControlMigrationMain - as sm_cp_migrator before starting the "
                    + "application";

    // ---- V016 (plan 012 T1, OR-121) -----------------------------------------------------------------------------
    //
    // A FOURTH stage, for the same reason V014 is a third: a V014 schema keeps being refused with the message that names
    // its own missing piece. A build that mirrors the (then) 43-verb catalog must not run against a 38-verb database, where an
    // operation naming one of the five would fail a foreign key (or, worse, be authorized by a stale role) instead of
    // being decided. The runtime role may read role_permission (V006), and a role_permission row exists only if the
    // catalog row it references does (role_permission_catalog_fk), so these two counts prove the catalog too.

    static final String V016_ADMINISTRATOR_VERBS_QUERY =
            "SELECT count(*) FROM sm_control.role_permission"
                    + " WHERE role_id = '00000000-0000-4000-8000-000000000001'"
                    + " AND permission_id IN ('instance.undeclare', 'instance.restart.overrideSafety',"
                    + " 'target.edit', 'hierarchy.edit', 'entitlement.set')";

    /** OR-121: the built-in Operator (V003's id) holds exactly these three of the new grants. */
    static final String V016_OPERATOR_VERBS_QUERY =
            "SELECT count(*) FROM sm_control.role_permission"
                    + " WHERE role_id = '00000000-0000-4000-8000-000000000003'"
                    + " AND permission_id IN ('instance.stop', 'instance.undeclare', 'instance.restart.overrideSafety')";

    static final String V016_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V016 (the permission catalog's five further verbs - instance.undeclare, "
                    + "instance.restart.overrideSafety, target.edit, hierarchy.edit and entitlement.set - and the "
                    + "built-in Operator's instance.stop, instance.undeclare and instance.restart.overrideSafety; plan 012 "
                    + "T1, OR-121): this build requires V016. Run the one-shot migration - SmControlMigrationMain - as "
                    + "sm_cp_migrator before starting the application";

    /** The V016 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V016_ADMINISTRATOR_VERBS_QUERY}. */
    private static void verifyV016Verbs(DataSource dataSource) throws SQLException {
        if (singleInt(dataSource, V016_ADMINISTRATOR_VERBS_QUERY) != 5) {
            throw new IllegalStateException(V016_SCHEMA_MISSING_MESSAGE + " (the built-in Administrator does not hold "
                    + "all five)");
        }
        if (singleInt(dataSource, V016_OPERATOR_VERBS_QUERY) != 3) {
            throw new IllegalStateException(V016_SCHEMA_MISSING_MESSAGE + " (the built-in Operator role does not "
                    + "hold all three of instance.stop, instance.undeclare and instance.restart.overrideSafety)");
        }
    }

    // ---- V017 (plan 012 T4, OR-121) -----------------------------------------------------------------------------
    //
    // A FIFTH stage, for the same reason V016 is a fourth: a V016 schema keeps being refused with the message that names
    // its own missing piece. The control plane's half of undeclare writes instance.retired_at/retired_by as the runtime
    // role and audits it as INSTANCE_UNDECLARED; a build that does that against a schema without them would fail at the
    // first undeclare, not at start.

    static final String V017_COLUMNS_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_control'"
                    + " AND table_name = 'instance' AND column_name IN ('retired_at', 'retired_by')";

    /**
     * V017's {@code record_change_audit()} is the only body that names INSTANCE_UNDECLARED; it must also keep V014's two
     * markers, so an owner who put an EARLIER body back (still fail-closed, so every earlier probe passes) is refused.
     */
    static final String V017_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%INSTANCE_UNDECLARED%' AND p.prosrc LIKE '%DASHBOARD_RESET%'"
                    + " AND p.prosrc LIKE '%document_sha256%' FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    /** The retired row is terminal: the guard trigger must exist and be enabled for an ordinary session. */
    static final String V017_TERMINAL_TRIGGER_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'instance'"
                    + " AND t.tgname = 'instance_retired_is_terminal' AND NOT t.tgisinternal"
                    + " AND t.tgenabled IN ('O','A')";

    /** The runtime role (the connection's own user) holds the column-scoped UPDATE the retirement needs. */
    static final String V017_RUNTIME_UPDATE_QUERY =
            "SELECT has_column_privilege('sm_control.instance', 'retired_at', 'UPDATE')"
                    + " AND has_column_privilege('sm_control.instance', 'retired_by', 'UPDATE')";

    static final String V017_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V017 (instance.retired_at and retired_by, the INSTANCE_UNDECLARED audit "
                    + "operation, the terminal-row trigger and the runtime role's column UPDATE - plan 012 T4, "
                    + "OR-121): this build requires V017. Run the one-shot migration - SmControlMigrationMain - as "
                    + "sm_cp_migrator before starting the application";

    /** The V017 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V017_COLUMNS_QUERY}. */
    private static void verifyV017Retirement(DataSource dataSource) throws SQLException {
        if (singleInt(dataSource, V017_COLUMNS_QUERY) != 2) {
            throw new IllegalStateException(V017_SCHEMA_MISSING_MESSAGE);
        }
        Boolean v017Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V017_AUDIT_FUNCTION_QUERY)) {
            v017Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(v017Function)) {
            throw new IllegalStateException(V017_SCHEMA_MISSING_MESSAGE + " (record_change_audit() does not name "
                    + "INSTANCE_UNDECLARED, or has lost an earlier operation's marker)");
        }
        if (singleInt(dataSource, V017_TERMINAL_TRIGGER_QUERY) != 1) {
            throw new IllegalStateException("sm_control is not migrated to the shape this build expects: the V017 "
                    + "instance table is missing its enabled instance_retired_is_terminal trigger (a retired "
                    + "instance must stay retired): run the migration step - SmControlMigrationMain with the "
                    + "sm_cp_migrator credential - before starting the application");
        }
        Boolean runtimeMayRetire;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V017_RUNTIME_UPDATE_QUERY)) {
            runtimeMayRetire = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(runtimeMayRetire)) {
            throw new IllegalStateException(V017_SCHEMA_MISSING_MESSAGE + " (the runtime role holds no UPDATE on "
                    + "instance.retired_at and retired_by)");
        }
    }

    // ---- V018 (plan 013 T2.1, OR-121) ---------------------------------------------------------------------------
    //
    // A SIXTH stage, for the same reason V017 is a fifth: a V017 schema keeps being refused with the message that names
    // its own missing piece. The registration API writes sub-organizations, environments and names, a local principal
    // and role bindings as the runtime role, audits them under seven new operation names, and relies on a retired
    // target freeing its name; a build that does any of that against a V017 schema would fail at the first request
    // (OR-25 refusing an operation as unknown, or 42501), not at start.

    /**
     * V018's {@code record_change_audit()} is the only body that names HIERARCHY_NODE_CREATED and the only one that
     * narrows the no-actor ENTITLEMENT_SET to the operator command ({@code 'operator-cli'}); it must also keep V017's
     * INSTANCE_UNDECLARED and V014's two markers, so an owner who put an EARLIER body back (still fail-closed, so every
     * earlier probe passes) is refused.
     */
    static final String V018_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%HIERARCHY_NODE_CREATED%' AND p.prosrc LIKE '%ROLE_BINDING_REVOKED%'"
                    + " AND p.prosrc LIKE '%operator-cli%' AND p.prosrc LIKE '%INSTANCE_UNDECLARED%'"
                    + " AND p.prosrc LIKE '%DASHBOARD_RESET%' AND p.prosrc LIKE '%document_sha256%'"
                    + " FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    /**
     * The runtime role (the connection's own user) holds every privilege the registration API writes with: create a
     * sub-organization (top-level only: no INSERT on parent_unit_id) and an environment, rename the three node kinds,
     * create and deactivate a principal, grant and revoke a role binding.
     */
    static final String V018_RUNTIME_PRIVILEGES_QUERY =
            "SELECT has_column_privilege('sm_control.organization_unit', 'name', 'INSERT')"
                    + " AND NOT has_column_privilege('sm_control.organization_unit', 'parent_unit_id', 'INSERT')"
                    + " AND has_table_privilege('sm_control.environment', 'INSERT')"
                    + " AND has_column_privilege('sm_control.organization', 'name', 'UPDATE')"
                    + " AND has_column_privilege('sm_control.organization_unit', 'name', 'UPDATE')"
                    + " AND has_column_privilege('sm_control.environment', 'name', 'UPDATE')"
                    + " AND has_table_privilege('sm_control.principal', 'INSERT')"
                    + " AND has_column_privilege('sm_control.principal', 'status', 'UPDATE')"
                    + " AND has_column_privilege('sm_control.principal', 'deactivated_at', 'UPDATE')"
                    + " AND has_table_privilege('sm_control.role_binding', 'INSERT')"
                    + " AND has_table_privilege('sm_control.role_binding', 'DELETE')";

    /** A retired target frees its name (OQ-11 (b)): the one-name-per-organization index must be partial. */
    static final String V018_TARGET_NAME_INDEX_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'sm_control' AND tablename = 'target'"
                    + " AND indexname = 'target_name_once' AND indexdef LIKE '%WHERE (retired_at IS NULL)%'";

    static final String V018_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V018 (the registration API's grants on organization_unit, environment, "
                    + "principal and role_binding, the seven HIERARCHY_NODE_* / PRINCIPAL_* / ROLE_BINDING_* audit "
                    + "operations, the operator-cli-only no-actor ENTITLEMENT_SET and the partial target_name_once "
                    + "index - plan 013 T2.1, OR-121): this build requires V018. Run the one-shot migration - "
                    + "SmControlMigrationMain - as sm_cp_migrator before starting the application";

    /** The V018 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V018_AUDIT_FUNCTION_QUERY}. */
    private static void verifyV018RegistrationApi(DataSource dataSource) throws SQLException {
        Boolean v018Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V018_AUDIT_FUNCTION_QUERY)) {
            v018Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(v018Function)) {
            throw new IllegalStateException(V018_SCHEMA_MISSING_MESSAGE + " (record_change_audit() does not name "
                    + "HIERARCHY_NODE_CREATED, ROLE_BINDING_REVOKED or the operator-cli narrowing, or has lost an "
                    + "earlier operation's marker)");
        }
        Boolean runtimePrivileges;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V018_RUNTIME_PRIVILEGES_QUERY)) {
            runtimePrivileges = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(runtimePrivileges)) {
            throw new IllegalStateException(V018_SCHEMA_MISSING_MESSAGE + " (the runtime role lacks a registration "
                    + "grant, or holds INSERT on organization_unit.parent_unit_id)");
        }
        if (singleInt(dataSource, V018_TARGET_NAME_INDEX_QUERY) != 1) {
            throw new IllegalStateException(V018_SCHEMA_MISSING_MESSAGE + " (target_name_once is not the partial "
                    + "unique index on live targets)");
        }
    }

    // ---- V019 (plan 013 T3.2, OR-125 (4)) -----------------------------------------------------------------------
    //
    // A SEVENTH stage, for the same reason as the sixth: a V018 schema keeps being refused with the message that names
    // its own missing piece. Login now names the organization and createUser may reuse a username another organization
    // holds; against a V018 schema the install-wide constraint would answer that create with a 409, which is exactly the
    // cross-organization oracle V019 removes.

    /**
     * Exactly one index of that name, unique, over the organization and the LOWERCASED username (login compares without
     * regard to case, so a case-sensitive index would let two spellings coexist), and the install-wide constraint gone.
     */
    static final String V019_USERNAME_INDEX_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'sm_control' AND tablename = 'principal'"
                    + " AND indexname = 'principal_org_username_ci_uq' AND indexdef LIKE 'CREATE UNIQUE INDEX%'"
                    + " AND indexdef LIKE '%(organization_id, lower(username))%'"
                    + " AND indexdef NOT LIKE '% WHERE %'";

    static final String V019_INSTALL_WIDE_CONSTRAINT_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_constraint c JOIN pg_catalog.pg_class t ON t.oid = c.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND t.relname = 'principal'"
                    + " AND c.conname = 'principal_username_unique'";

    static final String V019_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V019 (the per-organization, case-insensitive unique index "
                    + "principal_org_username_ci_uq and the removal of the install-wide principal_username_unique - "
                    + "plan 013 T3.2, OR-125 (4)): this build requires V019. Run the one-shot migration - "
                    + "SmControlMigrationMain - as sm_cp_migrator before starting the application";

    /** The V019 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V019_USERNAME_INDEX_QUERY}. */
    private static void verifyV019UsernamePerOrganization(DataSource dataSource) throws SQLException {
        if (singleInt(dataSource, V019_USERNAME_INDEX_QUERY) != 1) {
            throw new IllegalStateException(V019_SCHEMA_MISSING_MESSAGE + " (principal_org_username_ci_uq is not a "
                    + "unique index on (organization_id, lower(username)))");
        }
        if (singleInt(dataSource, V019_INSTALL_WIDE_CONSTRAINT_QUERY) != 0) {
            throw new IllegalStateException(V019_SCHEMA_MISSING_MESSAGE + " (the install-wide "
                    + "principal_username_unique constraint is still present)");
        }
    }

    // ---- V020 (Fable F-2, OR-131 Q1) ----------------------------------------------------------------------------
    //
    // An EIGHTH stage: a V019 schema keeps being refused with the message that names its own missing piece. The
    // organization-rename operator command writes a HIERARCHY_NODE_RENAMED row with no actor from 'operator-cli'; against
    // a V019 body the database would refuse that write (OR-25) at the first run of the command, not at start.

    /** V020's {@code record_change_audit()} is the only body carrying the no-actor HIERARCHY_NODE_RENAMED gate. */
    static final String V020_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%no-actor HIERARCHY_NODE_RENAMED%' AND p.prosrc LIKE '%ROLE_BINDING_REVOKED%'"
                    + " AND p.prosrc LIKE '%operator-cli%' AND p.prosrc LIKE '%INSTANCE_UNDECLARED%'"
                    + " AND p.prosrc LIKE '%DASHBOARD_RESET%' AND p.prosrc LIKE '%document_sha256%'"
                    + " FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    static final String V020_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V020 (record_change_audit() without the operator-cli-only no-actor "
                    + "HIERARCHY_NODE_RENAMED on organization - Fable F-2, OR-131 Q1): this build requires V020. Run the "
                    + "one-shot migration - SmControlMigrationMain - as sm_cp_migrator before starting the application";

    /** The V020 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V020_AUDIT_FUNCTION_QUERY}. */
    private static void verifyV020OperatorRename(DataSource dataSource) throws SQLException {
        Boolean v020Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V020_AUDIT_FUNCTION_QUERY)) {
            v020Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(v020Function)) {
            throw new IllegalStateException(V020_SCHEMA_MISSING_MESSAGE + " (record_change_audit() does not carry the "
                    + "no-actor HIERARCHY_NODE_RENAMED gate, or has lost an earlier operation's marker)");
        }
    }

    // ---- V026 (plan 015 T1.3, written V021 before the OR-147 reconciliation; OR-126 A8, BE-D70) ----------------
    //
    // A NINTH stage: a V020 schema keeps being refused with the message that names its own missing piece, and V026 stands
    // ON TOP of V020's markers rather than replacing them - V026 rebuilds record_change_audit() from V020's body, so the
    // V020 stage above still has to pass against the V026 body (an owner who put an earlier body back loses V020's markers
    // and is refused there). A build that mirrors a 51-verb catalog and writes AGENT_* audit operations must not run
    // against a schema without them: the operation would fail OR-25 as unknown, or a foreign key, at the first enrolment.

    /** V026's body is the only one that reads the agent actor and names the agent operations. */
    static final String V026_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%sm.audit.actor_agent%' AND p.prosrc LIKE '%AGENT_ENROLMENT_ISSUED%'"
                    + " AND p.prosrc LIKE '%AGENT_REVOKED%' AND p.prosrc LIKE '%AGENT_ENROLLED%'"
                    + " AND p.prosrc LIKE '%AGENT_INSTANCE_REGISTERED%' AND p.prosrc LIKE '%actor_agent_id%'"
                    // a marker from a RAISE text (not a comment): the scoped agent-actor gates of plan 015 T1.3's fix round
                    + " AND p.prosrc LIKE '%an agent actor is only permitted for%'"
                    + " AND p.prosrc LIKE '%the agent actor may write only%'"
                    // OR-147: the body V026 rebuilds also carries the Connections edits of V021/V024/V025
                    + " AND p.prosrc LIKE '%CONNECTION_DELETED%' AND p.prosrc LIKE '%WHEN ''connection'' THEN%'"
                    + " FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    /** The registration table exists, and its audit trigger is enabled for an ordinary session. */
    static final String V026_REGISTRATION_TRIGGER_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'agent_registration'"
                    + " AND t.tgname = 'change_audit_trigger' AND NOT t.tgisinternal AND t.tgenabled IN ('O','A')";

    /** V013's {@code target_transport} CHECK, widened to admit the agent transport. */
    static final String V026_TARGET_TRANSPORT_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_constraint con"
                    + " JOIN pg_catalog.pg_class c ON c.oid = con.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'target' AND con.conname = 'target_transport'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%''agent''%'";

    /** Rows 48-51 (44-47 before the OR-147 reconciliation) and their grants: Administrator all four, Operator view + drain, Viewer view (OR-126 A8). */
    static final String V026_ADMINISTRATOR_VERBS_QUERY =
            "SELECT count(*) FROM sm_control.role_permission"
                    + " WHERE role_id = '00000000-0000-4000-8000-000000000001'"
                    + " AND permission_id IN ('agent.enrol', 'agent.revoke', 'agent.drain', 'agent.view')";

    static final String V026_OPERATOR_VERBS_QUERY =
            "SELECT count(*) FROM sm_control.role_permission"
                    + " WHERE role_id = '00000000-0000-4000-8000-000000000003'"
                    + " AND permission_id IN ('agent.drain', 'agent.view')";

    static final String V026_VIEWER_VERBS_QUERY =
            "SELECT count(*) FROM sm_control.role_permission"
                    + " WHERE role_id = '00000000-0000-4000-8000-000000000002' AND permission_id = 'agent.view'";

    /** The audit row's agent-actor column; without it every audited agent write fails 42703. */
    static final String V026_ACTOR_AGENT_COLUMN_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_control'"
                    + " AND table_name = 'change_audit' AND column_name = 'actor_agent_id'";

    /** The widened audit_operation CHECK: without it the first agent write is a 23514 at runtime. */
    static final String V026_AUDIT_OPERATION_CHECK_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_constraint con"
                    + " JOIN pg_catalog.pg_class c ON c.oid = con.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'change_audit'"
                    + " AND con.conname = 'change_audit_audit_operation'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%''AGENT_ENROLMENT_ISSUED''%'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%''AGENT_ENROLLED''%'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%''AGENT_INSTANCE_REGISTERED''%'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%''AGENT_REVOKED''%'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%''CONNECTION_DELETED''%'";

    /**
     * The runtime role's privileges on agent_registration: SELECT and INSERT, UPDATE on exactly the eleven columns the
     * two later writers need (the agent's consumption and the principal's revocation) and no other, and no DELETE.
     */
    static final String V026_RUNTIME_PRIVILEGES_QUERY =
            "SELECT has_table_privilege('sm_control.agent_registration', 'SELECT')"
                    + " AND has_table_privilege('sm_control.agent_registration', 'INSERT')"
                    + " AND NOT has_table_privilege('sm_control.agent_registration', 'DELETE')"
                    + " AND (SELECT count(*) FILTER (WHERE a.attname IN ('enrollment_used_at', 'enrollment_csr_digest',"
                    + " 'certificate_chain_der', 'trust_bundle_pem', 'valid_from', 'valid_to', 'target_id', 'status',"
                    + " 'revoked_at', 'agent_revocation_epoch', 'fleet_revocation_epoch')) = 11 AND count(*) = 11"
                    + " FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'sm_control.agent_registration'::regclass"
                    + " AND a.attnum > 0 AND NOT a.attisdropped"
                    + " AND has_column_privilege(a.attrelid, a.attnum, 'UPDATE'))";

    /** The registration table refuses TRUNCATE (as V013 and V014 stages check for their tables). */
    static final String V026_TRUNCATE_REFUSED_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'agent_registration'"
                    + " AND t.tgname = 'truncate_refused' AND NOT t.tgisinternal AND t.tgenabled IN ('O','A')";

    static final String V026_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V026 (the agent registration table, the agent actor and the AGENT_* audit "
                    + "operations in record_change_audit(), the agent target transport and the permission catalog's "
                    + "rows 48-51 agent.enrol, agent.revoke, agent.drain and agent.view - plan 015 T1.3, OR-126 A8): "
                    + "this build requires V026. Run the one-shot migration - SmControlMigrationMain - as "
                    + "sm_cp_migrator before starting the application";

    /** The V026 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V026_AUDIT_FUNCTION_QUERY}. */
    private static void verifyV026AgentRegistration(DataSource dataSource) throws SQLException {
        Boolean v021Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V026_AUDIT_FUNCTION_QUERY)) {
            v021Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(v021Function)) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (record_change_audit() does not read the "
                    + "agent actor or does not name the four AGENT_* operations)");
        }
        if (singleInt(dataSource, V026_REGISTRATION_TRIGGER_QUERY) != 1) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (agent_registration has no enabled "
                    + "change_audit_trigger)");
        }
        if (singleInt(dataSource, V026_TRUNCATE_REFUSED_QUERY) != 1) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (agent_registration has no enabled "
                    + "truncate_refused trigger)");
        }
        if (singleInt(dataSource, V026_ACTOR_AGENT_COLUMN_QUERY) != 1) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (change_audit has no actor_agent_id "
                    + "column)");
        }
        if (singleInt(dataSource, V026_AUDIT_OPERATION_CHECK_QUERY) != 1) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (the change_audit_audit_operation CHECK "
                    + "does not admit the four AGENT_* operations)");
        }
        Boolean runtimePrivileges;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V026_RUNTIME_PRIVILEGES_QUERY)) {
            runtimePrivileges = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(runtimePrivileges)) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (the runtime role lacks SELECT or INSERT "
                    + "on agent_registration, does not hold UPDATE on exactly its eleven granted columns, or holds "
                    + "DELETE)");
        }
        if (singleInt(dataSource, V026_TARGET_TRANSPORT_QUERY) != 1) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (sm_control.target.target_transport does "
                    + "not admit the 'agent' transport)");
        }
        if (singleInt(dataSource, V026_ADMINISTRATOR_VERBS_QUERY) != 4) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (the built-in Administrator does not hold "
                    + "all four agent verbs)");
        }
        if (singleInt(dataSource, V026_OPERATOR_VERBS_QUERY) != 2) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (the built-in Operator does not hold "
                    + "agent.view and agent.drain)");
        }
        if (singleInt(dataSource, V026_VIEWER_VERBS_QUERY) != 1) {
            throw new IllegalStateException(V026_SCHEMA_MISSING_MESSAGE + " (the built-in Viewer does not hold "
                    + "agent.view)");
        }
    }

    // ---- V027 (plan 015 T4.6, written V022 before the OR-147 reconciliation; OR-140) ----------------------------
    //
    // A TENTH stage, standing ON TOP of V026's markers (V027 rebuilds record_change_audit() from V026's body, so the V020 and V026
    // stages above still pass against it). A build whose scheduled jobs audit to the system actor must not run against a schema
    // without it: the sweep's first revoke would fail OR-25 as a write with no principal. The runtime role has no grant on
    // system_actor (it cannot mint an actor), so the table is checked in the catalog, not by reading its row.

    /** V027's body is the only one that reads the system actor and names the system-actor gates. */
    static final String V027_AUDIT_FUNCTION_QUERY =
            "SELECT p.prosrc LIKE '%sm.audit.actor_system%' AND p.prosrc LIKE '%actor_system_id%'"
                    + " AND p.prosrc LIKE '%a system actor is only permitted for%'"
                    + " AND p.prosrc LIKE '%the system actor may write only%'"
                    + " AND p.prosrc LIKE '%is not a known system actor%'"
                    + " FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'sm_control' AND p.proname = 'record_change_audit'";

    /** The system actor registry and the audit row's column and one-kind check over all three actor kinds. */
    static final String V027_SYSTEM_ACTOR_TABLE_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'system_actor' AND c.relkind = 'r'";

    static final String V027_ACTOR_SYSTEM_COLUMN_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_control'"
                    + " AND table_name = 'change_audit' AND column_name = 'actor_system_id'";

    static final String V027_ONE_KIND_CHECK_QUERY =
            "SELECT count(*) FROM pg_catalog.pg_constraint con"
                    + " JOIN pg_catalog.pg_class c ON c.oid = con.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'sm_control' AND c.relname = 'change_audit'"
                    + " AND con.conname = 'change_audit_actor_one_kind'"
                    + " AND pg_catalog.pg_get_constraintdef(con.oid) LIKE '%actor_system_id%'";

    /** The runtime role must NOT be able to write the registry (an actor is seeded by migration, never minted). */
    static final String V027_REGISTRY_PRIVILEGES_QUERY =
            "SELECT NOT has_table_privilege('sm_control.system_actor', 'SELECT')"
                    + " AND NOT has_table_privilege('sm_control.system_actor', 'INSERT')"
                    + " AND NOT has_table_privilege('sm_control.system_actor', 'UPDATE')"
                    + " AND NOT has_table_privilege('sm_control.system_actor', 'DELETE')"
                    + " AND NOT has_table_privilege('sm_control.system_actor', 'TRUNCATE')"
                    + " AND NOT has_table_privilege('sm_control.system_actor', 'REFERENCES')";

    static final String V027_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V027 (the system actor, the actor_system_id audit column and the system-actor gates in "
                    + "record_change_audit() for the scheduled enrolment sweep - plan 015 T4.6, OR-140): this build requires V027. "
                    + "Run the one-shot migration - SmControlMigrationMain - as sm_cp_migrator before starting the application";

    /** The V027 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V027_AUDIT_FUNCTION_QUERY}. */
    private static void verifyV027SystemActor(DataSource dataSource) throws SQLException {
        Boolean v022Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V027_AUDIT_FUNCTION_QUERY)) {
            v022Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(v022Function)) {
            throw new IllegalStateException(V027_SCHEMA_MISSING_MESSAGE + " (record_change_audit() does not read the "
                    + "system actor or does not carry its gates)");
        }
        if (singleInt(dataSource, V027_SYSTEM_ACTOR_TABLE_QUERY) != 1) {
            throw new IllegalStateException(V027_SCHEMA_MISSING_MESSAGE + " (sm_control.system_actor does not exist)");
        }
        if (singleInt(dataSource, V027_ACTOR_SYSTEM_COLUMN_QUERY) != 1) {
            throw new IllegalStateException(V027_SCHEMA_MISSING_MESSAGE + " (change_audit has no actor_system_id column)");
        }
        if (singleInt(dataSource, V027_ONE_KIND_CHECK_QUERY) != 1) {
            throw new IllegalStateException(V027_SCHEMA_MISSING_MESSAGE + " (change_audit_actor_one_kind does not cover "
                    + "the system actor)");
        }
        Boolean registryClosed;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V027_REGISTRY_PRIVILEGES_QUERY)) {
            registryClosed = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(registryClosed)) {
            throw new IllegalStateException(V027_SCHEMA_MISSING_MESSAGE + " (the runtime role holds a grant on system_actor)");
        }
    }

    private static int singleInt(DataSource dataSource, String query, String... parameters) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The V014 stage of {@link #smControlSchemaReadiness}; see the comment on {@link #V014_DASHBOARD_TABLE_QUERY}. */
    private static void verifyV014Dashboards(DataSource dataSource) throws SQLException {
        if (singleInt(dataSource, V014_DASHBOARD_TABLE_QUERY) != 1) {
            throw new IllegalStateException(V014_SCHEMA_MISSING_MESSAGE);
        }
        Boolean v014Function;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(V014_AUDIT_FUNCTION_QUERY)) {
            v014Function = rs.next() ? rs.getBoolean(1) : null;
        }
        if (!Boolean.TRUE.equals(v014Function)) {
            throw new IllegalStateException(V014_SCHEMA_MISSING_MESSAGE);
        }
        if (singleInt(dataSource, V014_OPERATOR_VERBS_QUERY) != 2) {
            throw new IllegalStateException(V014_SCHEMA_MISSING_MESSAGE + " (the built-in Operator role does not "
                    + "hold both metrics.export and metrics.forceRaw)");
        }
        if (singleInt(dataSource, V014_TRIGGER_QUERY, "change_audit_trigger") != 1) {
            throw new IllegalStateException("sm_control is not migrated to the shape this build expects: the V014 "
                    + "dashboard table is missing its enabled change_audit_trigger (ruling OR-19 requires every "
                    + "state change to be audited): run the migration step - SmControlMigrationMain with the "
                    + "sm_cp_migrator credential - before starting the application");
        }
        if (singleInt(dataSource, V014_TRIGGER_QUERY, "truncate_refused") != 1) {
            throw new IllegalStateException("sm_control is not migrated to the shape this build expects: the V014 "
                    + "dashboard table is missing its enabled truncate_refused trigger (TRUNCATE bypasses every "
                    + "row-level audit trigger, so it must be refused outright): run the migration step - "
                    + "SmControlMigrationMain with the sm_cp_migrator credential - before starting the application");
        }
    }

    // ---- V015 (OR-120) -----------------------------------------------------------------------------------------
    //
    // A FOURTH stage: the hierarchy reads select the display `name` of organization, organization_unit and
    // environment, so a V014 schema is refused at startup with a message naming the missing migration rather than
    // failing the first hierarchy read.

    static final String V015_NAME_COLUMNS_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_control'"
                    + " AND column_name = 'name' AND is_nullable = 'NO'"
                    + " AND table_name IN ('organization', 'organization_unit', 'environment')";

    static final String V015_SCHEMA_MISSING_MESSAGE =
            "sm_control schema predates V015 (the NOT NULL display name column on organization, organization_unit "
                    + "and environment - OR-120): this build requires V015. Run the one-shot migration - "
                    + "SmControlMigrationMain - as sm_cp_migrator before starting the application";

    private static void verifyV015HierarchyNames(DataSource dataSource) throws SQLException {
        if (singleInt(dataSource, V015_NAME_COLUMNS_QUERY) != 3) {
            throw new IllegalStateException(V015_SCHEMA_MISSING_MESSAGE);
        }
    }

    /**
     * The random, throwaway {@code login_throttle} subject hash the OR-25 behaviour probe below
     * writes and then always rolls back. Sixty-four lowercase hex characters, matching {@code
     * login_throttle_subject_hash_shape} exactly, so the probe write cannot fail on that constraint
     * and be mistaken for an OR-25 refusal.
     */
    private static String randomSubjectHash() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * External finding: PRESENCE of V012's schema (the column above) is not the same as V012's
     * fail-closed BEHAVIOUR actually being in force — an owner could have run V012's {@code ALTER
     * TABLE} but reverted {@code record_change_audit()} to V011's fail-open body, which would satisfy
     * every check above AND {@link #LOGIN_THROTTLE_REFUSAL_COLUMN_QUERY} while OR-25 never fires. The
     * only honest check is to attempt the write OR-25 exists to refuse: as {@code sm_cp_runtime}, with
     * {@code sm.audit.*} cleared, insert a throwaway {@code login_throttle} row and confirm it is
     * refused with SQLSTATE {@code OR025}. Runs in its own transaction, ALWAYS rolled back — success
     * or failure — so this leaves neither the throwaway row nor a {@code change_audit} row behind.
     */
    private static void verifyOr25FailClosedAuditIsInForce(DataSource smControlDataSource) {
        String subjectHash = randomSubjectHash();
        try (Connection c = smControlDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement clearContext = c.createStatement()) {
                    // Empty string, not SQL NULL — the same convention JdbcAuditContext uses, and what
                    // the trigger's NULLIF(..., '') normalizes back to "absent".
                    clearContext.execute("SELECT set_config('sm.audit.actor', '', true), "
                            + "set_config('sm.audit.operation', '', true), "
                            + "set_config('sm.audit.correlation_id', '', true), "
                            + "set_config('sm.audit.client_address', '', true)");
                }
                try (PreparedStatement probe = c.prepareStatement(
                        "INSERT INTO sm_control.login_throttle "
                                + "(subject_hash, window_started_at, failure_count, locked_until) "
                                + "VALUES (?, now(), 0, NULL)")) {
                    probe.setString(1, subjectHash);
                    probe.executeUpdate();
                    // No exception: the write went through with no audit context at all. OR-25's
                    // fail-closed block is not in force — the column exists but the behaviour does not.
                    throw new IllegalStateException("sm_control.record_change_audit() is not enforcing "
                            + "OR-25's fail-closed audit — audit trigger is not fail-closed: OR-25 not "
                            + "in force (a runtime write with no audit context succeeded when it must be "
                            + "refused with SQLSTATE OR025). Run the one-shot migration — "
                            + "SmControlMigrationMain — as sm_cp_migrator before starting the application");
                } catch (SQLException refusal) {
                    if (!"OR025".equals(refusal.getSQLState())) {
                        throw new IllegalStateException(
                                "sm_control readiness probe for OR-25's fail-closed audit trigger failed "
                                        + "unexpectedly: " + refusal.getMessage(), refusal);
                    }
                    // Expected: OR-25 refused the write exactly as it should. Fall through to rollback.
                }
            } finally {
                c.rollback();
            }
        } catch (SQLException connectionFailure) {
            throw new IllegalStateException("unable to run the sm_control OR-25 readiness probe: "
                    + connectionFailure.getMessage(), connectionFailure);
        }
    }

    /**
     * Refuses to start against a schema {@link SmControlMigrationMain} has not migrated.
     *
     * @see com.solace.sm.dp.apply.persistence.ApplyPersistenceConfiguration#smDataSchemaReadiness the
     *      DP precedent this mirrors, including why presence-of-tables is the check and not a version
     */
    @Bean
    InitializingBean smControlSchemaReadiness(
            @Qualifier("smControlDataSource") DataSource smControlDataSource) {
        return () -> {
            int found;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED_TABLES) {
                throw new IllegalStateException("sm_control is not migrated to the shape this build "
                        + "expects (found " + found + " of " + EXPECTED_TABLES + " tables): run the "
                        + "migration step — SmControlMigrationMain with the sm_cp_migrator credential "
                        + "— before starting the application");
            }
            int audited;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(AUDIT_READINESS_QUERY)) {
                rs.next();
                audited = rs.getInt(1);
            }
            if (audited != AUDITED_TABLES.size()) {
                throw new IllegalStateException("sm_control is not migrated to the shape this build "
                        + "expects: only " + audited + " of " + AUDITED_TABLES.size() + " state tables carry "
                        + "an enabled change_audit_trigger (ruling OR-19 requires every state change to be "
                        + "audited): run the migration step — SmControlMigrationMain with the sm_cp_migrator "
                        + "credential — before starting the application");
            }
            int immutabilityTriggers;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(IMMUTABILITY_TRIGGERS_QUERY)) {
                rs.next();
                immutabilityTriggers = rs.getInt(1);
            }
            if (immutabilityTriggers != EXPECTED_IMMUTABILITY_TRIGGERS) {
                throw new IllegalStateException("sm_control is not migrated to the shape this build "
                        + "expects: only " + immutabilityTriggers + " of " + EXPECTED_IMMUTABILITY_TRIGGERS
                        + " change_audit immutability triggers are present and enabled (ruling OR-19 requires "
                        + "the audit trail itself to be append-only): run the migration step — "
                        + "SmControlMigrationMain with the sm_cp_migrator credential — before starting the "
                        + "application");
            }
            int truncateRefusedTriggers;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(TRUNCATE_REFUSED_TRIGGERS_QUERY)) {
                rs.next();
                truncateRefusedTriggers = rs.getInt(1);
            }
            if (truncateRefusedTriggers != EXPECTED_TRUNCATE_REFUSED_TRIGGERS) {
                throw new IllegalStateException("sm_control is not migrated to the shape this build "
                        + "expects: only " + truncateRefusedTriggers + " of " + EXPECTED_TRUNCATE_REFUSED_TRIGGERS
                        + " truncate_refused triggers are present and enabled (ruling OR-19 requires TRUNCATE on "
                        + "control-plane state to be refused outright, since it bypasses every row-level audit "
                        + "trigger): run the migration step — SmControlMigrationMain with the sm_cp_migrator "
                        + "credential — before starting the application");
            }
            Boolean securityDefiner;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(AUDIT_FUNCTION_SECURITY_DEFINER_QUERY)) {
                securityDefiner = rs.next() ? rs.getBoolean(1) : null;
            }
            if (!Boolean.TRUE.equals(securityDefiner)) {
                throw new IllegalStateException("sm_control is not migrated to the shape this build "
                        + "expects: sm_control.record_change_audit() is missing or not SECURITY DEFINER "
                        + "(sm_cp_runtime holds no INSERT on change_audit and relies on this to write its "
                        + "audit rows): run the migration step — SmControlMigrationMain with the "
                        + "sm_cp_migrator credential — before starting the application");
            }
            String sessionUser;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(SESSION_USER_QUERY)) {
                sessionUser = rs.next() ? rs.getString(1) : null;
            }
            if (!EXPECTED_RUNTIME_ROLE.equals(sessionUser)) {
                throw new IllegalStateException("sm.control.runtime is configured to connect as '" + sessionUser
                        + "', not '" + EXPECTED_RUNTIME_ROLE + "': ruling OR-25's fail-closed audit block in "
                        + "sm_control.record_change_audit() only refuses an incomplete-context write when "
                        + "session_user = '" + EXPECTED_RUNTIME_ROLE + "' — a runtime connecting under any other "
                        + "role name would fail OPEN instead of closed for every OR-25 guarantee. The runtime "
                        + "login role must be named exactly '" + EXPECTED_RUNTIME_ROLE + "'");
            }
            // Ordered AFTER the session_user check: a wrongly named runtime role gets that more
            // specific message rather than a V012 one that would be equally true but less actionable.
            int refusalColumn;
            try (Connection c = smControlDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(LOGIN_THROTTLE_REFUSAL_COLUMN_QUERY)) {
                rs.next();
                refusalColumn = rs.getInt(1);
            }
            if (refusalColumn != 1) {
                throw new IllegalStateException(V012_SCHEMA_MISSING_MESSAGE);
            }
            verifyOr25FailClosedAuditIsInForce(smControlDataSource);
            verifyV013Governance(smControlDataSource);
            verifyV014Dashboards(smControlDataSource);
            verifyV015HierarchyNames(smControlDataSource);
            verifyV016Verbs(smControlDataSource);
            verifyV017Retirement(smControlDataSource);
            verifyV018RegistrationApi(smControlDataSource);
            verifyV019UsernamePerOrganization(smControlDataSource);
            verifyV020OperatorRename(smControlDataSource);
            verifyV026AgentRegistration(smControlDataSource);
            verifyV027SystemActor(smControlDataSource);
        };
    }

    /** The application pool: {@code sm_cp_runtime}, the role that owns nothing. */
    @Bean(name = "smControlDataSource", destroyMethod = "close")
    HikariDataSource smControlDataSource(SmControlProperties props) {
        SmControlProperties.Credentials c = props.runtime();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(c.url());
        ds.setUsername(c.username());
        ds.setPassword(c.password());
        ds.setPoolName("sm-control");
        // The schema is set on the connection rather than written into every statement: unqualified
        // names in repository SQL then resolve inside sm_control and nowhere else.
        ds.setSchema("sm_control");
        return ds;
    }

    /** Bind-parameter-only template ({@code I5}), gated on readiness by bean-name dependency. */
    @Bean(name = "smControlJdbc")
    @DependsOn("smControlSchemaReadiness")
    NamedParameterJdbcTemplate smControlJdbc(@Qualifier("smControlDataSource") DataSource smControlDataSource) {
        return new NamedParameterJdbcTemplate(smControlDataSource);
    }

    @Bean(name = "smControlTransactionManager")
    @DependsOn("smControlSchemaReadiness")
    DataSourceTransactionManager smControlTransactionManager(
            @Qualifier("smControlDataSource") DataSource smControlDataSource) {
        return new DataSourceTransactionManager(smControlDataSource);
    }

    @Bean(name = "smControlTransactions")
    @DependsOn("smControlSchemaReadiness")
    TransactionTemplate smControlTransactions(
            @Qualifier("smControlTransactionManager") DataSourceTransactionManager smControlTransactionManager) {
        return new TransactionTemplate(smControlTransactionManager);
    }

    @Bean
    PrincipalRepository principalRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcPrincipalRepository(smControlJdbc);
    }

    @Bean
    SessionRepository sessionRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcSessionRepository(smControlJdbc);
    }

    @Bean
    LoginThrottleRepository loginThrottleRepository(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcLoginThrottleRepository(smControlJdbc);
    }

    /** {@code OR-19}'s transaction-local audit-context setter — see {@link JdbcAuditContext}. */
    @Bean
    AuditContext auditContext(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcAuditContext(smControlJdbc);
    }

    @Bean
    AuthorizationGrantRepository authorizationGrantRepository(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcAuthorizationGrantRepository(smControlJdbc);
    }

    @Bean
    RoleBindingRepository roleBindingRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcRoleBindingRepository(smControlJdbc);
    }

    @Bean
    PrincipalOrganizationResolver principalOrganizationResolver(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcPrincipalOrganizationResolver(smControlJdbc);
    }

    @Bean
    ResourceScopeDirectory resourceScopeDirectory(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcResourceScopeDirectory(smControlJdbc);
    }

    @Bean
    HierarchyTreeRepository hierarchyTreeRepository(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcHierarchyTreeRepository(smControlJdbc);
    }
}
