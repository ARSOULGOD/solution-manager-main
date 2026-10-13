package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.fleet.FleetAcceptedView;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.api.fleet.FleetValidationException;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.FleetItSupport.Inst;
import com.solace.sm.dp.fleet.FleetRecord;
import com.solace.sm.dp.fleet.FleetService;
import com.solace.sm.dp.fleet.MemberRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 012 T6 over the REAL {@code sm_data} schema (PostgreSQL 14): {@code OPS-Q8} K-2 with two genuine placements of one
 * instance, and the settle delay measured from the real {@code apply_request.terminal_at} the outcome service writes. The
 * pure rules are pinned by {@code FleetBatchSafetyTest}; this class proves what a fake cannot - that {@code terminal_at}
 * is what the delay needs (T6's "re-measure"), that a given-up {@code APPLY_INDETERMINATE} request really has none and is anchored on {@code gave_up_at}, and
 * that a refused fleet leaves no row behind.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetBatchSafetyIT {

    private static final Duration SETTLE = Duration.ofSeconds(30);

    /** A second live placement of {@code inst}'s instance, on a target of its own. */
    private static UUID secondPlacement(FleetItSupport rig, Inst inst) {
        return InventoryFixtures.placement(rig.tenant, inst.instance(), InventoryFixtures.target(rig.tenant));
    }

    private static FleetApplyCommand.Member member(Inst inst, UUID placement) {
        return new FleetApplyCommand.Member(inst.instance(), placement, 1, null);
    }

    private int fleetsOf(FleetItSupport rig) {
        return rig.superuser.queryForObject("SELECT count(*)::int FROM sm_data.fleet_action WHERE tenant_id = :t",
                new MapSqlParameterSource("t", rig.tenant), Integer.class);
    }

    private static final class SettableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private Instant terminalAt(FleetItSupport rig, UUID applyId) {
        OffsetDateTime at = rig.superuser.queryForObject(
                "SELECT terminal_at FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    // ---- K-2 ------------------------------------------------------------------------------------------------------

    @Test
    void aRollingBatchHoldingTwoLivePlacementsOfOneInstanceIsRefusedAndNothingIsWritten() {
        for (String strategy : List.of("rolling-health-gated", "rolling-no-validation")) {
            FleetItSupport rig = new FleetItSupport();
            FleetService fleets = rig.service();
            Inst a = rig.inst();
            UUID second = secondPlacement(rig, a);

            FleetValidationException refused = assertThrows(FleetValidationException.class, () -> fleets.submitFleet(
                    rig.tenant, rig.command(strategy, 2, FleetRecord.CONTINUE, FleetItSupport.ok(a), member(a, second))));

            assertEquals(FleetValidationException.Rule.BATCH_INSTANCE_REPEATED, refused.rule(), strategy);
            assertEquals(1, refused.ordinal(), strategy);
            assertEquals(0, fleetsOf(rig), strategy + ": a refused fleet leaves no fleet row");
            assertEquals(0, rig.requests(), strategy + ": and no request");
        }
    }

    @Test
    void theSameFleetWithBatchSizeOneIsAdmittedAndRunsOnePlacementAtATime() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        UUID second = secondPlacement(rig, a);

        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant, rig.command("rolling-no-validation", 1,
                FleetRecord.CONTINUE, FleetItSupport.ok(a), member(a, second)));
        fleets.advance(rig.tenant, accepted.fleetId());

        assertEquals(MemberRecord.ADMITTED, rig.disposition(accepted.fleetId(), 0));
        assertEquals(MemberRecord.PENDING, rig.disposition(accepted.fleetId(), 1), "the second placement waits its batch");
        assertEquals(1, rig.requestsOf(a.instance()));
    }

    @Test
    void restartAllAdmitsBothPlacementsOfOneInstanceInOneBatchUnderItsConfirmation() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        UUID second = secondPlacement(rig, a);

        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant, new FleetApplyCommand(rig.solution,
                List.of(FleetItSupport.ok(a), member(a, second)), "restart-all", FleetItSupport.ACTOR.toString(), 2,
                FleetRecord.CONTINUE, FleetItSupport.ACTOR, UUID.randomUUID()));
        fleets.advance(rig.tenant, accepted.fleetId());

        assertEquals(MemberRecord.ADMITTED, rig.disposition(accepted.fleetId(), 0));
        assertEquals(MemberRecord.ADMITTED, rig.disposition(accepted.fleetId(), 1));
        assertEquals(2, rig.requestsOf(a.instance()), "both placements restart together, as the confirmation says");
    }

    // ---- the settle delay -----------------------------------------------------------------------------------------

    @Test
    void theNextBatchWaitsTheSettleDelayFromTheRealTerminalAtAcrossASchedulerRestart() {
        FleetItSupport rig = new FleetItSupport();
        SettableClock clock = new SettableClock();
        FleetService fleets = rig.service(rig.rows, rig.realAdmitter(), clock, SETTLE);
        Inst a = rig.inst();
        Inst b = rig.inst();

        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant, rig.command("rolling-no-validation", 1,
                FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b)));
        UUID fleet = accepted.fleetId();
        fleets.advance(rig.tenant, fleet);
        UUID first = rig.member(fleet, 0).applyId();
        rig.drive(first, RequestState.APPLIED);
        Instant terminal = terminalAt(rig, first);
        assertNotNull(terminal, "an APPLIED request carries terminal_at: it is the delay's anchor");

        clock.set(terminal.plusSeconds(29));
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 1), "29 s after terminal_at the second batch has not opened");

        // a new service over the same database is a scheduler restart: nothing but the rows carries the delay
        FleetService restarted = rig.service(rig.rows, rig.realAdmitter(), clock, SETTLE);
        restarted.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 1));

        clock.set(terminal.plusSeconds(30));
        restarted.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1));
    }

    /** {@code apply_reconciliation.gave_up_at}, as the reconciler's own give-up transaction wrote it. */
    private Instant gaveUpAt(FleetItSupport rig, UUID applyId) {
        OffsetDateTime at = rig.superuser.queryForObject(
                "SELECT gave_up_at FROM sm_data.apply_reconciliation WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    @Test
    void aGivenUpIndeterminateMemberHasNoTerminalAtAndAnchorsTheDelayOnGaveUpAt() {
        FleetItSupport rig = new FleetItSupport();
        SettableClock clock = new SettableClock();
        FleetService fleets = rig.service(rig.rows, rig.realAdmitter(), clock, SETTLE);
        Inst a = rig.inst();
        Inst b = rig.inst();

        UUID fleet = fleets.submitFleet(rig.tenant, rig.command("rolling-no-validation", 1, FleetRecord.CONTINUE,
                FleetItSupport.ok(a), FleetItSupport.ok(b))).fleetId();
        fleets.advance(rig.tenant, fleet);
        UUID first = rig.member(fleet, 0).applyId();
        rig.drive(first, RequestState.APPLY_INDETERMINATE);
        // the REAL give-up path (W4 review W4B-1): classifier on the request and gave_up_at on its bookkeeping row, in one
        // transaction - not a raw UPDATE of reconcile_class, which writes no gave_up_at. The rig's OutcomeService wires
        // ReconciliationLedger.none() (writes no bookkeeping), so the give-up goes through one over the real ledger.
        assertTrue(ApplyEngineHarness.outcomesWithLedger().recordReconcileGiveUp(new TenantId(rig.tenant), new ApplyId(first),
                new ErrorClass("reconcile.foreign.content")));
        assertNull(terminalAt(rig, first), "terminal_has_timestamp: APPLY_INDETERMINATE has no terminal_at");
        Instant gaveUp = gaveUpAt(rig, first);
        assertNotNull(gaveUp, "the give-up persisted its instant");

        clock.set(gaveUp.plusSeconds(29));
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.FAILED, rig.member(fleet, 0).settled());
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 1), "29 s after the give-up the next batch has not opened");

        // a scheduler restart: nothing but the rows carries the anchor
        FleetService restarted = rig.service(rig.rows, rig.realAdmitter(), clock, SETTLE);
        clock.set(gaveUp.plusSeconds(30));
        restarted.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1));
    }

    @Test
    void aVerifyMismatchOnMemberTwoOfThreeSkipsMemberThreeWithoutRollbackOrWait() {
        FleetItSupport rig = new FleetItSupport();
        SettableClock clock = new SettableClock();
        FleetService fleets = rig.service(rig.rows, rig.realAdmitter(), clock, SETTLE);
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();

        UUID fleet = fleets.submitFleet(rig.tenant, rig.command("rolling-no-validation", 1, FleetRecord.HALT_REMAINING,
                FleetItSupport.ok(a), FleetItSupport.ok(b), FleetItSupport.ok(c))).fleetId();
        fleets.advance(rig.tenant, fleet);
        rig.drive(rig.member(fleet, 0).applyId(), RequestState.APPLIED);
        clock.set(terminalAt(rig, rig.member(fleet, 0).applyId()).plusSeconds(30));
        fleets.advance(rig.tenant, fleet);
        rig.drive(rig.member(fleet, 1).applyId(), RequestState.CONFIG_DIVERGED);

        fleets.advance(rig.tenant, fleet);

        assertEquals(MemberRecord.SKIPPED, rig.disposition(fleet, 2));
        assertEquals(FleetRecord.HALTED, rig.fleetState(fleet));
        assertEquals(2, rig.requests(), "member 3 has no request and the diverged member was not rolled back");
        assertEquals(RequestState.CONFIG_DIVERGED.name(), rig.requestState(rig.member(fleet, 1).applyId()));
    }
}
