package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.GenerationBinding;
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
 * Plan 010 T6.3 (the T5.2 note): the generation an apply names must still exist when the {@code apply_request} that
 * then protects it is inserted. Retention (T5.2) deletes under the {@code instance_state} row lock; admission checks
 * under the same lock, taken AFTER the {@code apply_scope} lock (the plan's lock order), so the check and the insert
 * cannot straddle a retention delete.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdmissionGenerationBindingIT {

    private static final String DIGEST = "hmacsha256v1:" + "a".repeat(64);

    private static AdmissionService admission;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private UUID tenant;
    private UUID instance;
    private ScopeKey scope;

    private void declared() {
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        UUID placement = InventoryFixtures.placement(tenant, instance, target);
        scope = ApplyEngineHarness.scope(tenant, instance, new PlacementId(placement));
    }

    /** Writes generation {@code g} as the config of record would, and moves the head to {@code head}. */
    private void generation(long g, long head) {
        superuser.update("INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, created_by, created_at) VALUES (:t, :i, :g, 'cache', '2.4', '{}'::jsonb,"
                        + " '{}'::jsonb, '[]'::jsonb, :d, 'edit', :u, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("g", g)
                        .addValue("d", DIGEST + g).addValue("u", UUID.randomUUID()));
        superuser.update("UPDATE sm_data.instance_state SET last_generation = :h WHERE tenant_id = :t"
                        + " AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("h", head));
    }

    private int requests() {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t"
                        + " AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Integer.class);
    }

    @Test
    void aStagedGenerationIsAdmittedAndANeverStagedOneIsRefusedBeforeAnyRowIsWritten() {
        declared();
        generation(1, 1);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(scope, 1), GenerationBinding.MUST_BE_STAGED));
        assertEquals(1, requests());

        // a different placement of the same instance, so the in-flight fence is not what refuses it
        UUID second = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        ScopeKey other = ApplyEngineHarness.scope(tenant, instance, new PlacementId(second));
        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class,
                admission.admit(ApplyEngineHarness.apply(other, 2), GenerationBinding.MUST_BE_STAGED));
        assertEquals(RefusalReason.GENERATION_NOT_STAGED, refused.reason());
        assertEquals(1, requests(), "refused: no apply_request was written for the unstaged generation");
    }

    @Test
    void anotherInstancesGenerationDoesNotCount() {
        declared();
        UUID elsewhere = UUID.randomUUID();
        InventoryFixtures.declare(tenant, elsewhere);
        // generation 1 exists, but for ANOTHER instance of the same tenant
        superuser.update("INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, created_by, created_at) VALUES (:t, :i, 1, 'cache', '2.4', '{}'::jsonb,"
                        + " '{}'::jsonb, '[]'::jsonb, :d, 'edit', :u, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", elsewhere).addValue("d", DIGEST)
                        .addValue("u", UUID.randomUUID()));

        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class,
                admission.admit(ApplyEngineHarness.apply(scope, 1), GenerationBinding.MUST_BE_STAGED));
        assertEquals(RefusalReason.GENERATION_NOT_STAGED, refused.reason(), "I5: the lookup is instance- and tenant-bound");
    }

    @Test
    void theUncheckedBindingIsThePlan005EngineBehaviourAndAdmitsAnUnstagedGeneration() {
        declared();

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(scope, 9), GenerationBinding.UNCHECKED));
        assertInstanceOf(AdmissionOutcome.Refused.class, admission.admit(ApplyEngineHarness.apply(scope, 10),
                GenerationBinding.UNCHECKED), "the control only: the in-flight fence now refuses");
    }

    @Test
    void aStopCarriesNoGenerationAndIsNeverChecked() {
        declared();

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(scope), GenerationBinding.MUST_BE_STAGED));
    }

    /**
     * The generation exists when admission starts, and a retention pass (holding the instance row lock, as T5.2's
     * stage does) deletes it while admission queues behind that lock. Admission must see the deletion: the check has to
     * run UNDER the lock, not before it.
     */
    @Test
    void aGenerationDeletedByRetentionWhileAdmissionQueuedOnTheInstanceLockIsRefused() throws Exception {
        declared();
        generation(1, 1);
        generation(2, 2);                  // head is 2, so generation 1 is deletable (not latest, not journal-named)
        // The scope row exists already, so admission is NOT held up earlier by the apply_scope -> instance_state
        // foreign-key check of a first-ever insert: the only thing left for it to queue on is the lock under test.
        ApplyRows.insertScope(SmDataPostgres.runtimeJdbc(), tenant, instance);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection retention = SmDataPostgres.runtimeDataSource().getConnection()) {
            retention.setAutoCommit(false);
            try (PreparedStatement lock = retention.prepareStatement("SELECT 1 FROM sm_data.instance_state"
                    + " WHERE tenant_id = ? AND instance_id = ? FOR UPDATE")) {
                lock.setObject(1, tenant);
                lock.setObject(2, instance);
                try (ResultSet rs = lock.executeQuery()) {
                    rs.next();
                }
            }
            Future<AdmissionOutcome> pending = pool.submit(() -> admission.admit(
                    ApplyEngineHarness.apply(scope, 1), GenerationBinding.MUST_BE_STAGED));
            awaitSomeoneWaitingOnALock();
            assertFalse(pending.isDone(), "admission must be queued behind the instance row lock");
            try (PreparedStatement delete = retention.prepareStatement("DELETE FROM sm_data.config_generation"
                    + " WHERE tenant_id = ? AND instance_id = ? AND generation = 1")) {
                delete.setObject(1, tenant);
                delete.setObject(2, instance);
                assertEquals(1, delete.executeUpdate());
            }
            retention.commit();

            AdmissionOutcome out = pending.get(15, TimeUnit.SECONDS);
            assertEquals(RefusalReason.GENERATION_NOT_STAGED,
                    assertInstanceOf(AdmissionOutcome.Refused.class, out).reason());
            assertEquals(0, requests(), "nothing names the vanished generation");
        } finally {
            pool.shutdownNow();
        }
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
        throw new AssertionError("admission never queued behind the instance lock");
    }
}
