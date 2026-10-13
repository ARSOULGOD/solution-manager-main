package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.config.ControlPlaneConfiguration;
import com.solace.sm.cp.events.StubMeshPublisherConfiguration;
import com.solace.sm.cp.identity.AuditOperations;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code V014} (plan 011 P11-2.3): {@code sm_control.dashboard}, its {@code OR-25} audit contract, and the privilege
 * the runtime role now holds on it.
 *
 * <p><b>Every write under test runs as {@code sm_cp_runtime}</b> over the real two-phase bootstrap
 * ({@link SmControlPostgres}), because a write by the migrator or the owner is exempt from OR-25 and would prove
 * nothing about the role the application actually connects as. Fixture rows (the hierarchy, a principal) are seeded
 * as the migrator, which is exempt, so each test's own write is the only thing under test.
 */
class DashboardSchemaIT {

    private static final String CACHE_KEY = "dashboard-schema-it";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

    // ---------------------------------------------------------------- the shape

    @Test
    void oneDashboardPerInstanceAndItsIdIsTheInstances() {
        Fixture f = fixture();

        // The id is derived from the instance, never chosen.
        Refusal wrongId = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, "db_" + UUID.randomUUID(), "instance", f.instance, "{}")));
        assertThat(wrongId.sqlState).isEqualTo("23514");
        assertThat(wrongId.message).contains("dashboard_id_is_instance");

        // A malformed id is refused too (dashboard_id_shape and dashboard_id_is_instance both reject it).
        Refusal malformedId = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, "dashboard-one", "instance", f.instance, "{}")));
        assertThat(malformedId.sqlState).isEqualTo("23514");

        asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}"));
        Refusal second = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}")));
        assertThat(second.sqlState).isEqualTo("23505");
    }

    @Test
    void onlyInstanceScopeIsLegalInV1() {
        Fixture f = fixture();

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "environment", f.instance, "{}")));

        assertThat(refusal.sqlState).isEqualTo("23514");
        assertThat(refusal.message).contains("dashboard_scope_v1");
    }

    @Test
    void aDashboardForAnUnknownInstanceIsRefused() {
        Fixture f = fixture();
        UUID unknown = UUID.randomUUID();

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, "db_" + unknown, "instance", unknown, "{}")));

        assertThat(refusal.sqlState).isEqualTo("23503");
        assertThat(refusal.message).contains("dashboard_instance_same_org");
    }

    @Test
    void aDashboardNamingAnotherOrganizationsInstanceIsRefused() {
        Fixture mine = fixture();
        Fixture theirs = fixture();

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, mine.principal,
                () -> insertDashboard(mine.organization, "db_" + theirs.instance, "instance", theirs.instance, "{}")));

        assertThat(refusal.sqlState).isEqualTo("23503");
        assertThat(refusal.message).contains("dashboard_instance_same_org");
    }

    @Test
    void aDocumentOver64KiBIsRefused() {
        Fixture f = fixture();
        String atTheLimit = "{\"pad\":\"" + "x".repeat(65_536 - "{\"pad\":\"\"}".length() - 1) + "\"}";
        String overTheLimit = "{\"pad\":\"" + "x".repeat(65_536) + "\"}";

        asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, atTheLimit));
        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> runtime.getJdbcTemplate().update(
                        "UPDATE sm_control.dashboard SET document = ?::jsonb, revision = revision + 1,"
                                + " updated_by = ?, updated_at = now() WHERE dashboard_id = ?",
                        overTheLimit, f.principal, f.dashboardId())));

        assertThat(refusal.sqlState).isEqualTo("23514");
        assertThat(refusal.message).contains("dashboard_size");
    }

    @Test
    void anInstanceWithADashboardCannotBeDeletedUntilTheDashboardIs() {
        Fixture f = fixture();
        asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}"));

        Refusal blocked = refusalOf(() -> migrator.getJdbcTemplate().update(
                "DELETE FROM sm_control.instance WHERE instance_id = ?", f.instance));
        assertThat(blocked.sqlState).as("ON DELETE RESTRICT: the operator runbook's orphan delete must see it")
                .isEqualTo("23503");
        assertThat(blocked.message).contains("dashboard_instance_same_org");

        // The runtime role cannot remove it; only the owner side can, and then the instance can go.
        Refusal runtimeDelete = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_RESET, f.principal,
                () -> runtime.getJdbcTemplate().update(
                        "DELETE FROM sm_control.dashboard WHERE dashboard_id = ?", f.dashboardId())));
        assertThat(runtimeDelete.sqlState).isEqualTo("42501");

        migrator.getJdbcTemplate().update("DELETE FROM sm_control.dashboard WHERE dashboard_id = ?", f.dashboardId());
        assertThat(migrator.getJdbcTemplate().update("DELETE FROM sm_control.instance WHERE instance_id = ?",
                f.instance)).isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCanRewriteTheDocumentButNeverTheIdentity() {
        Fixture f = fixture();
        Fixture other = fixture();
        asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}"));

        for (String column : List.of("organization_id", "dashboard_id", "scope_kind", "instance_id")) {
            Object value = switch (column) {
                case "organization_id" -> other.organization;
                case "dashboard_id" -> other.dashboardId();
                case "scope_kind" -> "instance";
                default -> other.instance;
            };
            Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                    () -> runtime.getJdbcTemplate().update(
                            "UPDATE sm_control.dashboard SET " + column + " = ? WHERE dashboard_id = ?",
                            value, f.dashboardId())));
            assertThat(refusal.sqlState).as(column).isEqualTo("42501");
        }
    }

    // ---------------------------------------------------------------- the Operator role (OR-80)

    @Test
    void theOperatorGainedExactlyTheTwoMetricsVerbsAndNothingElse() {
        // V014's own claim, read at V014: V016 (OR-121) later grants the Operator three more verbs, instance.stop among them.
        List<String> operator = SmControlPostgres.jdbcAtVersion("dashboard-schema-it-v014", "014").queryForList(
                "SELECT permission_id FROM sm_control.role_permission"
                        + " WHERE role_id = '00000000-0000-4000-8000-000000000003' ORDER BY permission_id",
                Map.of(), String.class);

        assertThat(operator).hasSize(21).contains("metrics.export", "metrics.forceRaw");
        assertThat(operator).doesNotContain("audit.view", "mesh.stream.grant", "instance.stop", "user.edit");
    }

    // ---------------------------------------------------------------- audit coverage

    @Test
    void everyDashboardWriteIsAuditedWithADigestNotTheDocument() {
        Fixture f = fixture();
        UUID insertCorrelation = UUID.randomUUID();
        UUID updateCorrelation = UUID.randomUUID();
        String marker = "DOC-MARKER-" + UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal, insertCorrelation);
            insertDashboard(f.organization, f.dashboardId(), "instance", f.instance,
                    "{\"widgets\":[{\"title\":\"" + marker + "\"}]}");
        });
        String firstSha = documentSha256(f.dashboardId());
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.DASHBOARD_LAYOUT_SET, f.principal, updateCorrelation);
            runtime.getJdbcTemplate().update("UPDATE sm_control.dashboard SET document = ?::jsonb,"
                    + " revision = revision + 1, updated_by = ?, updated_at = now() WHERE dashboard_id = ?",
                    "{\"widgets\":[],\"layout\":[],\"note\":\"" + marker + "\"}", f.principal, f.dashboardId());
        });

        String secondSha = documentSha256(f.dashboardId());
        assertThat(secondSha).isNotEqualTo(firstSha);

        Map<String, Object> inserted = auditRow("dashboard", insertCorrelation);
        assertThat(inserted.get("row_key").toString()).contains(f.dashboardId());
        assertThat(inserted.get("audit_operation")).isEqualTo(AuditOperations.DASHBOARD_WIDGET_SAVED);
        assertThat(inserted.get("new_values").toString()).as("the document is not copied into the audit row")
                .doesNotContain(marker).doesNotContain("\"document\"")
                .contains("document_sha256").contains(firstSha);

        Map<String, Object> updated = auditRow("dashboard", updateCorrelation);
        assertThat(updated.get("audit_operation")).isEqualTo(AuditOperations.DASHBOARD_LAYOUT_SET);
        assertThat(updated.get("old_values").toString()).doesNotContain(marker).contains(firstSha);
        assertThat(updated.get("new_values").toString()).doesNotContain(marker).contains(secondSha);
        assertThat(updated.get("old_values").toString()).isNotEqualTo(updated.get("new_values").toString());
    }

    @Test
    void theFourDashboardOperationsAreAcceptedAndAnUnknownOneIsRefused() {
        Fixture f = fixture();
        asRuntime(AuditOperations.DASHBOARD_WIDGET_SAVED, f.principal,
                () -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}"));

        for (String operation : List.of(AuditOperations.DASHBOARD_WIDGET_SAVED, AuditOperations.DASHBOARD_LAYOUT_SET,
                AuditOperations.DASHBOARD_WIDGET_HIDDEN, AuditOperations.DASHBOARD_RESET)) {
            UUID correlation = UUID.randomUUID();
            runtimeTx.executeWithoutResult(status -> {
                setContext(operation, f.principal, correlation);
                bump(f);
            });
            assertThat(auditRow("dashboard", correlation).get("audit_operation")).isEqualTo(operation);
        }

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext("DASHBOARD_EXPLODED", f.principal, UUID.randomUUID());
            bump(f);
        }));
        assertThat(refusal.sqlState).isEqualTo("OR025");
        assertThat(refusal.message).contains("no known audit_operation");

        // The CHECK on change_audit was widened in step, so the vocabulary is closed on both sides.
        Refusal checkRefusal = refusalOf(() -> migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.change_audit (recorded_at, txid, table_name, operation, row_key,"
                        + " audit_operation, db_role) VALUES (now(), 1, 'x', 'INSERT', '{}'::jsonb,"
                        + " 'DASHBOARD_EXPLODED', 'x')"));
        assertThat(checkRefusal.sqlState).isEqualTo("23514");
        assertThat(checkRefusal.message).contains("change_audit_audit_operation");
    }

    @Test
    void aRuntimeDashboardWriteWithoutAuditContextIsRefusedOR025() {
        Fixture f = fixture();

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(
                status -> insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}")));
        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
        assertThat(refusal.message).contains("no known audit_operation");

        // An operation of another table's vocabulary is no context for this one either... but it is a KNOWN
        // operation; what binds a dashboard write to the dashboard operations is the application, and what binds
        // every write to SOME operation, an actor and an address is the trigger.
        Refusal noActor = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.DASHBOARD_WIDGET_SAVED, null, UUID.randomUUID());
            insertDashboard(f.organization, f.dashboardId(), "instance", f.instance, "{}");
        }));
        assertThat(noActor.sqlState).isEqualTo("OR025");
        assertThat(noActor.message).contains("requires an actor principal");
    }

    // ---------------------------------------------------------------- readiness

    @Test
    void readinessRefusesASchemaWithoutThisMigration() {
        String cacheKey = "dashboard-readiness-v013-it";
        SmControlPostgres.jdbcAtVersion(cacheKey, "013");

        runner(cacheKey).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).as("a V013 sm_control must not be served by a build that requires V014").isNotNull();
            assertThat(causeChain(failure)).contains("V014").contains("SmControlMigrationMain");
        });
    }

    @Test
    void readinessRefusesAV014SchemaWhoseDashboardLostItsAuditOrTruncateTriggerOrItsAuditFunction() {
        String cacheKey = "dashboard-readiness-triggers-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);

        runner(cacheKey).run(context -> assertThat(context.getStartupFailure())
                .as("a fully migrated sm_control starts (V014's readiness stage is the one probed below)").isNull());

        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.dashboard DISABLE TRIGGER change_audit_trigger");
        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).as("an unaudited dashboard table must not be served").isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("audit");
        });
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.dashboard ENABLE TRIGGER change_audit_trigger");

        owner.getJdbcTemplate().execute("DROP TRIGGER truncate_refused ON sm_control.dashboard");
        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).as("a truncatable dashboard table must not be served")
                    .isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("truncate_refused");
        });
        owner.getJdbcTemplate().execute("CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.dashboard"
                + " FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation()");

        // An owner who put V013's body back: still fail-closed (every V013 probe passes), but it refuses every
        // dashboard operation. Presence of the table is not proof the function that audits it is in force.
        owner.getJdbcTemplate().execute(functionBodyOf("V013__dp_remote_governance.sql"));
        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).as("record_change_audit() reverted to V013 must not be served")
                    .isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("V014");
        });
    }

    @Test
    void readinessRefusesAnOperatorThatLostTheTwoMetricsVerbs() {
        String cacheKey = "dashboard-readiness-operator-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);
        owner.getJdbcTemplate().update("DELETE FROM sm_control.role_permission"
                + " WHERE role_id = '00000000-0000-4000-8000-000000000003' AND permission_id = 'metrics.forceRaw'");

        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("V014").contains("metrics.forceRaw");
        });
    }

    // ---------------------------------------------------------------- harness

    private ApplicationContextRunner runner(String cacheKey) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + SmControlPostgres.runtimeUrlAtVersion(cacheKey),
                        "sm.control.runtime.username=" + SmControlPostgres.RUNTIME_USERNAME,
                        "sm.control.runtime.password=" + SmControlPostgres.runtimePasswordAtVersion(cacheKey));
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        return chain.toString();
    }

    private static String functionBodyOf(String migration) {
        try (java.io.InputStream in = DashboardSchemaIT.class.getClassLoader()
                .getResourceAsStream("db/migration/sm_control/" + migration)) {
            String sql = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int start = sql.indexOf("CREATE OR REPLACE FUNCTION sm_control.record_change_audit()");
            int end = sql.indexOf("\n$$;", start) + "\n$$;".length();
            return sql.substring(start, end);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(UUID organization, UUID instance, UUID principal) {
        String dashboardId() {
            return "db_" + instance;
        }
    }

    private record Refusal(String sqlState, String message) {
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

    /** Runs {@code work} in one transaction as {@code sm_cp_runtime} with complete audit context. */
    private void asRuntime(String operation, UUID actor, Runnable work) {
        runtimeTx.executeWithoutResult(status -> {
            setContext(operation, actor, UUID.randomUUID());
            work.run();
        });
    }

    /** The same transaction-local settings {@code JdbcAuditContext} writes, spelled out. */
    private void setContext(String operation, UUID actor, UUID correlation) {
        runtime.getJdbcTemplate().queryForList(
                "SELECT set_config('sm.audit.actor', ?, true), set_config('sm.audit.operation', ?, true),"
                        + " set_config('sm.audit.correlation_id', ?, true),"
                        + " set_config('sm.audit.client_address', ?, true)",
                actor == null ? "" : actor.toString(), operation, correlation.toString(), "203.0.113.50");
    }

    private Map<String, Object> auditRow(String table, UUID correlation) {
        List<Map<String, Object>> rows = migrator.queryForList(
                "SELECT row_key::text AS row_key, old_values::text AS old_values, new_values::text AS new_values,"
                        + " audit_operation, actor_principal_id FROM sm_control.change_audit"
                        + " WHERE table_name = :t AND correlation_id = :c", Map.of("t", table, "c", correlation));
        assertThat(rows).as("exactly one audit row for " + table).hasSize(1);
        return rows.get(0);
    }

    /** The digest the database computes over the stored document as it stands now. */
    private String documentSha256(String dashboardId) {
        return migrator.getJdbcTemplate().queryForObject(
                "SELECT encode(sha256(convert_to(document::text, 'UTF8')), 'hex') FROM sm_control.dashboard"
                        + " WHERE dashboard_id = ?", String.class, dashboardId);
    }

    private void bump(Fixture f) {
        runtime.getJdbcTemplate().update("UPDATE sm_control.dashboard SET revision = revision + 1,"
                + " updated_by = ?, updated_at = now() WHERE dashboard_id = ?", f.principal, f.dashboardId());
    }

    private Fixture fixture() {
        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)",
                organization);
        migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                organization, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id,"
                + " organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", organization, unit, environment);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                + " environment_id, solution_id) VALUES (?, ?, ?, ?)", organization, unit, environment, solution);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.instance (organization_id, organization_unit_id,"
                + " environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", organization, unit,
                environment, solution, instance);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "dashboard-" + principal);
        return new Fixture(organization, instance, principal);
    }

    private void insertDashboard(UUID organization, String dashboardId, String scopeKind, UUID instance,
            String document) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.dashboard (organization_id, dashboard_id,"
                + " scope_kind, instance_id, document, revision, updated_by, updated_at)"
                + " VALUES (?, ?, ?, ?, ?::jsonb, 1, (SELECT id FROM sm_control.principal WHERE organization_id = ?"
                + " LIMIT 1), now())", organization, dashboardId, scopeKind, instance, document, organization);
    }
}
