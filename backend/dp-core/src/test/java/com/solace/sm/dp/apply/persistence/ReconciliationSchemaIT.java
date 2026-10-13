package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.8 - {@code sm_data} V011. Every statement that exercises a privilege runs as {@code sm_dp_runtime}, and
 * every refusal is asserted by SQLSTATE and name.
 *
 * <p>It also records Step 0's DDL measurements as tests, so they cannot go stale: (a) the {@code APPLY_INDETERMINATE →
 * RUNNING} re-drive walk is legal in the DDL as it stands, and (b) the journal holds a second attempt on an intent whose
 * request has been re-opened.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationSchemaIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private record Req(UUID tenant, UUID instance, UUID placement, UUID applyId) {
    }

    private static Req indeterminate() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance, placement, "REQUESTED");
        walk(applyId, "REQUESTED", "RUNNING");
        walk(applyId, "RUNNING", "APPLY_INDETERMINATE");
        return new Req(tenant, instance, placement, applyId);
    }

    private static int walk(UUID applyId, String from, String to) {
        return runtime.update("UPDATE sm_data.apply_request SET state = :to WHERE apply_id = :a AND state = :from",
                new MapSqlParameterSource().addValue("a", applyId).addValue("from", from).addValue("to", to));
    }

    private static long stamp(UUID applyId) {
        return superuser.queryForObject("SELECT state_version FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), Long.class);
    }

    private static MapSqlParameterSource key(Req r) {
        return new MapSqlParameterSource().addValue("t", r.tenant()).addValue("a", r.applyId())
                .addValue("now", OffsetDateTime.now());
    }

    private static int insertRow(Req r) {
        return runtime.update("INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at,"
                + " first_indeterminate_at) VALUES (:t, :a, :now, :now)", key(r));
    }

    // ---- Step 0 (a), (b): what the DDL already allows --------------------------------------------

    @Test
    void theReDriveWalkIndeterminateToRunningIsLegalInTheDdlAsItStands() {
        Req r = indeterminate();

        assertEquals(1, walk(r.applyId(), "APPLY_INDETERMINATE", "RUNNING"),
                "V002's state_fits_operation, terminal_has_timestamp and V006's terminal guard all admit it: no state-CHECK "
                        + "change is needed (Step 0 (a))");
        assertEquals(1, walk(r.applyId(), "RUNNING", "APPLY_INDETERMINATE"), "and the walk back, for a re-drive that is cut off again");
        assertEquals(1, walk(r.applyId(), "APPLY_INDETERMINATE", "RUNNING"));
        assertEquals(1, runtime.update("UPDATE sm_data.apply_request SET state = 'APPLIED', terminal_at = :now"
                + " WHERE apply_id = :a", key(r)), "and a re-drive that succeeds lands terminal");
    }

    @Test
    void theJournalHoldsASecondAttemptOnTheSameIntentOfAReOpenedRequest() {
        Req r = indeterminate();
        UUID intent = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id, placement_id,"
                + " replica_ref, step, idempotency_key, config_hash, created_at) VALUES (:i, :t, :a, :p, 'replica-1',"
                + " 'LIFECYCLE', :k, NULL, :now)",
                key(r).addValue("i", intent).addValue("p", r.placement()).addValue("k", intent.toString()));
        for (int n = 1; n <= 2; n++) {
            assertEquals(1, runtime.update("INSERT INTO sm_data.apply_attempt (attempt_id, tenant_id, apply_id,"
                    + " intent_id, step, attempt_no, started_at, ended_at, step_deadline, outcome)"
                    + " VALUES (:att, :t, :a, :i, 'LIFECYCLE', :n, :now, :now, :now, 'FAILED')",
                    key(r).addValue("att", UUID.randomUUID()).addValue("i", intent).addValue("n", n)));
            if (n == 1) {
                assertEquals(1, walk(r.applyId(), "APPLY_INDETERMINATE", "RUNNING"));
            }
        }
        assertEquals(2, runtime.queryForObject("SELECT count(*)::int FROM sm_data.apply_attempt WHERE intent_id = :i",
                new MapSqlParameterSource("i", intent), Integer.class),
                "Step 0 (b): intents are immutable and attempts append; a re-opened request breaks no invariant");
    }

    // ---- reconcile_class --------------------------------------------------------------------------

    @Test
    void theRuntimeRoleSetsReconcileClassAndTheWriteDrawsANewStamp() {
        Req r = indeterminate();
        long before = stamp(r.applyId());

        assertEquals(1, runtime.update("UPDATE sm_data.apply_request SET reconcile_class = 'reconcile.foreign.content'"
                + " WHERE tenant_id = :t AND apply_id = :a", key(r)));

        assertTrue(stamp(r.applyId()) > before,
                "M-1: a give-up must be an observable change - the T2.2 trigger stamps any change but the compaction fold");
        assertEquals("reconcile.foreign.content", superuser.queryForObject(
                "SELECT reconcile_class FROM sm_data.apply_request WHERE apply_id = :a", key(r), String.class));
    }

    @Test
    void reconcileClassIsAClassifierGrammarAndNothingElse() {
        Req r = indeterminate();
        for (String bad : List.of("Reconcile.Foreign", "has space", "line\nbreak", "token=secret", "x".repeat(65),
                "9starts.with.digit", "trailing.", "")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "reconcile_class_grammar",
                    () -> runtime.update("UPDATE sm_data.apply_request SET reconcile_class = :c WHERE apply_id = :a",
                            key(r).addValue("c", bad)), "'" + bad.replace("\n", "\\n") + "'");
        }
    }

    @Test
    void reconcileClassCannotBeWrittenOnceTheRequestIsTerminalSoAnOperatorResolutionWins() {
        Req r = indeterminate();
        assertEquals(1, walk(r.applyId(), "APPLY_INDETERMINATE", "RUNNING"));
        assertEquals(1, runtime.update("UPDATE sm_data.apply_request SET state = 'APPLIED', terminal_at = :now"
                + " WHERE apply_id = :a", key(r)));

        Refusals.assertRaised("terminal", () -> runtime.update(
                "UPDATE sm_data.apply_request SET reconcile_class = 'reconcile.exhausted' WHERE apply_id = :a", key(r)));
    }

    @Test
    void theColumnGrantStaysNarrowIdentityAndFreeTextAreStillNotRuntimeWritable() {
        Req r = indeterminate();
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.apply_request SET tenant_id = :t WHERE apply_id = :a", key(r)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.apply_request SET resolution_note = 'x' WHERE apply_id = :a", key(r)));
        assertNull(superuser.queryForObject("SELECT reconcile_class FROM sm_data.apply_request WHERE apply_id = :a",
                key(r), String.class), "a request starts with no classifier");
    }

    // ---- apply_reconciliation ---------------------------------------------------------------------

    @Test
    void aReconciliationRowNamesARealRequestOfTheSameTenant() {
        Req r = indeterminate();
        assertEquals(1, insertRow(r));

        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "reconciliation_has_request", () -> runtime.update(
                "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at, first_indeterminate_at)"
                        + " VALUES (:t, :other, :now, :now)", key(r).addValue("other", UUID.randomUUID())),
                "no such request");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "reconciliation_has_request", () -> runtime.update(
                "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at, first_indeterminate_at)"
                        + " VALUES (:other, :a, :now, :now)", key(r).addValue("other", UUID.randomUUID())),
                "another tenant's request id");
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "apply_reconciliation_pkey", () -> insertRow(r),
                "one row per request");
    }

    @Test
    void theCountersAreNonNegativeAndTheClassifierIsAGrammarToken() {
        Req r = indeterminate();
        insertRow(r);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "reconciliation_inspections_nonnegative", () -> runtime.update(
                "UPDATE sm_data.apply_reconciliation SET inspections = -1 WHERE apply_id = :a", key(r)));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "reconciliation_redrives_nonnegative", () -> runtime.update(
                "UPDATE sm_data.apply_reconciliation SET redrives = -1 WHERE apply_id = :a", key(r)));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "reconciliation_last_class_grammar", () -> runtime.update(
                "UPDATE sm_data.apply_reconciliation SET last_class = 'Not A Token' WHERE apply_id = :a", key(r)));
    }

    @Test
    void theRuntimeRoleUpdatesTheBookkeepingColumnsOnlyAndNeverDeletes() {
        Req r = indeterminate();
        insertRow(r);

        assertEquals(1, runtime.update("UPDATE sm_data.apply_reconciliation SET inspections = 2, redrives = 1,"
                + " next_attempt_at = :now, gave_up_at = :now, last_class = 'reconcile.exhausted' WHERE apply_id = :a", key(r)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.apply_reconciliation SET tenant_id = :t2 WHERE apply_id = :a",
                key(r).addValue("t2", UUID.randomUUID())), "identity is not re-targetable");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.apply_reconciliation SET first_indeterminate_at = :now WHERE apply_id = :a", key(r)),
                "the age bound's origin is written once");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.apply_reconciliation WHERE apply_id = :a", key(r)), "history, not a queue");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.getJdbcTemplate()
                .execute("TRUNCATE sm_data.apply_reconciliation"));
    }

    @Test
    void aStampIsNotDrawnByBookkeepingAlone() {
        Req r = indeterminate();
        long before = stamp(r.applyId());
        insertRow(r);
        runtime.update("UPDATE sm_data.apply_reconciliation SET inspections = inspections + 1 WHERE apply_id = :a", key(r));

        assertEquals(before, stamp(r.applyId()),
                "the counters are bookkeeping: the observable change is the request's classifier, not a counter");
    }

    @Test
    void theDueIndexIsPartialOnOpenRows() {
        List<Map<String, Object>> idx = superuser.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'sm_data' AND indexname = 'apply_reconciliation_due'",
                Map.of());
        assertEquals(1, idx.size());
        String def = String.valueOf(idx.get(0).get("indexdef"));
        assertTrue(def.contains("tenant_id, next_attempt_at") && def.contains("gave_up_at IS NULL"),
                "a closed row costs the poll nothing: " + def);
        assertNotEquals(-1, def.indexOf("WHERE"));
    }
}
