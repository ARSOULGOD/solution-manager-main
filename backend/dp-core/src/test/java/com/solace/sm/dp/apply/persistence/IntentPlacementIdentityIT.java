package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code BE-D44} (plan 008 S2, {@code sm_data} V021): {@code apply_step_intent} is placement-aware, so BOTH of
 * {@code BE-D23}'s clauses are closed in the DDL itself — a database-level test, written as raw statements as
 * {@code sm_dp_runtime} (the credential the application holds), because the claim is about the table and not about any
 * engine that happens to use it. The engine-level halves (an instance-wide stop over two placements whose containers
 * share a name lands {@code STOPPED}; an instance-wide apply is still refused before admission) are in
 * {@code StopApplyIT}, whose own fake adapter they need.
 *
 * <p>The shape being proven: the identity is {@code (tenant, apply, placement, step, replica)}; a placement-scoped
 * request's intents all carry the request's own placement (so for that request the old identity is unchanged in
 * effect); an instance-wide request's intents each name a real placement of the request's instance; and no new intent
 * ever carries the nil sentinel. {@code InstanceWideIdentityLimitIT} used to pin the opposite and now pins this.
 */
@Testcontainers(disabledWithoutDocker = true)
class IntentPlacementIdentityIT {

    private static final UUID NIL = ApplyRows.INSTANCE_WIDE;
    private static final String HASH = "sha256:1111111111111111111111111111111111111111";

