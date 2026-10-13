package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 012 T7 - {@code sm_data} V025 ({@code OPS-Q9} Sch-2, {@code ops-501.md} section 6.3): {@code apply_schedule}'s shapes,
 * its forward-only state machine, the window checks, the stamp and the grants. As {@code sm_dp_runtime} unless a test needs
 * the owner to get past a grant, SQL only: what the SERVICE does with these is {@code ApplyScheduleServiceIT}. Every refusal
 * is asserted by SQLSTATE and by the constraint's name or the trigger's own wording, so a refusal that fires for another
 * reason is not a proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyScheduleSchemaIT {

    private static final OffsetDateTime OPENS = OffsetDateTime.parse("2030-01-02T02:00:00Z");
    private static final OffsetDateTime CLOSES = OffsetDateTime.parse("2030-01-02T04:00:00Z");

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private record Live(UUID tenant, UUID instance, UUID placement) {
    }

    private static Live live() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        return new Live(tenant, instance, InventoryFixtures.placement(tenant, instance, target));
    }

    private static MapSqlParameterSource row(Live l, UUID schedule) {
        return new MapSqlParameterSource("t", l.tenant()).addValue("s", schedule).addValue("i", l.instance())
                .addValue("p", l.placement());
    }

    /** The columns every legal row has, with an apply of generation 9 under {@code manual}; callers overwrite what they probe. */
    private static MapSqlParameterSource legal(Live l, UUID schedule) {
        return row(l, schedule).addValue("op", "apply").addValue("gen", 9L).addValue("rbApply", null)
                .addValue("rbGen", null).addValue("strategy", "manual").addValue("confirmed", null)
                .addValue("nb", OPENS).addValue("na", CLOSES).addValue("by", UUID.randomUUID())
                .addValue("at", OffsetDateTime.parse("2030-01-01T00:00:00Z"));
    }

    private static final String INSERT = "INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id,"
            + " placement_id, operation, generation, rollback_apply_id, rollback_generation, strategy,"
            + " outage_confirmed_by, not_before, not_after, requested_by, requested_at)"
            + " VALUES (:t, :s, :i, :p, :op, :gen, :rbApply, :rbGen, :strategy, :confirmed, :nb, :na, :by, :at)";

    private static UUID insert(Live l) {
        UUID schedule = UUID.randomUUID();
        runtime.update(INSERT, legal(l, schedule));
        return schedule;
    }

    private static String state(Live l, UUID schedule) {
        return superuser.queryForObject("SELECT state FROM sm_data.apply_schedule WHERE tenant_id = :t"
                + " AND schedule_id = :s", row(l, schedule), String.class);
    }

    private static long stamp(Live l, UUID schedule) {
        return superuser.queryForObject("SELECT state_version FROM sm_data.apply_schedule WHERE tenant_id = :t"
                + " AND schedule_id = :s", row(l, schedule), Long.class);
    }

    private static final String ADMIT = "UPDATE sm_data.apply_schedule SET state = 'ADMITTED', apply_id = :apply,"
            + " admitted_generation = 9, resolved_at = :at WHERE tenant_id = :t AND schedule_id = :s";

    /** A request the schedule can claim: a REQUESTED apply on this placement. */
    private static UUID anApply(Live l) {
        ApplyRows.insertScope(runtime, l.tenant(), l.instance());
        return ApplyRows.insertRequest(runtime, l.tenant(), l.instance(), l.placement(), "REQUESTED");
    }

    // ---- a schedule is born PENDING and stamped ------------------------------------------------------------------

    @Test
    void aScheduleIsBornPendingWithTheDatabasesStampAndItsResolutionColumnsEmpty() {
        Live l = live();
        UUID schedule = insert(l);

        assertEquals("PENDING", state(l, schedule));
        assertTrue(stamp(l, schedule) > 0, "OR-35: the stamp trigger drew a state_version");
        assertNull(superuser.queryForObject("SELECT apply_id FROM sm_data.apply_schedule WHERE schedule_id = :s",
                row(l, schedule), UUID.class));
    }

    @Test
    void theRuntimeCanNeitherChooseTheStateNorTheStampOnInsert() {
        Live l = live();
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id, placement_id, operation,"
                        + " generation, strategy, not_before, not_after, requested_by, requested_at, state_version)"
                        + " VALUES (:t, :s, :i, :p, 'apply', 9, 'manual', :nb, :na, :by, :at, 5)",
                legal(l, UUID.randomUUID())), "an explicit state_version");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id, placement_id, operation,"
                        + " generation, strategy, not_before, not_after, requested_by, requested_at, state)"
                        + " VALUES (:t, :s, :i, :p, 'apply', 9, 'manual', :nb, :na, :by, :at, 'ADMITTED')",
                legal(l, UUID.randomUUID())), "an explicit state");
    }

    @Test
    void evenTheOwnerCannotInsertAScheduleThatIsNotPending() {
        Live l = live();
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_born_pending", () -> superuser.update(
                "INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id, placement_id, operation,"
                        + " generation, strategy, not_before, not_after, requested_by, requested_at, state,"
                        + " cancelled_by, resolved_at)"
                        + " VALUES (:t, :s, :i, :p, 'apply', 9, 'manual', :nb, :na, :by, :at, 'CANCELLED', :by, :at)",
                legal(l, UUID.randomUUID())));
    }

    @Test
    void anInstanceThatIsNotDeclaredAndAPlacementOfAnotherInstanceAreRefusedByTheForeignKeysName() {
        Live l = live();
        MapSqlParameterSource nobody = legal(l, UUID.randomUUID()).addValue("i", UUID.randomUUID());
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "apply_schedule_has_instance",
                () -> runtime.update(INSERT, nobody));

        Live other = live();
        Live crossed = new Live(l.tenant(), l.instance(), other.placement());   // another tenant's placement id
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "apply_schedule_has_placement",
                () -> runtime.update(INSERT, legal(crossed, UUID.randomUUID())));

        UUID sibling = UUID.randomUUID();
        InventoryFixtures.declare(l.tenant(), sibling);
        UUID siblingPlacement = InventoryFixtures.placement(l.tenant(), sibling, InventoryFixtures.target(l.tenant()));
        Live mixed = new Live(l.tenant(), l.instance(), siblingPlacement);   // SAME tenant, another instance's placement
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "apply_schedule_has_placement",
                () -> runtime.update(INSERT, legal(mixed, UUID.randomUUID())),
                "the foreign key alone would accept it: the stamp trigger holds the placement to THIS instance");
    }

    // ---- the shapes ----------------------------------------------------------------------------------------------

    @Test
    void theShapeChecksRefuseByName() {
        Live l = live();
        UUID applyId = UUID.randomUUID();

        // the window
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_window_ordered", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("na", OPENS)), "notAfter equal to notBefore");
        // what is scheduled
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_names_one_target", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("gen", null)), "an apply with no generation");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_names_one_target", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("rbGen", 5L)), "an apply that also names a rollback target");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_names_one_target", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("op", "rollback").addValue("gen", null)),
                "a rollback with no target");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_names_one_target", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("op", "rollback").addValue("gen", null).addValue("rbApply", applyId)
                        .addValue("rbGen", 5L)), "a rollback with both targets");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_names_one_target", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("op", "restart")), "an operation that is not scheduled");
        // the strategy: 'scheduled' is never stored
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_strategy", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("strategy", "scheduled")), "OR-60: the token is never a row's strategy");
        // the outage confirmation exists exactly for restart-all
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_outage_confirmed", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("strategy", "restart-all")), "restart-all without a confirmation");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_outage_confirmed", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("confirmed", UUID.randomUUID())), "a confirmation on manual");
        // the placement
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "apply_schedule_has_placement", () -> runtime.update(INSERT,
                legal(l, UUID.randomUUID()).addValue("p", UUID.fromString("00000000-0000-0000-0000-000000000000"))),
                "v1 refuses instance-wide apply: the nil sentinel is no placement");

        // the positive controls: the shapes the refusals sit beside are legal
        runtime.update(INSERT, legal(l, UUID.randomUUID()).addValue("strategy", "restart-all")
                .addValue("confirmed", UUID.randomUUID()));
        runtime.update(INSERT, legal(l, UUID.randomUUID()).addValue("op", "rollback").addValue("gen", null)
                .addValue("rbApply", applyId));
        runtime.update(INSERT, legal(l, UUID.randomUUID()).addValue("op", "rollback").addValue("gen", null)
                .addValue("rbGen", 5L).addValue("strategy", "rolling-no-validation"));
    }

    // ---- forward only, once -------------------------------------------------------------------------------------

    @Test
    void aPendingScheduleIsAdmittedWithItsApplyAndTheStampMoves() {
        Live l = live();
        UUID schedule = insert(l);
        long before = stamp(l, schedule);
        UUID apply = anApply(l);

        runtime.update(ADMIT, row(l, schedule).addValue("apply", apply).addValue("at", OPENS.plusMinutes(1)));

        assertEquals("ADMITTED", state(l, schedule));
        assertTrue(stamp(l, schedule) > before, "every move is stamped");
    }

    @Test
    void anAdmissionStampedOutsideTheWindowIsRefusedByTheDatabase() {
        Live l = live();
        UUID schedule = insert(l);
        UUID apply = anApply(l);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_admitted_inside_window", () -> runtime.update(ADMIT,
                row(l, schedule).addValue("apply", apply).addValue("at", OPENS.minusSeconds(1))), "before notBefore");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_admitted_inside_window", () -> runtime.update(ADMIT,
                row(l, schedule).addValue("apply", apply).addValue("at", CLOSES)), "at notAfter: the window is half-open");
        assertEquals("PENDING", state(l, schedule));
        runtime.update(ADMIT, row(l, schedule).addValue("apply", apply).addValue("at", CLOSES.minusNanos(1000)));
        assertEquals("ADMITTED", state(l, schedule), "the last instant inside the window is legal");
    }

    @Test
    void anAdmittedScheduleCarriesW6sRecordOrTheDatabaseRefusesIt() {
        // W3 review W3B-6: an ADMITTED rollback names the target it resolved, and an ADMITTED apply's admitted generation is
        // the generation it scheduled - held by the database, not only by the service.
        Live l = live();
        UUID apply = anApply(l);
        UUID scheduledApply = insert(l);
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_admitted_apply_is_its_generation",
                () -> runtime.update("UPDATE sm_data.apply_schedule SET state = 'ADMITTED', apply_id = :apply,"
                        + " admitted_generation = 8, resolved_at = :at WHERE tenant_id = :t AND schedule_id = :s",
                        row(l, scheduledApply).addValue("apply", apply).addValue("at", OPENS)),
                "admitted generation 8 for a schedule of generation 9");
        assertEquals("PENDING", state(l, scheduledApply));

        UUID rollback = UUID.randomUUID();
        runtime.update(INSERT, legal(l, rollback).addValue("op", "rollback").addValue("gen", null).addValue("rbGen", 5L));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_admitted_rollback_records_target",
                () -> runtime.update(ADMIT, row(l, rollback).addValue("apply", apply).addValue("at", OPENS)),
                "an admitted rollback with no resolved target");
        assertEquals("PENDING", state(l, rollback));
        runtime.update("UPDATE sm_data.apply_schedule SET state = 'ADMITTED', apply_id = :apply, target_generation = 5,"
                + " admitted_generation = 10, resolved_at = :at WHERE tenant_id = :t AND schedule_id = :s",
                row(l, rollback).addValue("apply", apply).addValue("at", OPENS));
        assertEquals("ADMITTED", state(l, rollback), "the positive control: target and minted generation recorded");
    }

    @Test
    void aMissedScheduleIsMissedOnlyAfterItsWindowAndOnlyWithThatReason() {
        Live l = live();
        UUID schedule = insert(l);
        String miss = "UPDATE sm_data.apply_schedule SET state = 'MISSED', reason = :reason, resolved_at = :at"
                + " WHERE tenant_id = :t AND schedule_id = :s";

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_missed_after_window", () -> runtime.update(miss,
                row(l, schedule).addValue("reason", "schedule.window.missed").addValue("at", CLOSES.minusSeconds(1))),
                "missed while the window is open");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_missed_reason", () -> runtime.update(miss,
                row(l, schedule).addValue("reason", "schedule.target.gone").addValue("at", CLOSES)),
                "MISSED with another reason");
        runtime.update(miss, row(l, schedule).addValue("reason", "schedule.window.missed").addValue("at", CLOSES));
        assertEquals("MISSED", state(l, schedule));
    }

    @Test
    void aRefusedScheduleCarriesAReasonFromTheClosedSetAndOnlyThatState() {
        Live l = live();
        UUID schedule = insert(l);
        String refuse = "UPDATE sm_data.apply_schedule SET state = 'REFUSED', reason = :reason, resolved_at = :at"
                + " WHERE tenant_id = :t AND schedule_id = :s";

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_reason", () -> runtime.update(refuse,
                row(l, schedule).addValue("reason", "schedule.made.up").addValue("at", OPENS)), "a reason outside the set");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_state_shape", () -> runtime.update(refuse,
                row(l, schedule).addValue("reason", null).addValue("at", OPENS)), "REFUSED with no reason");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_missed_reason", () -> runtime.update(refuse,
                row(l, schedule).addValue("reason", "schedule.window.missed").addValue("at", OPENS)),
                "REFUSED with MISSED's reason");
        for (String reason : List.of("schedule.authority.revoked", "schedule.target.gone", "schedule.generation.superseded",
                "schedule.generation.not-staged", "schedule.rollback.target-not-eligible")) {
            UUID each = insert(l);
            runtime.update(refuse, row(l, each).addValue("reason", reason).addValue("at", OPENS));
            assertEquals("REFUSED", state(l, each), reason);
        }
    }

    @Test
    void aCancelledScheduleNamesWhoCancelledItAndAdmittedOneNamesItsApply() {
        Live l = live();
        UUID schedule = insert(l);
        String cancel = "UPDATE sm_data.apply_schedule SET state = 'CANCELLED', cancelled_by = :by, resolved_at = :at"
                + " WHERE tenant_id = :t AND schedule_id = :s";
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_state_shape", () -> runtime.update(cancel,
                row(l, schedule).addValue("by", null).addValue("at", OPENS)), "CANCELLED with no canceller");
        runtime.update(cancel, row(l, schedule).addValue("by", UUID.randomUUID()).addValue("at", OPENS));
        assertEquals("CANCELLED", state(l, schedule));

        UUID second = insert(l);
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_schedule_state_shape", () -> runtime.update(
                "UPDATE sm_data.apply_schedule SET state = 'ADMITTED', admitted_generation = 9, resolved_at = :at"
                        + " WHERE tenant_id = :t AND schedule_id = :s", row(l, second).addValue("at", OPENS)),
                "ADMITTED with no apply");
    }

    @Test
    void aScheduleThatLeftPendingNeverChangesAgainAndNoUpdateRewritesTheRequest() {
        Live l = live();
        UUID schedule = insert(l);
        UUID apply = anApply(l);
        runtime.update(ADMIT, row(l, schedule).addValue("apply", apply).addValue("at", OPENS));

        for (String sql : List.of(
                "UPDATE sm_data.apply_schedule SET state = 'CANCELLED', cancelled_by = :by, apply_id = NULL,"
                        + " admitted_generation = NULL, resolved_at = :at WHERE tenant_id = :t AND schedule_id = :s",
                "UPDATE sm_data.apply_schedule SET resolved_at = :at WHERE tenant_id = :t AND schedule_id = :s")) {
            Refusals.assertRaised("never changes again", () -> runtime.update(sql,
                    row(l, schedule).addValue("by", UUID.randomUUID()).addValue("at", OPENS.plusMinutes(5))));
        }

        UUID pending = insert(l);
        Refusals.assertRaised("must move it out of PENDING", () -> runtime.update(
                "UPDATE sm_data.apply_schedule SET resolved_at = NULL WHERE tenant_id = :t AND schedule_id = :s",
                row(l, pending)));
        // the owner passes the column grants, so only the trigger can hold the request itself
        Refusals.assertRaised("may change only how it was resolved", () -> superuser.update(
                "UPDATE sm_data.apply_schedule SET strategy = 'restart-all', outage_confirmed_by = :by,"
                        + " state = 'CANCELLED', cancelled_by = :by, resolved_at = :at"
                        + " WHERE tenant_id = :t AND schedule_id = :s",
                row(l, pending).addValue("by", UUID.randomUUID()).addValue("at", OPENS)));
        Refusals.assertRaised("may change only how it was resolved", () -> superuser.update(
                "UPDATE sm_data.apply_schedule SET not_after = not_after + interval '1 day', state = 'CANCELLED',"
                        + " cancelled_by = :by, resolved_at = :at WHERE tenant_id = :t AND schedule_id = :s",
                row(l, pending).addValue("by", UUID.randomUUID()).addValue("at", OPENS)));
        assertEquals("PENDING", state(l, pending));
    }

    @Test
    void oneApplyIsClaimedByAtMostOneSchedule() {
        Live l = live();
        UUID apply = anApply(l);
        UUID first = insert(l);
        UUID second = insert(l);
        runtime.update(ADMIT, row(l, first).addValue("apply", apply).addValue("at", OPENS));

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "apply_schedule_apply_once",
                () -> runtime.update(ADMIT, row(l, second).addValue("apply", apply).addValue("at", OPENS)));
        assertEquals("PENDING", state(l, second));
    }

    @Test
    void anAdmittedApplyThatDoesNotExistIsRefusedByTheForeignKey() {
        Live l = live();
        UUID schedule = insert(l);
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "apply_schedule_has_apply", () -> runtime.update(ADMIT,
                row(l, schedule).addValue("apply", UUID.randomUUID()).addValue("at", OPENS)));
    }

    // ---- grants -------------------------------------------------------------------------------------------------

    @Test
    void theRuntimeCanUpdateOnlyWhatAResolutionWritesAndCannotDelete() {
        Live l = live();
        UUID schedule = insert(l);
        for (String column : List.of("not_before = not_before", "not_after = not_after", "strategy = strategy",
                "requested_by = requested_by", "operation = operation", "generation = generation",
                "outage_confirmed_by = outage_confirmed_by", "state_version = 5")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_data.apply_schedule SET " + column + " WHERE tenant_id = :t AND schedule_id = :s",
                    row(l, schedule)), column);
        }
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.apply_schedule WHERE tenant_id = :t AND schedule_id = :s", row(l, schedule)),
                "a schedule is history");
    }

    // ---- the readiness stage and the scan's index --------------------------------------------------------------------

    @Test
    void theReadinessQueryFindsBothPartsOfV025() {
        assertEquals(ApplySchedulePersistenceConfiguration.EXPECTED, superuser.getJdbcTemplate().queryForObject(
                ApplySchedulePersistenceConfiguration.READINESS_QUERY, Integer.class));
        // each part counts: a name that matches nothing reads one short
        assertEquals(1, superuser.getJdbcTemplate().queryForObject(
                ApplySchedulePersistenceConfiguration.READINESS_QUERY.replace("'apply_schedule_state_shape'", "'no_such'"),
                Integer.class));
        assertEquals(1, superuser.getJdbcTemplate().queryForObject(
                ApplySchedulePersistenceConfiguration.READINESS_QUERY.replace("table_name = 'apply_schedule'",
                        "table_name = 'no_such'"), Integer.class));
    }

    @Test
    void theFirersScanIsServedByThePartialIndex() {
        List<String> definitions = superuser.getJdbcTemplate().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'sm_data' AND tablename = 'apply_schedule'"
                        + " AND indexname = 'apply_schedule_due'", String.class);
        assertEquals(1, definitions.size());
        assertTrue(definitions.get(0).contains("WHERE (state = 'PENDING'::text)"), definitions.get(0));
    }
}
