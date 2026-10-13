package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T2.2 — {@code sm_data} V008: {@code apply_request.state_version}, stamped by trigger (OR-35).
 *
 * <p>Every statement a test makes AS the application runs as {@code sm_dp_runtime}. The superuser appears only to
 * READ the stamp back and, where named, to build a state no runtime path can reach.
 *
 * <p>The two {@code BEFORE UPDATE} triggers on a terminal row (V006's guard and V008's stamp) must be
 * order-independent; {@link #theCompactionFoldOnATerminalRowStillSucceedsAndDoesNotStamp} and
 * {@link #stagedToSupersededStampsAndIsStillPermitted} are the two sanctioned writes V006 allows, and
 * {@link #anyOtherWriteToATerminalRowIsStillRefused} is V006 unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyRequestStateVersionIT {

    private static ApplyRequestRepository requests;
    private static ApplyScopeRepository scopes;
    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        requests = ApplyEngineHarness.requests();
        scopes = ApplyEngineHarness.scopes();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    @Test
    void everyRequestTransitionTakesAHigherStateVersion() {
        Fixture f = new Fixture();
        long born = stamp(f.tenant, f.id);

        assertEquals(1, requests.transition(new TenantId(f.tenant), f.id, RequestState.REQUESTED, RequestState.RUNNING, null));
        long running = stamp(f.tenant, f.id);
        assertEquals(1, requests.transition(new TenantId(f.tenant), f.id, RequestState.RUNNING, RequestState.APPLIED,
                Instant.now()));
        long applied = stamp(f.tenant, f.id);

        assertTrue(born > 0, "an INSERT is stamped, though the repository never names the column");
        assertTrue(running > born, "REQUESTED -> RUNNING takes a higher stamp: " + born + " -> " + running);
        assertTrue(applied > running, "RUNNING -> APPLIED takes a higher stamp: " + running + " -> " + applied);
        assertTrue(born > instanceStamp(f.tenant, f.instance),
                "the request's stamp comes from the SAME sequence as the instance's, and was drawn later");
    }

    @Test
    void theRowReadBackCarriesItsStamp() {
        Fixture f = new Fixture();
        assertEquals(stamp(f.tenant, f.id), requests.find(new TenantId(f.tenant), f.id).orElseThrow().stateVersion());
    }

    @Test
    void theRuntimeRoleStillCannotWriteStateVersion() {
        Fixture f = new Fixture();
        assertEquals(List.of(), columnsWithPrivilege("INSERT"), "state_version is in no runtime INSERT grant");
        assertEquals(List.of(), columnsWithPrivilege("UPDATE"), "state_version is in no runtime UPDATE grant");
        long before = stamp(f.tenant, f.id);
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.apply_request SET state_version = 1 WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", f.tenant).addValue("a", f.id.value())));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, state_version)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 9, 'manual', 'operator-1', now(), 'REQUESTED', 999999)",
                new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", f.tenant)
                        .addValue("i", f.instance).addValue("p", UUID.randomUUID())));
        assertEquals(before, stamp(f.tenant, f.id));
    }

    @Test
    void theCompactionFoldOnATerminalRowStillSucceedsAndDoesNotStamp() {
        Fixture f = new Fixture();
        requests.transition(new TenantId(f.tenant), f.id, RequestState.REQUESTED, RequestState.RUNNING, null);
        requests.transition(new TenantId(f.tenant), f.id, RequestState.RUNNING, RequestState.APPLIED, Instant.now());
        long terminal = stamp(f.tenant, f.id);
        long sequenceBefore = lastValue();

        assertEquals(1, requests.foldAttemptSummary(new TenantId(f.tenant), f.id, "{\"attempts\":3}"),
                "V006's sanctioned write 2 must still go through with the stamp trigger present");

        assertEquals(terminal, stamp(f.tenant, f.id), "a compaction is not an observable change: no new stamp");
        assertEquals(sequenceBefore, lastValue(), "…and it draws no value from the sequence at all");
        assertEquals("{\"attempts\": 3}", superuser.queryForObject(
                "SELECT attempt_summary::text FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", f.tenant).addValue("a", f.id.value()), String.class));
    }

    @Test
    void stagedToSupersededStampsAndIsStillPermitted() {
        Fixture f = new Fixture();
        TenantId t = new TenantId(f.tenant);
        requests.transition(t, f.id, RequestState.REQUESTED, RequestState.RUNNING, null);
        requests.transition(t, f.id, RequestState.RUNNING, RequestState.STAGED, Instant.now());
        long staged = stamp(f.tenant, f.id);

        assertEquals(1, requests.transition(t, f.id, RequestState.STAGED, RequestState.SUPERSEDED, Instant.now()),
                "V006's sanctioned transition 1");

        assertTrue(stamp(f.tenant, f.id) > staged, "supersession IS an observable change, so it stamps");
    }

    @Test
    void anyOtherWriteToATerminalRowIsStillRefused() {
        Fixture f = new Fixture();
        TenantId t = new TenantId(f.tenant);
        requests.transition(t, f.id, RequestState.REQUESTED, RequestState.RUNNING, null);
        requests.transition(t, f.id, RequestState.RUNNING, RequestState.APPLIED, Instant.now());
        long terminal = stamp(f.tenant, f.id);

        Refusals.assertRefused(Refusals.RAISE_EXCEPTION, null, () -> runtime.update(
                "UPDATE sm_data.apply_request SET terminal_at = now() WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", f.tenant).addValue("a", f.id.value())),
                "a terminal row's terminal_at is not the fold");
        Refusals.assertRefused(Refusals.RAISE_EXCEPTION, null, () -> runtime.update(
                "UPDATE sm_data.apply_request SET state = 'RUNNING', terminal_at = NULL"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", f.tenant).addValue("a", f.id.value())),
                "a terminal row may not regress");
        assertEquals(terminal, stamp(f.tenant, f.id), "the refused writes rolled their stamps back with them");
    }

    /**
     * The stamp trigger is the SECOND enforcer after admission and the foreign key: it raises rather than stamp
     * for an instance the DP never declared. Reaching it takes a state no runtime path can build, so a scope row for
     * an undeclared instance is planted with the foreign key's triggers disabled for one transaction (superuser).
     */
    @Test
    void theStampTriggerRefusesARequestForAnUndeclaredInstanceByItself() throws SQLException {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        try (Connection c = SmDataPostgres.superuser().getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("ALTER TABLE sm_data.apply_scope DISABLE TRIGGER ALL");
            s.execute("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES ('" + tenant + "', '"
                    + instance + "')");
            s.execute("ALTER TABLE sm_data.apply_scope ENABLE TRIGGER ALL");
            c.commit();
        }
        ScopeKey scope = ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);

        PgConstraintViolation e = assertThrows(PgConstraintViolation.class,
                () -> requests.insert(ApplyEngineHarness.requestedApply(scope, 1)));

        assertEquals("23503", e.sqlState());
        assertEquals("apply_request_has_instance", e.constraint());
    }

    @Test
    void v008RefusesToRunOverANonEmptyJournal() throws SQLException {
        PostgreSQLContainer<?> c = SmDataPostgres.container();
        String database = "smdb_v008_nonempty";
        try (Connection admin = SmDataPostgres.superuser().getConnection(); Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database);
            s.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database;
        DataSource su = SmDataPostgres.dataSource(url, c.getUsername(), c.getPassword());
        try (Connection conn = su.getConnection(); Statement s = conn.createStatement()) {
            s.execute("CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner");
            s.execute("GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime");
        }
        DataSource migrator = SmDataPostgres.dataSource(url, "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);

        flyway(migrator, "7").migrate();                                    // a database as T2.1 left it …
        NamedParameterJdbcTemplate owner = new NamedParameterJdbcTemplate(su);
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        owner.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));   // … one legacy journal row
        ApplyRows.insertRequest(owner, tenant, instance, ApplyRows.INSTANCE_WIDE, "REQUESTED");

        Throwable refused = assertThrows(Throwable.class, () -> flyway(migrator, null).migrate());

        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null && t.getCause() != t; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains("V008 refuses to run over a non-empty sm_data.apply_request"),
                "the refusal must be V008's own sentence naming the recreate procedure. Was: " + chain);
        assertFalse(chain.toString().contains(tenant.toString()), "never a row's value (I6)");
        assertEquals(0, owner.queryForObject("SELECT count(*)::int FROM information_schema.columns"
                + " WHERE table_schema = 'sm_data' AND table_name = 'apply_request' AND column_name = 'state_version'",
                new MapSqlParameterSource(), Integer.class), "the migration rolled back: no column was added");
        assertEquals(1, owner.queryForObject("SELECT count(*)::int FROM sm_data.apply_request",
                new MapSqlParameterSource(), Integer.class), "…and nothing was deleted either");
    }

    private static Flyway flyway(DataSource migrator, String target) {
        var cfg = Flyway.configure().dataSource(migrator).schemas(SmDataMigrator.SCHEMA)
                .defaultSchema(SmDataMigrator.SCHEMA).initSql(SmDataMigrator.INIT_SQL)
                .locations(SmDataMigrator.LOCATION).createSchemas(false);
        return (target == null ? cfg : cfg.target(target)).load();
    }

    /** A declared instance with one REQUESTED apply on it, written the way admission writes it. */
    private static final class Fixture {
        final UUID tenant = SmDataPostgres.freshTenant();
        final UUID instance = UUID.randomUUID();
        final ApplyId id;

        Fixture() {
            InventoryFixtures.declare(tenant, instance);
            ScopeKey scope = ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);
            AdmissionOutcome out = ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scope, 1));
            this.id = assertInstanceOf(AdmissionOutcome.Admitted.class, out).applyId();
        }
    }

    private static long stamp(UUID tenant, ApplyId id) {
        return superuser.queryForObject("SELECT state_version FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", tenant).addValue("a", id.value()), Long.class);
    }

    private static long instanceStamp(UUID tenant, UUID instance) {
        return superuser.queryForObject("SELECT state_version FROM sm_data.instance_state"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Long.class);
    }

    private static long lastValue() {
        return superuser.queryForObject("SELECT last_value FROM sm_data.state_version_seq",
                new MapSqlParameterSource(), Long.class);
    }

    private static List<String> columnsWithPrivilege(String privilege) {
        return superuser.queryForList("SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'apply_request'"
                        + " AND column_name = 'state_version' AND grantee = 'sm_dp_runtime' AND privilege_type = :p",
                new MapSqlParameterSource("p", privilege), String.class);
    }
}
