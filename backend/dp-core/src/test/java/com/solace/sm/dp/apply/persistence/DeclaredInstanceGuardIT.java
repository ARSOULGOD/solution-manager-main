package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T2.2 — admission refuses an instance the data plane has not declared (OR-27's declare is the only door
 * to the journal), and the database refuses the same thing independently.
 *
 * <p>Three enforcers, each proven alone: admission's own check (the readable one — a {@code Refused} value, before
 * any row is written), the {@code scope_has_instance} foreign key on {@code apply_scope} (M6), and the stamp
 * trigger's own refusal on {@code apply_request} (see {@code ApplyRequestStateVersionIT}).
 */
@Testcontainers(disabledWithoutDocker = true)
class DeclaredInstanceGuardIT {

    private static AdmissionService admission;
    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    @Test
    void admissionRefusesAnInstanceThatWasNeverDeclared() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ScopeKey scope = ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);

        AdmissionOutcome out = admission.admit(ApplyEngineHarness.apply(scope, 1));

        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class, out);
        assertEquals(RefusalReason.INSTANCE_NOT_DECLARED, refused.reason());
        assertEquals(0, rows("apply_scope", tenant, instance), "refused BEFORE any row — no scope row was written");
        assertEquals(0, rows("apply_request", tenant, instance));
    }

    @Test
    void everyOperationIsRefusedForAnUndeclaredInstance() {
        UUID tenant = SmDataPostgres.freshTenant();
        ScopeKey scope = ApplyEngineHarness.scope(tenant, UUID.randomUUID(), PlacementId.INSTANCE_WIDE);
        for (var cmd : java.util.List.of(ApplyEngineHarness.apply(scope, 1), ApplyEngineHarness.restart(scope),
                ApplyEngineHarness.stop(scope))) {
            AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class,
                    admission.admit(cmd), cmd.operation().toString());
            assertEquals(RefusalReason.INSTANCE_NOT_DECLARED, refused.reason(), cmd.operation().toString());
        }
    }

    @Test
    void admissionRefusesAPendingOrAbandonedInstance() {
        UUID tenant = SmDataPostgres.freshTenant();
        for (String lifecycle : new String[] {"pending", "abandoned"}) {
            UUID instance = UUID.randomUUID();
            InventoryFixtures.declare(tenant, instance, lifecycle);
            AdmissionOutcome out = admission.admit(ApplyEngineHarness.apply(
                    ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE), 1));
            assertEquals(RefusalReason.INSTANCE_NOT_DECLARED,
                    assertInstanceOf(AdmissionOutcome.Refused.class, out, lifecycle).reason(),
                    "only lifecycle = 'active' is admissible; " + lifecycle + " is not");
            assertEquals(0, rows("apply_request", tenant, instance), lifecycle);
        }
    }

    @Test
    void anActiveInstanceIsAdmitted() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        UUID placement = InventoryFixtures.placement(tenant, instance, target);

        assertInstanceOf(AdmissionOutcome.Admitted.class, admission.admit(ApplyEngineHarness.apply(
                ApplyEngineHarness.scope(tenant, instance, new PlacementId(placement)), 1)),
                "the control: the same command over a declared, active instance is admitted");
    }

    @Test
    void anotherTenantsDeclarationDoesNotDeclareMyInstance() {
        UUID mine = SmDataPostgres.freshTenant();
        UUID theirs = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(theirs, instance);                       // same instance id, ANOTHER tenant

        AdmissionOutcome out = admission.admit(ApplyEngineHarness.apply(
                ApplyEngineHarness.scope(mine, instance, PlacementId.INSTANCE_WIDE), 1));

        assertEquals(RefusalReason.INSTANCE_NOT_DECLARED,
                assertInstanceOf(AdmissionOutcome.Refused.class, out).reason(),
                "I5: the lifecycle read is tenant-bound");
    }

    @Test
    void aScopeRowForAnUndeclaredInstanceIsRefusedByTheDatabaseToo() {
        UUID tenant = SmDataPostgres.freshTenant();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "scope_has_instance",
                () -> runtime.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                        new MapSqlParameterSource().addValue("t", tenant).addValue("i", UUID.randomUUID())));
    }

    /**
     * The check runs AFTER the scope lock as well as before it: an instance that was active when admission began
     * and abandoned while it queued behind another writer of the scope must still be refused. The first read is
     * unlocked (and happens before any row is written); only the read under the lock is authoritative.
     */
    @Test
    void anInstanceAbandonedWhileAdmissionWaitedForTheScopeLockIsRefused() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        ApplyRows.insertScope(runtime, tenant, instance);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = SmDataPostgres.runtimeDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement("SELECT 1 FROM sm_data.apply_scope"
                    + " WHERE tenant_id = ? AND instance_id = ? FOR UPDATE")) {
                ps.setObject(1, tenant);
                ps.setObject(2, instance);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            Future<AdmissionOutcome> pending = pool.submit(() -> admission.admit(ApplyEngineHarness.apply(
                    ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE), 1)));
            awaitSomeoneWaitingOnALock();
            assertFalse(pending.isDone(), "admission must be queued behind the scope lock");
            // The lifecycle trigger (V007) now makes active -> abandoned unreachable through any lawful writer, which
            // is the point of it; this test pins that admission's re-check under the lock does not rely on that, so
            // it bypasses the trigger for this one transaction (session_replication_role needs the superuser).
            try (Connection owner = SmDataPostgres.superuserJdbc().getJdbcTemplate().getDataSource().getConnection();
                    java.sql.Statement st = owner.createStatement()) {
                owner.setAutoCommit(false);
                st.execute("SET LOCAL session_replication_role = replica");
                st.executeUpdate("UPDATE sm_data.instance_state SET lifecycle = 'abandoned' WHERE tenant_id = '"
                        + tenant + "' AND instance_id = '" + instance + "'");
                owner.commit();
            }
            holder.commit();

            AdmissionOutcome out = pending.get(15, TimeUnit.SECONDS);
            assertEquals(RefusalReason.INSTANCE_NOT_DECLARED,
                    assertInstanceOf(AdmissionOutcome.Refused.class, out).reason());
            assertEquals(0, rows("apply_request", tenant, instance));
        } finally {
            pool.shutdownNow();
        }
    }

    private static int rows(String table, UUID tenant, UUID instance) {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_data." + table
                        + " WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Integer.class);
    }

    private static void awaitSomeoneWaitingOnALock() throws InterruptedException, SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = superuser.queryForObject("SELECT count(*)::int FROM pg_stat_activity"
                    + " WHERE usename = 'sm_dp_runtime' AND wait_event_type = 'Lock'", new MapSqlParameterSource(),
                    Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("admission never queued behind the scope lock");
    }
}
