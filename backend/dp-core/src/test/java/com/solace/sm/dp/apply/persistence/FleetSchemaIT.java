package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T11.2 - {@code sm_data} V014: {@code fleet_action} and {@code fleet_member}, rule by rule
 * ({@code fleet-apply.md} section 4.3, "Rules the DDL must carry"). As {@code sm_dp_runtime}, the credential the
 * application holds; the superuser only reads back what a refusal must not have changed. Refusals are asserted by
 * SQLSTATE and by the constraint's or the trigger's own wording, so a statement that fails for another reason is not a
 * proof ({@code L7}).
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetSchemaIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private record Fleet(UUID tenant, UUID id) {
        MapSqlParameterSource key() {
            return new MapSqlParameterSource().addValue("t", tenant).addValue("f", id);
        }
    }

    private static Fleet fleet() {
        Fleet fleet = new Fleet(UUID.randomUUID(), UUID.randomUUID());
        runtime.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy, batch_size,"
                        + " on_failure, state, current_batch, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, :f, :c, 'manual', 2, 'halt-remaining', 'RUNNING', 0, 'principal-1', now(), :x)",
                fleet.key().addValue("c", UUID.randomUUID()).addValue("x", UUID.randomUUID()));
        return fleet;
    }

    private static void member(Fleet fleet, int ordinal, String disposition) {
        member(fleet, ordinal, disposition, UUID.randomUUID(), UUID.randomUUID());
    }

    private static void member(Fleet fleet, int ordinal, String disposition, UUID instance, UUID placement) {
        boolean refused = disposition.equals("refused");
        runtime.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, apply_id, disposition, refusal_code) VALUES (:t, :f, :o, :b, :i, :p, 1, :a, :d,"
                        + " :rc)",
                fleet.key().addValue("o", ordinal).addValue("b", refused ? null : ordinal).addValue("i", instance)
                        .addValue("p", placement).addValue("a", refused ? null : UUID.randomUUID())
                        .addValue("d", disposition).addValue("rc", refused ? "NOT_FOUND" : null));
    }

    private static int updateMember(Fleet fleet, int ordinal, String set) {
        return runtime.update("UPDATE sm_data.fleet_member SET " + set + " WHERE tenant_id = :t AND fleet_id = :f"
                + " AND ordinal = :o", fleet.key().addValue("o", ordinal));
    }

    private static long stamp(Fleet fleet) {
        return superuser.queryForObject("SELECT state_version FROM sm_data.fleet_action WHERE tenant_id = :t"
                + " AND fleet_id = :f", fleet.key(), Long.class);
    }

    private static String disposition(Fleet fleet, int ordinal) {
        return superuser.queryForObject("SELECT disposition FROM sm_data.fleet_member WHERE tenant_id = :t"
                + " AND fleet_id = :f AND ordinal = :o", fleet.key().addValue("o", ordinal), String.class);
    }

    // ---- append-only in meaning ------------------------------------------------------------------------------

    @Test
    void theRuntimeRoleCanNeverDeleteBecauseTheFleetIsPartOfTheAuditTrail() {
        Fleet fleet = fleet();
        member(fleet, 0, "pending");

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.fleet_member WHERE tenant_id = :t", fleet.key()));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.fleet_action WHERE tenant_id = :t", fleet.key()));
        assertEquals(List.of(), superuser.queryForList("SELECT privilege_type FROM information_schema.table_privileges"
                + " WHERE table_schema = 'sm_data' AND table_name IN ('fleet_action', 'fleet_member')"
                + " AND grantee = 'sm_dp_runtime' AND privilege_type IN ('DELETE', 'TRUNCATE')", new MapSqlParameterSource(),
                String.class));
    }

    @Test
    void identityAndTheStampAreNotRuntimeWritable() {
        Fleet fleet = fleet();
        member(fleet, 0, "pending");

        for (String column : List.of("tenant_id = gen_random_uuid()", "fleet_id = gen_random_uuid()",
                "catalog_solution_id = gen_random_uuid()", "strategy = 'scheduled'", "batch_size = 9",
                "on_failure = 'continue'", "requested_by = 'someone-else'", "state_version = 1")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_data.fleet_action SET " + column + " WHERE tenant_id = :t", fleet.key()));
        }
        for (String column : List.of("instance_id = gen_random_uuid()", "placement_id = gen_random_uuid()",
                "generation = 5", "apply_id = gen_random_uuid()", "batch = 3", "ordinal = 7",
                "refusal_code = 'FORBIDDEN'")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_data.fleet_member SET " + column + " WHERE tenant_id = :t", fleet.key()));
        }
        // the stamp is the trigger's alone: an explicit value on INSERT is refused too (V007's measured M5)
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy, batch_size,"
                        + " on_failure, state, requested_by, requested_at, correlation_id, state_version)"
                        + " VALUES (:t, gen_random_uuid(), :t, 'manual', 1, 'continue', 'RUNNING', 'p', now(), :t, 1)",
                fleet.key()));
    }

    @Test
    void theTablesHoldIdsNumbersAndClassifiersOnlyNeverASecret() {
        // I6: names are enough to see it; there is nowhere to put a value
        assertEquals(java.util.Set.of("batch", "generation", "instance_id", "placement_id", "ordinal", "apply_id",
                        "disposition", "refusal_code", "admission_refusal", "settled", "tenant_id", "fleet_id"),
                java.util.Set.copyOf(superuser.queryForList("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'fleet_member'", new MapSqlParameterSource(),
                        String.class)));
        assertEquals(java.util.Set.of("batch_size", "catalog_solution_id", "correlation_id", "current_batch", "fleet_id",
                        "on_failure", "outage_confirmed_by", "requested_at", "requested_by", "state", "state_version",
                        "strategy", "tenant_id", "terminal_at"),
                java.util.Set.copyOf(superuser.queryForList("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'fleet_action'", new MapSqlParameterSource(),
                        String.class)));
    }

    // ---- forward-only transitions ---------------------------------------------------------------------------

    @Test
    void aFleetLeavesRunningExactlyOnceAndTerminalAtIsSetExactlyWhileItIsNot() {
        Fleet fleet = fleet();

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_terminal_has_timestamp", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET state = 'SUCCEEDED' WHERE tenant_id = :t", fleet.key()),
                "a terminal state without terminal_at");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_terminal_has_timestamp", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET terminal_at = now() WHERE tenant_id = :t", fleet.key()),
                "terminal_at while RUNNING");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_state_known", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET state = 'DONE', terminal_at = now() WHERE tenant_id = :t", fleet.key()));

        assertEquals(1, runtime.update("UPDATE sm_data.fleet_action SET state = 'HALTED', terminal_at = now()"
                + " WHERE tenant_id = :t", fleet.key()));
        Refusals.assertRaised("leaves RUNNING exactly once", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET state = 'SUCCEEDED' WHERE tenant_id = :t", fleet.key()));
        Refusals.assertRaised("leaves RUNNING exactly once", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET state = 'RUNNING', terminal_at = NULL WHERE tenant_id = :t", fleet.key()));
        Refusals.assertRaised("terminal_at", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET terminal_at = now() + interval '1 day' WHERE tenant_id = :t", fleet.key()));
    }

    @Test
    void aFleetNeverMovesBackToAnEarlierBatch() {
        Fleet fleet = fleet();
        assertEquals(1, runtime.update("UPDATE sm_data.fleet_action SET current_batch = 3 WHERE tenant_id = :t",
                fleet.key()));

        Refusals.assertRaised("earlier batch", () -> runtime.update(
                "UPDATE sm_data.fleet_action SET current_batch = 2 WHERE tenant_id = :t", fleet.key()));
        // (a negative batch can only be BORN: an update to one is a move back, which the trigger names first)
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_current_batch_nonneg", () -> runtime.update(
                "INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy, batch_size,"
                        + " on_failure, state, current_batch, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, gen_random_uuid(), :t, 'manual', 1, 'continue', 'RUNNING', -1, 'p', now(), :t)",
                fleet.key()));
    }

    @Test
    void aMemberIsBornPendingOrRefusedAndMovesForwardOnlyFromPending() {
        Fleet fleet = fleet();
        Refusals.assertRaised("born pending or refused", () -> member(fleet, 0, "admitted"));
        Refusals.assertRaised("born pending or refused", () -> member(fleet, 0, "skipped"));
        member(fleet, 0, "pending");
        member(fleet, 1, "pending");
        member(fleet, 2, "pending");
        member(fleet, 3, "refused");

        assertEquals(1, updateMember(fleet, 0, "disposition = 'admitted'"));
        Refusals.assertRaised("only moves forward", () -> updateMember(fleet, 0, "disposition = 'pending'"));
        Refusals.assertRaised("only moves forward", () -> updateMember(fleet, 0, "disposition = 'skipped'"));
        Refusals.assertRaised("only moves forward", () -> updateMember(fleet, 0, "disposition = 'not_admitted',"
                + " admission_refusal = 'SCOPE_BUSY', settled = 'failed'"));

        assertEquals(1, updateMember(fleet, 1, "disposition = 'skipped'"));
        Refusals.assertRaised("only moves forward", () -> updateMember(fleet, 1, "disposition = 'admitted'"));

        assertEquals(1, updateMember(fleet, 2, "disposition = 'not_admitted', admission_refusal = 'SCOPE_BUSY',"
                + " settled = 'failed'"));
        Refusals.assertRaised("only moves forward", () -> updateMember(fleet, 2, "disposition = 'admitted'"));

        Refusals.assertRaised("refused", () -> updateMember(fleet, 3, "disposition = 'pending'"));
        assertEquals("refused", disposition(fleet, 3), "a refused member is written only at insert");
    }

    @Test
    void anAdmittedMemberGainsItsSettlementExactlyOnce() {
        Fleet fleet = fleet();
        member(fleet, 0, "pending");
        member(fleet, 1, "pending");

        Refusals.assertRaised("admitted before it is settled", () -> updateMember(fleet, 0,
                "disposition = 'admitted', settled = 'succeeded'"));
        assertEquals(1, updateMember(fleet, 0, "disposition = 'admitted'"));
        assertEquals(1, updateMember(fleet, 0, "settled = 'succeeded'"));

        Refusals.assertRaised("settlement", () -> updateMember(fleet, 0, "settled = 'failed'"));
        Refusals.assertRaised("settlement", () -> updateMember(fleet, 0, "settled = NULL"));
        assertEquals("succeeded", superuser.queryForObject("SELECT settled FROM sm_data.fleet_member WHERE tenant_id = :t"
                + " AND fleet_id = :f AND ordinal = 0", fleet.key(), String.class), "written once, never changed");
        // a settlement needs a member that was admitted
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_settled_fits_disposition",
                () -> updateMember(fleet, 1, "settled = 'failed'"));
    }

    // ---- the shape of a member --------------------------------------------------------------------------------

    @Test
    void aRefusedMemberHasACodeAndNoBatchAndNoRequestAndEveryOtherMemberTheReverse() {
        Fleet fleet = fleet();
        // each statement below breaks EXACTLY ONE rule: PostgreSQL reports the first violated in name order, so a
        // statement that broke two would prove only the first
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_refusal_iff_refused", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, instance_id, placement_id, generation,"
                        + " disposition) VALUES (:t, :f, 0, gen_random_uuid(), gen_random_uuid(), 1, 'refused')",
                fleet.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_refusal_iff_refused", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, apply_id, disposition, refusal_code) VALUES (:t, :f, 0, 0, gen_random_uuid(),"
                        + " gen_random_uuid(), 1, gen_random_uuid(), 'pending', 'FORBIDDEN')", fleet.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_batch_unless_refused", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, instance_id, placement_id, generation,"
                        + " apply_id, disposition) VALUES (:t, :f, 0, gen_random_uuid(), gen_random_uuid(), 1,"
                        + " gen_random_uuid(), 'pending')", fleet.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_apply_id_unless_refused", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, disposition) VALUES (:t, :f, 0, 0, gen_random_uuid(), gen_random_uuid(), 1,"
                        + " 'pending')", fleet.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_refusal_code_known", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, instance_id, placement_id, generation,"
                        + " disposition, refusal_code) VALUES (:t, :f, 0, gen_random_uuid(), gen_random_uuid(), 1,"
                        + " 'refused', 'TEAPOT')", fleet.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_generation_positive", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, instance_id, placement_id, generation,"
                        + " disposition, refusal_code) VALUES (:t, :f, 0, gen_random_uuid(), gen_random_uuid(), 0,"
                        + " 'refused', 'NOT_FOUND')", fleet.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_disposition_known", () -> runtime.update(
                "INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, apply_id, disposition) VALUES (:t, :f, 0, 0, gen_random_uuid(),"
                        + " gen_random_uuid(), 1, gen_random_uuid(), 'running')", fleet.key()));
    }

    @Test
    void anAdmissionRefusalIsANamedClassifierSetExactlyWhenTheMemberWasNotAdmitted() {
        Fleet fleet = fleet();
        member(fleet, 0, "pending");
        member(fleet, 1, "pending");

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_admission_refusal_iff_not_admitted",
                () -> updateMember(fleet, 0, "disposition = 'admitted', admission_refusal = 'SCOPE_BUSY'"));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_admission_refusal_iff_not_admitted",
                () -> updateMember(fleet, 0, "disposition = 'not_admitted', settled = 'failed'"));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_admission_refusal_grammar", () -> updateMember(
                fleet, 0, "disposition = 'not_admitted', admission_refusal = 'a free text sentence', settled = 'failed'"));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "fleet_member_settled_fits_disposition", () -> updateMember(
                fleet, 0, "disposition = 'not_admitted', admission_refusal = 'SCOPE_BUSY', settled = 'succeeded'"));
        assertEquals(1, updateMember(fleet, 1, "disposition = 'not_admitted', admission_refusal = 'SCOPE_BUSY',"
                + " settled = 'failed'"));
    }

    @Test
    void aMemberIsNamedOnceInAFleetAndAMemberNeedsItsFleet() {
        Fleet fleet = fleet();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        member(fleet, 0, "pending", instance, placement);

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "fleet_member_named_once",
                () -> member(fleet, 1, "pending", instance, placement));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "fleet_member_pkey",
                () -> member(fleet, 0, "pending", UUID.randomUUID(), UUID.randomUUID()));
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "fleet_member_has_fleet",
                () -> member(new Fleet(fleet.tenant(), UUID.randomUUID()), 0, "pending"));
    }

    // ---- section 5.6: at most one live reservation per placement ------------------------------------------------------

    @Test
    void aPlacementHasAtMostOneLiveReservationAndASettledOrRefusedOrSkippedMemberHoldsNone() {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        Fleet first = new Fleet(tenant, UUID.randomUUID());
        Fleet second = new Fleet(tenant, UUID.randomUUID());
        Fleet third = new Fleet(tenant, UUID.randomUUID());
        Fleet fourth = new Fleet(tenant, UUID.randomUUID());
        for (Fleet f : List.of(first, second, third, fourth)) {
            runtime.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy,"
                            + " batch_size, on_failure, state, requested_by, requested_at, correlation_id) VALUES (:t, :f,"
                            + " :c, 'manual', 1, 'continue', 'RUNNING', 'p', now(), :x)",
                    f.key().addValue("c", UUID.randomUUID()).addValue("x", UUID.randomUUID()));
        }
        member(first, 0, "pending", instance, placement);

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "fleet_member_reservation",
                () -> member(second, 0, "pending", instance, placement), "a pending member reserves the placement");
        // refused members reserve nothing: they were never going to be applied
        member(third, 0, "refused", instance, placement);

        assertEquals(1, updateMember(first, 0, "disposition = 'admitted'"));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "fleet_member_reservation",
                () -> member(second, 2, "pending", instance, placement), "an admitted, unsettled member still holds it");

        assertEquals(1, updateMember(first, 0, "settled = 'failed'"));
        assertEquals(1, runtime.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id,"
                        + " placement_id, generation, apply_id, disposition) VALUES (:t, :f, 3, 3, :i, :p, 1,"
                        + " gen_random_uuid(), 'pending')", second.key().addValue("i", instance).addValue("p", placement)),
                "a settled member freed the placement");
        assertEquals(1, updateMember(second, 3, "disposition = 'skipped'"));
        assertEquals(1, runtime.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id,"
                        + " placement_id, generation, apply_id, disposition) VALUES (:t, :f, 0, 0, :i, :p, 1,"
                        + " gen_random_uuid(), 'pending')", fourth.key().addValue("i", instance).addValue("p", placement)),
                "a skipped member held nothing");
    }

    // ---- OR-35: stamping ----------------------------------------------------------------------------------------

    @Test
    void fleetActionIsStampedOnInsertAndOnEveryChangeAndAMemberChangeStampsItsParent() {
        Fleet fleet = fleet();
        long born = stamp(fleet);
        assertTrue(born > 0, "stamped on insert, from the one sequence");

        member(fleet, 0, "pending");
        assertEquals(born, stamp(fleet), "inserting a member changes nothing observable (the parent is not re-stamped)");

        runtime.update("UPDATE sm_data.fleet_action SET current_batch = 1 WHERE tenant_id = :t", fleet.key());
        long moved = stamp(fleet);
        assertTrue(moved > born, "a parent change stamps");

        assertEquals(1, updateMember(fleet, 0, "disposition = 'admitted'"));
        long admitted = stamp(fleet);
        assertTrue(admitted > moved, "a MEMBER change stamps the parent: no member change can go unstamped");

        assertEquals(1, updateMember(fleet, 0, "settled = 'succeeded'"));
        assertTrue(stamp(fleet) > admitted);

        // the stamp is the one sequence's: nothing else may have drawn a value out of order
        long last = superuser.queryForObject("SELECT last_value FROM sm_data.state_version_seq",
                new MapSqlParameterSource(), Long.class);
        assertTrue(last >= stamp(fleet));
    }

    @Test
    void aMemberChangeWaitsOnTheParentRowSoStampOrderIsCommitOrder() {
        Fleet fleet = fleet();
        member(fleet, 0, "pending");
        // The property the lock-order rule exists for (OR-35): within one fleet, stamp order is commit order. Hold the
        // parent row FOR UPDATE in one transaction; a member change in another must WAIT on it, because stamping the parent
        // takes the same row lock before a value is drawn. lock_timeout turns the wait into a refusal (55P03) we can name,
        // instead of a hang.
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(SmDataPostgres.superuser()));
        Boolean refused = tx.execute(status -> {
            superuser.queryForList("SELECT 1 FROM sm_data.fleet_action WHERE tenant_id = :t AND fleet_id = :f FOR UPDATE",
                    fleet.key(), Integer.class);
            var other = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                            SmDataPostgres.runtimeDataSource()));
            return other.execute(inner -> {
                runtime.getJdbcTemplate().execute("SET LOCAL lock_timeout = '300ms'");
                try {
                    updateMember(fleet, 0, "disposition = 'admitted'");
                    return false;
                } catch (RuntimeException e) {
                    org.postgresql.util.PSQLException pg = Refusals.unwrap(e);
                    return pg != null && "55P03".equals(pg.getSQLState());
                }
            });
        });
        assertTrue(Boolean.TRUE.equals(refused), "the member change waited on the parent's row lock (lock_timeout fired)");
        assertFalse(disposition(fleet, 0).equals("admitted"));
    }
}