    private NamedParameterJdbcTemplate jdbc;
    private UUID tenant;
    private UUID instance;
    private UUID p1;
    private UUID p2;
    private UUID otherInstancesPlacement;
    private UUID retiredPlacement;
    /** A placement-scoped apply on {@code p1}, and an instance-wide stop, both open. */
    private UUID applyOnP1;
    private UUID stopAll;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        jdbc = SmDataPostgres.runtimeJdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        UUID otherInstance = UUID.randomUUID();
        ApplyRows.insertScope(jdbc, tenant, instance);
        InventoryFixtures.declare(tenant, otherInstance);
        p1 = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        p2 = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        retiredPlacement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        jdbc.update("UPDATE sm_data.placement SET retired_at = :now WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("t", tenant)
                        .addValue("p", retiredPlacement));
        otherInstancesPlacement = InventoryFixtures.placement(tenant, otherInstance,
                InventoryFixtures.target(tenant));
        applyOnP1 = ApplyRows.insertRequest(jdbc, tenant, instance, p1, "REQUESTED");
        stopAll = ApplyRows.insertRequest(jdbc, tenant, instance, NIL, "stop", "REQUESTED");
    }

    private void insertIntent(UUID apply, UUID placement, String step, String replica, String key, String hash) {
        jdbc.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id, placement_id,"
                        + " replica_ref, step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, :p, :r, :s, :k, :h, :now)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("t", tenant)
                        .addValue("a", apply).addValue("p", placement).addValue("r", replica)
                        .addValue("s", step).addValue("k", key).addValue("h", hash)
                        .addValue("now", OffsetDateTime.now()));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private int count(UUID apply) {
        return jdbc.queryForObject("SELECT count(*)::int FROM sm_data.apply_step_intent"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", tenant).addValue("a", apply), Integer.class);
    }

    // ---- clause 1: placement-scoped steps, one per placement ------------------------------------------

    /**
     * {@code BE-D23}'s first clause: {@code placement_scoped_steps} pins {@code replica_ref} to {@code '@placement'},
     * so the old key admitted one {@code PREFLIGHT} per {@code apply_id}. The new key admits one per placement. (The
     * engine still refuses an instance-wide APPLY before admission, {@code BE-D45}; this is what makes that refusal a
     * policy rather than a limit of the table.)
     */
    @Test
    void twoPlacementScopedIntentsOfOneApplyCoexistInDifferentPlacements() {
        insertIntent(stopAll, p1, "PREFLIGHT", "@placement", key(), null);
        insertIntent(stopAll, p2, "PREFLIGHT", "@placement", key(), null);

        assertEquals(2, count(stopAll));
    }

    // ---- clause 2: replica_ref is adapter vocabulary ----------------------------------------------------

    /** {@code BE-D23}'s second clause, R5's case: two placements, one container name, one {@code apply_id}. */
    @Test
    void twoStopIntentsWithTheSameReplicaRefInDifferentPlacementsCoexist() {
        insertIntent(stopAll, p1, "STOP", "sm-cache", key(), null);
        insertIntent(stopAll, p2, "STOP", "sm-cache", key(), null);

        assertEquals(2, count(stopAll));
    }

    /** Narrowed, not removed: the same replica in the same placement is still ONE intent. */
    @Test
    void twoIntentsWithTheSameReplicaRefInTheSamePlacementStillCollide() {
        insertIntent(stopAll, p1, "STOP", "sm-cache", key(), null);

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, Constraints.INTENT_STEP_REPLICA_UNIQUE,
                () -> insertIntent(stopAll, p1, "STOP", "sm-cache", key(), null),
                "same (apply, placement, step, replica), a different key: only the identity key can fire");
        assertEquals(1, count(stopAll));
    }

    @Test
    void theIdentityKeyIsTheNewNamedConstraintAndTheOldOneIsGone() {
        List<String> uniques = SmDataPostgres.superuserJdbc().queryForList(
                "SELECT con.conname FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'sm_data' AND c.relname = 'apply_step_intent' AND con.contype = 'u'"
                        + " ORDER BY con.conname", new MapSqlParameterSource(), String.class);

        assertTrue(uniques.contains(Constraints.INTENT_STEP_REPLICA_UNIQUE), uniques.toString());
        assertTrue(!uniques.contains("apply_step_intent_tenant_id_apply_id_step_replica_ref_key"),
                "the placement-blind key was dropped in the same migration that added its replacement: " + uniques);
        assertEquals("UNIQUE (tenant_id, apply_id, placement_id, step, replica_ref)",
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :n",
                        new MapSqlParameterSource("n", Constraints.INTENT_STEP_REPLICA_UNIQUE), String.class));
    }

    // ---- what an intent's placement must be -------------------------------------------------------------

    @Test
    void anIntentsPlacementMustBeItsRequestsPlacementWhenTheRequestIsPlacementScoped() {
        insertIntent(applyOnP1, p1, "PREFLIGHT", "@placement", key(), null);

        Refusals.assertRaised("must be the request's own placement",
                () -> insertIntent(applyOnP1, p2, "MATERIALIZE", "sm-cache", key(), HASH));
        Refusals.assertRaised("must be the request's own placement",
                () -> insertIntent(applyOnP1, retiredPlacement, "MATERIALIZE", "sm-cache", key(), HASH));
        assertEquals(1, count(applyOnP1));
    }

    @Test
    void anInstanceWideStopsIntentNamesARealPlacementOfTheSameInstance() {
        insertIntent(stopAll, p1, "STOP", "a", key(), null);
        insertIntent(stopAll, retiredPlacement, "STOP", "b", key(), null);   // live OR retired: history is history

        Refusals.assertRaised("must be a placement of the request's instance",
                () -> insertIntent(stopAll, UUID.randomUUID(), "STOP", "c", key(), null));
        Refusals.assertRaised("must be a placement of the request's instance",
                () -> insertIntent(stopAll, otherInstancesPlacement, "STOP", "d", key(), null));
        assertEquals(2, count(stopAll));
    }

    /** The sentinel marks a REQUEST as instance-wide; an intent addresses a real placement, every insert. */
    @Test
    void aNewIntentNeverCarriesTheNilPlacement() {
        Refusals.assertRaised("may not be the instance-wide sentinel",
                () -> insertIntent(stopAll, NIL, "STOP", "a", key(), null));
        Refusals.assertRaised("may not be the instance-wide sentinel",
                () -> insertIntent(applyOnP1, NIL, "PREFLIGHT", "@placement", key(), null));
        assertEquals(0, count(stopAll) + count(applyOnP1));
    }

    @Test
    void anIntentCannotBeInsertedWithoutAPlacement() {
        Refusals.assertRefused(Refusals.NOT_NULL_VIOLATION, null,
                () -> insertIntent(stopAll, null, "STOP", "a", key(), null));
    }

    // ---- what did not change ----------------------------------------------------------------------------

    @Test
    void placementScopedStepsStillRequireTheAtPlacementSentinel() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_scoped_steps",
                () -> insertIntent(applyOnP1, p1, "PREFLIGHT", "sm-cache", key(), null));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_scoped_steps",
                () -> insertIntent(applyOnP1, p1, "MATERIALIZE", "@placement", key(), HASH));
    }

    @Test
    void hashOnConfigStepsAndTheAttemptTripleFkAreUnchanged() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "hash_on_config_steps",
                () -> insertIntent(applyOnP1, p1, "MATERIALIZE", "sm-cache", key(), null));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "hash_on_config_steps",
                () -> insertIntent(applyOnP1, p1, "STOP", "sm-cache", key(), HASH));

        // the attempt's triple FK targets UNIQUE (tenant_id, apply_id, intent_id), which V021 does not touch
        assertEquals("FOREIGN KEY (tenant_id, apply_id, intent_id) REFERENCES"
                        + " sm_data.apply_step_intent(tenant_id, apply_id, intent_id)",
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'apply_attempt_tenant_id_apply_id_intent_id_fkey'",
                        new MapSqlParameterSource(), String.class));
        assertEquals("FOREIGN KEY (intent_id, step) REFERENCES sm_data.apply_step_intent(intent_id, step)",
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'apply_attempt_intent_id_step_fkey'",
                        new MapSqlParameterSource(), String.class));
    }

    /** The idempotency key stays GLOBALLY unique: the new identity does not let one key name two steps. */
    @Test
    void theIdempotencyKeyIsStillGloballyUnique() {
        String shared = key();
        insertIntent(stopAll, p1, "STOP", "a", shared, null);

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE,
                () -> insertIntent(stopAll, p2, "STOP", "a", shared, null));
    }
}
