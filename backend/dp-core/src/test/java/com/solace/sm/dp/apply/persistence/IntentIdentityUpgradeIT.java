package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code sm_data} V021 across an upgrade (plan 008 S2 Step 3, {@code BE-D44}): a database migrated to V020 holds
 * journal rows with no placement; V021 gives them one, refuses the one case that cannot be back-filled, and leaves a
 * placement-scoped request resumable onto its own rows.
 *
 * <p><b>Each test builds its own database</b> ({@code MetricsDb.fresh(.., NOTHING)}: Phase 0's schema half only), stops
 * Flyway at V020 with {@code target("20")} — {@code ApplyRequestStateVersionIT} did the same at V007 for V008 — seeds
 * rows as the database owner exactly as the V020 engine wrote them, and only then lets Flyway run V021. The shared
 * database every other IT uses is already past V021 and cannot show an upgrade.
 *
 * <p>MF-1 (the owner's {@code OR-118} database) is not measured here: that database is not reachable from a test lane.
 * The refusal below is what protects it, and the runbook's drain query is the operator's own check.
 */
@Testcontainers(disabledWithoutDocker = true)
class IntentIdentityUpgradeIT {

    private static final UUID NIL = ApplyRows.INSTANCE_WIDE;
    private static final String HASH = "sha256:1111111111111111111111111111111111111111";

    private static Flyway flyway(DataSource migrator, String target) {
        var cfg = Flyway.configure().dataSource(migrator).schemas(SmDataMigrator.SCHEMA)
                .defaultSchema(SmDataMigrator.SCHEMA).initSql(SmDataMigrator.INIT_SQL)
                .locations(SmDataMigrator.LOCATION).createSchemas(false);
        return (target == null ? cfg : cfg.target(target)).load();
    }

    /** A database as the V020 release left it: migrated to V020, no V021. */
    private static final class AtV020 {
        final MetricsDb db;
        final DataSource migrator;
        final NamedParameterJdbcTemplate owner;
        final UUID tenant = UUID.randomUUID();
        final UUID instance = UUID.randomUUID();
        final UUID p1 = UUID.randomUUID();
        final UUID p2 = UUID.randomUUID();

        AtV020(String name) {
            SmDataPostgres.bootstrap();
            db = MetricsDb.fresh("v021_" + name, MetricsDb.Migrated.NOTHING);
            migrator = SmDataPostgres.dataSource(db.url(), "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);
            flyway(migrator, "20").migrate();
            owner = new NamedParameterJdbcTemplate(db.superuser());
            InventoryFixtures.declare(owner, tenant, instance, "active");
            owner.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                    ApplyRows.scope(tenant, instance));
        }

        UUID request(UUID placement, String operation, String state) {
            return ApplyRows.insertRequest(owner, tenant, instance, placement, operation, state);
        }

        /** An intent as V020 wrote it: no placement column. */
        UUID intent(UUID apply, String step, String replica, String key, String hash) {
            UUID id = UUID.randomUUID();
            owner.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id, replica_ref, step,"
                            + " idempotency_key, config_hash, created_at)"
                            + " VALUES (:id, :t, :a, :r, :s, :k, :h, :now)",
                    new MapSqlParameterSource().addValue("id", id).addValue("t", tenant).addValue("a", apply)
                            .addValue("r", replica).addValue("s", step).addValue("k", key).addValue("h", hash)
                            .addValue("now", OffsetDateTime.now()));
            return id;
        }

        void land(UUID apply, String state) {
            owner.update("UPDATE sm_data.apply_request SET state = :s, terminal_at = :now"
                            + " WHERE tenant_id = :t AND apply_id = :a",
                    new MapSqlParameterSource().addValue("s", state).addValue("now", OffsetDateTime.now())
                            .addValue("t", tenant).addValue("a", apply));
        }

        void upgrade() {
            flyway(migrator, null).migrate();
        }

        boolean hasPlacementColumn() {
            return owner.queryForObject("SELECT count(*)::int FROM information_schema.columns"
                    + " WHERE table_schema = 'sm_data' AND table_name = 'apply_step_intent'"
                    + " AND column_name = 'placement_id'", Map.of(), Integer.class) == 1;
        }

        UUID placementOf(UUID intent) {
            return owner.queryForObject("SELECT placement_id FROM sm_data.apply_step_intent WHERE intent_id = :i",
                    Map.of("i", intent), UUID.class);
        }

        // Every column V020 wrote. The upgrade runs to the latest migration, and V024 (plan 008 S4) adds the generated
        // placement_ref, which is not a column a request was written with, so it is not part of "no row changed".
        String requestFingerprint() {
            return owner.queryForObject("SELECT md5(string_agg((to_jsonb(r) - 'placement_ref')::text, '|' ORDER BY apply_id))"
                    + " FROM sm_data.apply_request r", Map.of(), String.class);
        }
    }

    // ---- the back-fill -----------------------------------------------------------------------------------

    /**
     * A placement-scoped request in flight across the upgrade: its intents gain the request's placement, keep their
     * ids, keys and hashes, and the placement-scoped key format is untouched — which is what makes {@code compare()}
     * pass when the engine resumes (the engine-level half is the last two tests of this class).
     */
    @Test
    void aPlacementScopedRequestsIntentsGainItsOwnPlacementAndNothingElseChanges() {
        AtV020 v = new AtV020("backfill");
        UUID apply = v.request(v.p1, "apply", "RUNNING");
        String preflightKey = apply + ":PREFLIGHT:@placement";
        String materializeKey = apply + ":MATERIALIZE:sm-cache";
        UUID preflight = v.intent(apply, "PREFLIGHT", "@placement", preflightKey, null);
        UUID materialize = v.intent(apply, "MATERIALIZE", "sm-cache", materializeKey, HASH);
        UUID stop = v.request(v.p2, "stop", "RUNNING");
        String stopKey = stop + ":STOP:sm-cache";
        UUID stopIntent = v.intent(stop, "STOP", "sm-cache", stopKey, null);
        String requestsBefore = v.requestFingerprint();
        assertFalse(v.hasPlacementColumn(), "precondition: this database is at V020");

        v.upgrade();

        assertTrue(v.hasPlacementColumn());
        assertEquals(v.p1, v.placementOf(preflight));
        assertEquals(v.p1, v.placementOf(materialize));
        assertEquals(v.p2, v.placementOf(stopIntent), "a placement-scoped STOP is placement-scoped: its request's placement");
        assertEquals(preflightKey, v.owner.queryForObject("SELECT idempotency_key FROM sm_data.apply_step_intent"
                + " WHERE intent_id = :i", Map.of("i", preflight), String.class),
                "the placement-scoped key format is byte-for-byte unchanged");
        assertEquals(materializeKey, v.owner.queryForObject("SELECT idempotency_key FROM sm_data.apply_step_intent"
                + " WHERE intent_id = :i", Map.of("i", materialize), String.class));
        assertEquals(stopKey, v.owner.queryForObject("SELECT idempotency_key FROM sm_data.apply_step_intent"
                + " WHERE intent_id = :i", Map.of("i", stopIntent), String.class));
        assertEquals(HASH, v.owner.queryForObject("SELECT config_hash FROM sm_data.apply_step_intent"
                + " WHERE intent_id = :i", Map.of("i", materialize), String.class));
        // MF-2: the migration owner can UPDATE the table (the runtime role cannot) and touches no request row.
        assertEquals(requestsBefore, v.requestFingerprint(), "V021 changes no apply_request row");
        assertEquals(0, v.owner.queryForObject("SELECT count(*)::int FROM sm_data.apply_step_intent"
                + " WHERE placement_id IS NULL", Map.of(), Integer.class));
    }

    // ---- the one refusal ---------------------------------------------------------------------------------

    /**
     * The one case that cannot be back-filled: an instance-wide stop's {@code STOP} intents never recorded which
     * placement each addressed. V021 refuses to run, names the procedure, rolls back, and runs once the stop has
     * landed. The message carries no row value ({@code I6}).
     */
    @Test
    void v021RefusesToRunWhileAnInstanceWideStopIsNonTerminal() {
        AtV020 v = new AtV020("refuse");
        UUID stop = v.request(NIL, "stop", "RUNNING");
        v.intent(stop, "STOP", "sm-cache", stop + ":STOP:sm-cache", null);
        String requestsBefore = v.requestFingerprint();

        Throwable refused = assertThrows(Throwable.class, v::upgrade);

        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null && t.getCause() != t; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains("V021 refuses to run while an instance-wide request is in flight"),
                "V021's own sentence, naming the drain procedure. Was: " + chain);
        assertTrue(chain.toString().contains("let the instance-wide stop land"), chain.toString());
        assertFalse(chain.toString().contains(stop.toString()), "never a row's value (I6)");
        assertFalse(chain.toString().contains(v.tenant.toString()), "never a row's value (I6)");
        assertFalse(v.hasPlacementColumn(), "the migration rolled back: no column was added");
        assertEquals(requestsBefore, v.requestFingerprint(), "…and nothing was deleted or changed");

        // the positive control: the same database upgrades once the stop has landed
        v.land(stop, "STOPPED");
        v.upgrade();
        assertTrue(v.hasPlacementColumn());
    }

    /** An open instance-wide request in any open state refuses; a PLACEMENT-scoped one does not. */
    @Test
    void onlyAnOpenInstanceWideRequestRefusesAndARequestedOneRefusesToo() {
        AtV020 refusing = new AtV020("refuse_requested");
        refusing.request(NIL, "stop", "REQUESTED");
        assertThrows(Throwable.class, refusing::upgrade);
        assertFalse(refusing.hasPlacementColumn());

        AtV020 passing = new AtV020("pass_placement_scoped");
        passing.request(passing.p1, "stop", "REQUESTED");
        passing.request(passing.p2, "apply", "REQUESTED");
        passing.upgrade();
        assertTrue(passing.hasPlacementColumn(), "a placement-scoped request in flight never blocks the upgrade");
    }

    // ---- readiness (W1 review A-1 (iii)) -----------------------------------------------------------------

    /**
     * A new binary must not go Ready on a V020 schema: every table exists there, so the table-count check alone passes,
     * and the engine would fail at its first intent INSERT. The runtime role's own view (it holds SELECT on the table)
     * is what the check uses.
     */
    @Test
    void theNewBinaryRefusesToGoReadyOnAV020SchemaAndPassesOnceV021Ran() throws Exception {
        AtV020 v = new AtV020("readiness");
        ApplyPersistenceConfiguration config = new ApplyPersistenceConfiguration();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> config.smDataSchemaReadiness(v.db.runtime()).afterPropertiesSet());
        assertTrue(refused.getMessage().contains("placement_id is absent") && refused.getMessage().contains("SmDataMigrationMain"),
                refused.getMessage());

        v.upgrade();
        config.smDataSchemaReadiness(v.db.runtime()).afterPropertiesSet();
    }

    /**
     * W1 review A-5 (a), the nil half: a pre-V021 terminal instance-wide stop's history keeps the sentinel and
     * {@code observeApply}'s read reports its steps' placement as absent, never as the sentinel.
     */
    @Test
    void nilHistoryOfAnOldInstanceWideStopObservesAsAnAbsentStepPlacement() {
        AtV020 v = new AtV020("observe_nil");
        UUID stop = v.request(NIL, "stop", "RUNNING");
        v.intent(stop, "STOP", "a", stop + ":STOP:a", null);
        v.land(stop, "STOPPED");
        v.upgrade();

        var observed = new JdbcApplyObservationRepository(new NamedParameterJdbcTemplate(v.db.runtime()))
                .observe(new TenantId(v.tenant), new InstanceId(v.instance), new com.solace.sm.dp.apply.model.ApplyId(stop),
                        null, 10);

        assertEquals(1, observed.size());
        assertEquals(1, observed.get(0).steps().size());
        assertEquals(null, observed.get(0).steps().get(0).placementId(), "history shows as absent, not as the sentinel");
    }

    // ---- history ---------------------------------------------------------------------------------------

    /**
     * A TERMINAL instance-wide stop's intents keep the nil placement: their placements were never persisted, they
     * cannot be derived, and a terminal request admits no new intent (obligation (q)), so they are never re-found or
     * re-inserted. History, not inserts: the trigger refuses nil on every INSERT, and the existing rows are not
     * touched by it.
     */
    @Test
    void aTerminalInstanceWideStopsNilIntentsSurviveAndAreNeverReinserted() {
        AtV020 v = new AtV020("history");
        UUID stop = v.request(NIL, "stop", "RUNNING");
        UUID a = v.intent(stop, "STOP", "a", stop + ":STOP:a", null);
        UUID b = v.intent(stop, "STOP", "b", stop + ":STOP:b", null);
        v.land(stop, "STOPPED");

        v.upgrade();

        assertEquals(NIL, v.placementOf(a), "history keeps the sentinel: stated in the migration");
        assertEquals(NIL, v.placementOf(b));
        NamedParameterJdbcTemplate runtime = new NamedParameterJdbcTemplate(v.db.runtime());
        Refusals.assertRaised("may not be the instance-wide sentinel", () -> runtime.update(
                "INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id, placement_id, replica_ref, step,"
                        + " idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, :p, 'c', 'STOP', :k, NULL, :now)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("t", v.tenant)
                        .addValue("a", stop).addValue("p", NIL).addValue("k", "k-" + UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now())));
        assertEquals(2, v.owner.queryForObject("SELECT count(*)::int FROM sm_data.apply_step_intent"
                + " WHERE apply_id = :a", Map.of("a", stop), Integer.class));
    }

    // ---- resume across the upgrade (the engine, over the upgraded database) --------------------------------

    /** The apply engine wired by hand over the UPGRADED database's runtime role, exactly as {@code ApplyEngineHarness} does over the shared one. */
    private static ApplyExecutor executorOver(AtV020 v, InMemoryRuntimeAdapter adapter) {
        var ds = v.db.runtime();                                   // ONE instance: Spring keys the transaction by it
        var jdbc = new NamedParameterJdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var scopes = new ApplyScopeRepository(jdbc);
        var requests = new ApplyRequestRepository(jdbc);
        var intents = new StepIntentRepository(jdbc);
        var attempts = new AttemptRepository(jdbc);
        var props = ApplyEngineHarness.engineProperties();
        return new ApplyExecutor(new AdmissionService(tx, scopes, requests, Clock.systemUTC()),
                new StepIntentService(tx, requests, intents, Clock.systemUTC()),
                new AttemptService(tx, attempts, new AttemptPolicy(ApplyEngineHarness.maxAttempts()), Clock.systemUTC()),
                new OutcomeService(tx, scopes, requests, Clock.systemUTC()), adapter,
                (p, g) -> TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8)),
                new com.solace.sm.dp.apply.engine.HealthGate(Clock.systemUTC(),
                        com.solace.sm.dp.apply.engine.Sleeper.real(), props),
                new com.solace.sm.dp.apply.engine.ResumePlanner(), props);
    }

    private static int intentCount(AtV020 v, UUID apply) {
        return v.owner.queryForObject("SELECT count(*)::int FROM sm_data.apply_step_intent WHERE apply_id = :a",
                Map.of("a", apply), Integer.class);
    }

    /**
     * The point of keeping the placement-scoped key format: a request the V020 engine admitted and journalled a
     * PREFLIGHT for, and then crashed on, is resumed by the V021 engine onto THAT intent. {@code compare()} requires key
     * equality, so a key that had gained the placement would make this an {@code IntentMismatch} (the mutation proof
     * in the plan: make the key placement-aware for placement-scoped requests too, and this goes red).
     */
    @Test
    void aPlacementScopedApplyRunningAcrossV021ResumesOntoItsOwnIntents() {
        AtV020 v = new AtV020("resume_apply");
        UUID apply = v.request(v.p1, "apply", "RUNNING");
        UUID preflight = v.intent(apply, "PREFLIGHT", "@placement", apply + ":PREFLIGHT:@placement", null);
        v.upgrade();
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(
                new PlacementRef(new TenantId(v.tenant), new InstanceId(v.instance), new PlacementId(v.p1)),
                true, "sm-cache");

        var resumed = executorOver(v, adapter).resume(new TenantId(v.tenant));

        assertEquals(1, resumed.size());
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) resumed.get(0);
        assertEquals(RequestState.STAGED, landed.state(), "a manual apply stages; it did not stall on an intent mismatch");
        assertEquals(1L, v.owner.queryForObject("SELECT count(*) FROM sm_data.apply_step_intent"
                        + " WHERE apply_id = :a AND step = 'PREFLIGHT'", Map.of("a", apply), Long.class),
                "no second PREFLIGHT intent");
        assertEquals(preflight, v.owner.queryForObject("SELECT intent_id FROM sm_data.apply_step_intent"
                + " WHERE apply_id = :a AND step = 'PREFLIGHT'", Map.of("a", apply), UUID.class),
                "the recovered request resumed ONTO the intent it had");
        assertEquals(v.p1, v.placementOf(preflight));
    }

    @Test
    void aPlacementScopedStopRunningAcrossV021ResumesOntoItsOwnIntents() {
        AtV020 v = new AtV020("resume_stop");
        UUID stop = v.request(v.p1, "stop", "RUNNING");
        UUID intent = v.intent(stop, "STOP", "sm-cache", stop + ":STOP:sm-cache", null);
        v.upgrade();
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(
                new PlacementRef(new TenantId(v.tenant), new InstanceId(v.instance), new PlacementId(v.p1)),
                true, "sm-cache");

        var resumed = executorOver(v, adapter).resume(new TenantId(v.tenant));

        assertEquals(1, resumed.size());
        assertEquals(RequestState.STOPPED, ((ApplyOutcome.Landed) resumed.get(0)).state());
        assertEquals(1, intentCount(v, stop), "the stop's one replica has one intent, the one it had");
        assertEquals(intent, v.owner.queryForObject("SELECT intent_id FROM sm_data.apply_step_intent"
                + " WHERE apply_id = :a", Map.of("a", stop), UUID.class));
        assertEquals(Liveness.STOPPED, adapter.status(adapter.replicas(null).get(0)).liveness());
    }
}
