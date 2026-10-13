package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.fleet.FleetAcceptedView;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.api.fleet.FleetApplyCommand.RefusalCode;
import com.solace.sm.dp.api.fleet.FleetConflictException;
import com.solace.sm.dp.api.fleet.FleetMemberView;
import com.solace.sm.dp.api.fleet.FleetObservationView;
import com.solace.sm.dp.api.fleet.FleetValidationException;
import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.engine.GenerationBinding;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.FleetItSupport.Inst;
import com.solace.sm.dp.apply.persistence.FleetItSupport.SimulatedCrash;
import com.solace.sm.dp.fleet.FleetRecord;
import com.solace.sm.dp.fleet.FleetScheduler;
import com.solace.sm.dp.fleet.FleetService;
import com.solace.sm.dp.fleet.MemberRecord;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T11.2 (REQ-013, OR-47; {@code fleet-apply.md}) at the data plane: a fleet is a DP-owned record over ordinary
 * apply requests, advanced batch by batch by ONE scheduler thread. Over a real PostgreSQL, a real {@code ApplyExecutor}
 * (admission, outcomes, intents) and the real {@code fleet_action} / {@code fleet_member} tables, as
 * {@code sm_dp_runtime}. The host is the one thing not here: a fleet admits and observes, and the durable worker is what
 * executes, so a request's outcome is walked by the real outcome service instead.
 *
 * <p>Lives in this package for the engine harness and the shared container it uses (the same reason {@code HealthPollerIT}
 * tests another package's poller from here). Every assertion names only its own tenant.
 */
@Testcontainers(disabledWithoutDocker = true)
class FleetServiceIT {

    private static TenantEnumerationRepository tenantSource;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        tenantSource = new TenantEnumerationRepository(SmDataPostgres.runtimeJdbc());
    }

    // ---- REQ-013 bullet 2: one request, journal and audit line per member; one fleet id traces them all ----------

    @Test
    void everyMemberHasItsOwnApplyIdJournalAndAuditLineAndTheFleetIdTracesThemAll() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();

        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant,
                rig.manual(3, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b), FleetItSupport.ok(c)));
        fleets.advance(rig.tenant, accepted.fleetId());

        List<MemberRecord> members = rig.members(accepted.fleetId());
        assertEquals(3, members.size());
        for (MemberRecord member : members) {
            assertEquals(MemberRecord.ADMITTED, member.disposition());
            // fleet -> request: the pre-minted id names the member's own request
            assertTrue(rig.requestExists(member.applyId()), "member " + member.ordinal() + " has its own request");
            assertEquals("REQUESTED", rig.requestState(member.applyId()));
            // the audit line IS that row (D10 approval 1.4): the submitting principal, never a display name
            assertEquals(FleetItSupport.ACTOR.toString(), rig.superuser.queryForObject(
                    "SELECT requested_by FROM sm_data.apply_request WHERE apply_id = :a",
                    new MapSqlParameterSource("a", member.applyId()), String.class));
            // request -> fleet: one indexed lookup, and a request belongs to at most one fleet
            assertEquals(accepted.fleetId(), rig.superuser.queryForObject(
                    "SELECT fleet_id FROM sm_data.fleet_member WHERE tenant_id = :t AND apply_id = :a",
                    new MapSqlParameterSource().addValue("t", rig.tenant).addValue("a", member.applyId()),
                    UUID.class));
        }
        assertEquals(3, members.stream().map(MemberRecord::applyId).distinct().count(), "three distinct requests");
        assertEquals(3, rig.requests(), "exactly one request per member, and nothing else");
        // a request belongs to at most one fleet: a second fleet naming one of these ids is refused by the database
        UUID otherFleet = UUID.randomUUID();
        rig.superuser.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy,"
                        + " batch_size, on_failure, state, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, :f, :c, 'manual', 1, 'continue', 'RUNNING', 'x', now(), :f)",
                new MapSqlParameterSource().addValue("t", rig.tenant).addValue("f", otherFleet)
                        .addValue("c", rig.solution));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "fleet_member_request_once", () ->
                rig.superuser.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch,"
                                + " instance_id, placement_id, generation, apply_id, disposition) VALUES (:t, :f, 0,"
                                + " 0, :i, :p, 1, :a, 'pending')", new MapSqlParameterSource().addValue("t", rig.tenant)
                                .addValue("f", otherFleet).addValue("i", UUID.randomUUID())
                                .addValue("p", UUID.randomUUID()).addValue("a", members.get(0).applyId())));
    }

    // ---- REQ-013 bullet 3, onFailure --------------------------------------------------------------------------------

    @Test
    void oneFailingMemberNeitherStopsNorHidesTheOthersUnderContinue() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b), FleetItSupport.ok(c)));
        UUID fleet = accepted.fleetId();

        fleets.advance(rig.tenant, fleet);                                     // batch 0: a
        rig.drive(rig.member(fleet, 0).applyId(), RequestState.FAILED_ROLLED_BACK);
        fleets.advance(rig.tenant, fleet);                                     // a failed; batch 1: b
        assertEquals(MemberRecord.FAILED, rig.member(fleet, 0).settled());
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1), "a failure did not stop the next batch");
        rig.drive(rig.member(fleet, 1).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, fleet);                                     // b succeeded; batch 2: c
        rig.drive(rig.member(fleet, 2).applyId(), RequestState.STAGED);
        fleets.advance(rig.tenant, fleet);                                     // c succeeded; the fleet lands

        assertEquals(FleetRecord.COMPLETED_WITH_FAILURES, rig.fleetState(fleet));
        FleetObservationView seen = fleets.observeFleet(rig.tenant, fleet).orElseThrow();
        assertEquals(List.of("failed", "succeeded", "succeeded"),
                seen.members().stream().map(FleetMemberView::settled).toList(),
                "nothing is hidden: the failure is on its member, and the others ran");
        assertTrue(seen.members().stream().allMatch(m -> m.disposition().equals("admitted")));
        assertEquals(3, rig.requests());
        assertTrue(seen.terminalAt() != null, "terminal_at is set exactly when the fleet leaves RUNNING");
    }

    @Test
    void haltRemainingStopsOnlyUnadmittedMembers() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        Inst d = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(2, FleetRecord.HALT_REMAINING, FleetItSupport.ok(a),
                FleetItSupport.ok(b), FleetItSupport.ok(c), FleetItSupport.ok(d))).fleetId();

        fleets.advance(rig.tenant, fleet);                                     // batch 0: a and b admitted
        UUID aRequest = rig.member(fleet, 0).applyId();
        UUID bRequest = rig.member(fleet, 1).applyId();
        rig.drive(aRequest, RequestState.FAILED_ROLLED_BACK);                  // a fails; b is still in flight
        fleets.advance(rig.tenant, fleet);

        assertEquals(MemberRecord.SKIPPED, rig.disposition(fleet, 2), "a later, unadmitted member is stopped");
        assertEquals(MemberRecord.SKIPPED, rig.disposition(fleet, 3));
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1), "an admitted member is NOT stopped");
        assertEquals("REQUESTED", rig.requestState(bRequest), "b's request is exactly as it was: nothing touched it");
        assertEquals(FleetRecord.RUNNING, rig.fleetState(fleet), "the fleet waits for the batch it already opened");
        assertFalse(rig.requestExists(rig.member(fleet, 2).applyId()),
                "a skipped member never gets a request: its pre-minted id stays a reservation that names nothing");
        assertFalse(rig.requestExists(rig.member(fleet, 3).applyId()));
        assertEquals(2, rig.requests(), "only the two admitted members have requests");

        rig.drive(bRequest, RequestState.APPLIED);
        fleets.advance(rig.tenant, fleet);
        assertEquals(FleetRecord.HALTED, rig.fleetState(fleet), "the stop is visible: skipped members, and HALTED");
        assertEquals(2, rig.requests());
    }

    /**
     * OR-108 (4): {@code HALTED} means a member was SKIPPED. Under {@code halt-remaining}, a failure in the LAST batch has
     * nothing later to skip, so nothing was halted and the fleet lands {@code COMPLETED_WITH_FAILURES}, exactly as under
     * {@code continue} (fleet-apply.md section 5.2 item 4).
     */
    @Test
    void aHaltRemainingFleetWhoseLastBatchFailsLandsCompletedWithFailuresNotHalted() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.HALT_REMAINING, FleetItSupport.ok(a),
                FleetItSupport.ok(b))).fleetId();

        fleets.advance(rig.tenant, fleet);                                     // batch 0: a
        rig.drive(rig.member(fleet, 0).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, fleet);                                     // a succeeded; batch 1 (the last): b
        rig.drive(rig.member(fleet, 1).applyId(), RequestState.FAILED_ROLLED_BACK);
        fleets.advance(rig.tenant, fleet);                                     // b failed; nothing is left to skip

        assertEquals(FleetRecord.COMPLETED_WITH_FAILURES, rig.fleetState(fleet),
                "a failure with no later batch skipped nothing, so nothing was halted");
        FleetObservationView seen = fleets.observeFleet(rig.tenant, fleet).orElseThrow();
        assertEquals(List.of("succeeded", "failed"), seen.members().stream().map(FleetMemberView::settled).toList());
        assertTrue(seen.members().stream().noneMatch(m -> m.disposition().equals(MemberRecord.SKIPPED)));
    }

    @Test
    void anAdmissionRefusalDoesNotStopItsSameBatchSiblingsAndSettlesFailed() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(3, FleetRecord.HALT_REMAINING, FleetItSupport.ok(a),
                FleetItSupport.ok(b), FleetItSupport.ok(c))).fleetId();
        // a single apply holds b's placement when the batch opens (Q-F3: a fleet reservation does not block it)
        UUID busy = UUID.randomUUID();
        assertInstanceOf(AdmissionResult.Admitted.class, rig.executor.admit(new ApplyCommand(
                new ScopeKey(new TenantId(rig.tenant), new InstanceId(b.instance()), new PlacementId(b.placement())),
                Operation.APPLY, 1L, Strategy.MANUAL, null, "someone-else"), new ApplyId(busy)));

        fleets.advance(rig.tenant, fleet);

        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 0));
        MemberRecord refused = rig.member(fleet, 1);
        assertEquals(MemberRecord.NOT_ADMITTED, refused.disposition());
        assertEquals(RefusalReason.SCOPE_BUSY.name(), refused.admissionRefusal());
        assertEquals(MemberRecord.FAILED, refused.settled(), "a refused admission settles failed (5.3)");
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 2),
                "Q-F9 (a): halt-remaining acts between batches, so a same-batch sibling is still admitted");
        assertFalse(rig.requestExists(refused.applyId()), "the pre-minted id of a refused member names nothing");
    }

    @Test
    void aSiblingLeftPendingByAnExceptionIsStillAdmittedAfterAnotherMemberWasRefused() {
        // Q-F9 (a): halt-remaining acts BETWEEN batches. A member refused at admission settles failed, but a same-batch
        // sibling that was left pending (the process died mid-pass, or admission threw for it) is admitted on the next
        // tick all the same: "the outcome is the same with or without the crash"
        FleetItSupport rig = new FleetItSupport();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        AtomicBoolean explode = new AtomicBoolean(true);
        FleetService.Admitter flaky = (command, id) -> {
            if (explode.get() && command.scope().instance().value().equals(c.instance())) {
                throw new IllegalStateException("the process died here, say");
            }
            return rig.realAdmitter().admit(command, id);
        };
        FleetService fleets = rig.service(rig.rows, flaky);
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(3, FleetRecord.HALT_REMAINING, FleetItSupport.ok(a),
                FleetItSupport.ok(b), FleetItSupport.ok(c))).fleetId();
        assertInstanceOf(AdmissionResult.Admitted.class, rig.executor.admit(new ApplyCommand(
                new ScopeKey(new TenantId(rig.tenant), new InstanceId(b.instance()), new PlacementId(b.placement())),
                Operation.APPLY, 1L, Strategy.MANUAL, null, "someone-else"), new ApplyId(UUID.randomUUID())));

        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.NOT_ADMITTED, rig.disposition(fleet, 1));
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 2), "left pending");

        explode.set(false);
        fleets.advance(rig.tenant, fleet);

        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 2),
                "a failure in its batch does not skip a same-batch sibling: only LATER batches are skipped");
    }

    // ---- ordering ---------------------------------------------------------------------------------------------

    @Test
    void theNextBatchWaitsForTheCurrentOneToBeSettled() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b))).fleetId();

        fleets.advance(rig.tenant, fleet);
        UUID first = rig.member(fleet, 0).applyId();
        for (int i = 0; i < 3; i++) {
            fleets.advance(rig.tenant, fleet);
        }
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 1), "REQUESTED: batch 1 is not opened");
        assertEquals(1, rig.requests());

        rig.drive(first, RequestState.RUNNING);
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 1), "RUNNING: batch 1 is not opened");

        // an APPLY_INDETERMINATE member is not terminal and, until the reconciler gives up, not settled: the fleet
        // would otherwise advance past a host whose state is unknown
        rig.outcomes.land(new TenantId(rig.tenant), new ApplyId(first), RequestState.APPLY_INDETERMINATE);
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 1), "indeterminate: batch 1 still waits");
        assertNull(rig.member(fleet, 0).settled());

        // once the reconciler gives up it is settled failed - but its request is not terminal and still blocks its scope
        rig.giveUp(first);
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.FAILED, rig.member(fleet, 0).settled());
        assertFalse(RequestState.valueOf(rig.requestState(first)).isTerminal(),
                "settled is not terminal: the one case where the two differ");
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1), "and batch 1 opens");
    }

    @Test
    void membersOfOneBatchAreAdmittedTogetherAndTheBatchOpensOnlyOnceItsPredecessorSettles() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(2, FleetRecord.CONTINUE, FleetItSupport.ok(a),
                FleetItSupport.ok(b), FleetItSupport.ok(c))).fleetId();

        fleets.advance(rig.tenant, fleet);

        assertEquals(List.of(0, 0, 1), rig.members(fleet).stream().map(MemberRecord::batch).toList(),
                "member k is in batch k / batchSize");
        assertEquals(List.of("admitted", "admitted", "pending"),
                rig.members(fleet).stream().map(MemberRecord::disposition).toList(),
                "all of ONE batch, and none of the next: admitting every batch at once is the defect this pins");
        assertEquals(2, rig.requests());
    }

    @Test
    void aFleetWithNoMemberInFlightStillAdvances() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b))).fleetId();
        fleets.advance(rig.tenant, fleet);
        rig.drive(rig.member(fleet, 0).applyId(), RequestState.APPLIED);
        assertFalse(tenantSource.tenantsWithNonTerminalWork().contains(new TenantId(rig.tenant)),
                "B4: between batches this tenant has NO request in flight, so OR-44's list would never yield it");
        assertTrue(tenantSource.tenantsWithActiveInstances().contains(new TenantId(rig.tenant)),
                "...while the second list (OR-49) does, because its instances are active");

        FleetScheduler scheduler = new FleetScheduler(() -> true, tenantSource, fleets,
                new WorkerSingletonGuard(SmDataPostgres.runtimeDataSource()), new com.solace.sm.dp.fleet.FleetProperties(
                        true, Duration.ofSeconds(1), null));
        scheduler.pollOnce();

        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1), "the scheduler opened batch 1");
        assertEquals(2, rig.requests());
    }

    @Test
    void theFleetSchedulerReadsNoTenantOutsideItsSource() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        // A tenant with a running fleet and NOT ONE active instance: it is outside the second list, so the scheduler must
        // never see it (a scan of fleet_action across tenants would be a THIRD cross-tenant read, which needs an owner
        // ruling). Written as the superuser: the service refuses to create such a fleet.
        UUID stranger = UUID.randomUUID();
        UUID fleet = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        rig.superuser.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy,"
                        + " batch_size, on_failure, state, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, :f, :c, 'manual', 1, 'continue', 'RUNNING', 'x', now(), :f)",
                new MapSqlParameterSource().addValue("t", stranger).addValue("f", fleet).addValue("c", UUID.randomUUID()));
        rig.superuser.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id,"
                        + " placement_id, generation, apply_id, disposition) VALUES (:t, :f, 0, 0, :i, :p, 1, :a,"
                        + " 'pending')",
                new MapSqlParameterSource().addValue("t", stranger).addValue("f", fleet).addValue("i", UUID.randomUUID())
                        .addValue("p", UUID.randomUUID()).addValue("a", member));
        // and a tenant that IS in the source, with its own fleet
        Inst a = rig.inst();
        UUID mine = fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a))).fleetId();
        assertFalse(tenantSource.tenantsWithActiveInstances().contains(new TenantId(stranger)));

        new FleetScheduler(() -> true, tenantSource, fleets, new WorkerSingletonGuard(SmDataPostgres.runtimeDataSource()),
                new com.solace.sm.dp.fleet.FleetProperties(true, Duration.ofSeconds(1), null)).pollOnce();

        assertEquals(MemberRecord.ADMITTED, rig.disposition(mine, 0), "a tenant in the source is advanced");
        assertEquals("pending", rig.superuser.queryForObject("SELECT disposition FROM sm_data.fleet_member"
                + " WHERE tenant_id = :t AND fleet_id = :f", new MapSqlParameterSource().addValue("t", stranger)
                .addValue("f", fleet), String.class), "a tenant outside it is never read, let alone advanced");
        assertEquals(List.of(), fleets.runningFleets(UUID.randomUUID()), "an unknown tenant has no running fleets");
        assertEquals(List.of(mine), fleets.runningFleets(rig.tenant), "runningFleets is scoped to ONE tenant");
    }

    // ---- authorization's two outcomes: what the DP stores -------------------------------------------------------

    @Test
    void aRefusedMemberIsStoredUnvalidatedAndNeverReadByTheDataPlaneAndStaysOutOfTheVerdict() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        FleetApplyCommand.Member hidden = new FleetApplyCommand.Member(UUID.randomUUID(), UUID.randomUUID(), 9,
                RefusalCode.NOT_FOUND);
        FleetApplyCommand.Member forbidden = new FleetApplyCommand.Member(UUID.randomUUID(), UUID.randomUUID(), 4,
                RefusalCode.FORBIDDEN);

        // member 0 and 2 name instances the DP has never heard of: if it read them, it would answer 422
        FleetAcceptedView accepted = assertDoesNotThrow(() -> fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, hidden, FleetItSupport.ok(a), forbidden)));

        assertEquals(List.of("refused", "pending", "refused"),
                accepted.members().stream().map(FleetMemberView::disposition).toList());
        FleetMemberView h = accepted.members().get(0);
        assertEquals("NOT_FOUND", h.refusalCode());
        assertNull(h.batch(), "a refused member is in no batch");
        assertNull(h.applyId(), "and has no request id");
        assertEquals(hidden.instanceId(), h.instanceId(), "the caller-named, unvalidated ids are what is stored");
        assertEquals("FORBIDDEN", accepted.members().get(2).refusalCode());

        fleets.advance(rig.tenant, accepted.fleetId());
        rig.drive(rig.member(accepted.fleetId(), 1).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, accepted.fleetId());
        assertEquals(FleetRecord.SUCCEEDED, rig.fleetState(accepted.fleetId()),
                "refused members are reported, and are not in the verdict");
        assertEquals(1, rig.requests(), "nothing was applied for a refused member");
    }

    @Test
    void aMemberDataPlaneCheckNeverRunsOnARefusedMember() {
        // The gateway authorizes every member BEFORE the data plane is called (its own test pins the order). This is the
        // data plane's half: a refused member is never read, so no 422 can confirm that a hidden instance exists.
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        FleetApplyCommand.Member hiddenWithNoGeneration = new FleetApplyCommand.Member(UUID.randomUUID(),
                UUID.randomUUID(), 99, RefusalCode.NOT_FOUND);

        assertDoesNotThrow(() -> fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.CONTINUE,
                FleetItSupport.ok(a), hiddenWithNoGeneration)));
    }

    // ---- section 5.1: whole-action validation, on authorized members only ---------------------------------------------

    @Test
    void anAuthorizedMemberThatFailsASemanticCheckRefusesTheWholeFleetAndNothingIsWritten() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst good = rig.inst();
        Inst otherSolution = rig.inst();
        rig.superuser.update("UPDATE sm_data.instance_state SET catalog_solution_id = :c WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("c", UUID.randomUUID())
                .addValue("t", rig.tenant).addValue("i", otherSolution.instance()));
        UUID pendingInstance = UUID.randomUUID();
        InventoryFixtures.declare(rig.tenant, pendingInstance, "pending");   // declared, not yet registered by the CP
        Inst pending = new Inst(pendingInstance,
                InventoryFixtures.placement(rig.tenant, pendingInstance, InventoryFixtures.target(rig.tenant)));
        rig.generation(pendingInstance, 1);
        Inst noGeneration = rig.inst();
        UUID foreignPlacement = rig.inst().placement();

        assertValidation(fleets, rig, 1, FleetValidationException.Rule.INSTANCE_NOT_ELIGIBLE, good,
                FleetItSupport.ok(otherSolution));
        assertValidation(fleets, rig, 1, FleetValidationException.Rule.INSTANCE_NOT_ELIGIBLE, good,
                FleetItSupport.ok(pending));
        assertValidation(fleets, rig, 1, FleetValidationException.Rule.INSTANCE_NOT_ELIGIBLE, good,
                new FleetApplyCommand.Member(UUID.randomUUID(), UUID.randomUUID(), 1, null));
        assertValidation(fleets, rig, 1, FleetValidationException.Rule.PLACEMENT_NOT_LIVE, good,
                new FleetApplyCommand.Member(noGeneration.instance(), foreignPlacement, 1, null));
        assertValidation(fleets, rig, 1, FleetValidationException.Rule.GENERATION_NOT_STAGED, good,
                new FleetApplyCommand.Member(noGeneration.instance(), noGeneration.placement(), 2, null));
        assertValidation(fleets, rig, 1, FleetValidationException.Rule.DUPLICATE_MEMBER, good,
                FleetItSupport.ok(good));
        assertEquals(0, rig.superuser.queryForObject("SELECT count(*)::int FROM sm_data.fleet_action WHERE tenant_id = :t",
                new MapSqlParameterSource("t", rig.tenant), Integer.class), "whole-action: nothing was written");
        assertEquals(0, rig.requests());
    }

    private static void assertValidation(FleetService fleets, FleetItSupport rig, int ordinal,
            FleetValidationException.Rule rule, Inst first, FleetApplyCommand.Member second) {
        FleetValidationException failure = assertThrows(FleetValidationException.class, () -> fleets.submitFleet(
                rig.tenant, rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(first), second)), rule.name());
        assertEquals(ordinal, failure.ordinal());
        assertEquals(rule, failure.rule());
        assertFalse(failure.getMessage().contains(second.instanceId().toString()), "no value from the body is echoed");
    }

    @Test
    void onlyScheduledIsStillNotImplementedAndWritesNothing() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        assertThrows(NotImplementedStrategyException.class, () -> fleets.submitFleet(rig.tenant,
                new FleetApplyCommand(rig.solution, List.of(FleetItSupport.ok(a)), "scheduled", null, 1,
                        FleetRecord.CONTINUE, FleetItSupport.ACTOR, UUID.randomUUID())), "scheduled is plan 012 T7's");
        assertThrows(IllegalArgumentException.class, () -> fleets.submitFleet(rig.tenant, rig.command("yolo", 1,
                FleetRecord.CONTINUE, FleetItSupport.ok(a))));
        assertThrows(IllegalArgumentException.class, () -> fleets.submitFleet(rig.tenant, rig.manual(1, "sometimes",
                FleetItSupport.ok(a))));
        assertEquals(0, rig.superuser.queryForObject("SELECT count(*)::int FROM sm_data.fleet_action WHERE tenant_id = :t",
                new MapSqlParameterSource("t", rig.tenant), Integer.class));
    }

    @Test
    void theTwoUngatedStrategiesAreServedAndRestartAllStoresTheConfirmingPrincipalOnTheFleetAndEveryMember() {
        // Plan 012 T2 (OPS-Q7 C-2): the principal the gateway wrote is on fleet_action AND on each member's request.
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();

        FleetAcceptedView all = fleets.submitFleet(rig.tenant, new FleetApplyCommand(rig.solution,
                List.of(FleetItSupport.ok(a), FleetItSupport.ok(b)), "restart-all", FleetItSupport.ACTOR.toString(), 2,
                FleetRecord.CONTINUE, FleetItSupport.ACTOR, UUID.randomUUID()));
        assertEquals(FleetItSupport.ACTOR.toString(), rig.superuser.queryForObject(
                "SELECT outage_confirmed_by FROM sm_data.fleet_action WHERE tenant_id = :t AND fleet_id = :f",
                new MapSqlParameterSource().addValue("t", rig.tenant).addValue("f", all.fleetId()), String.class));
        fleets.advance(rig.tenant, all.fleetId());
        for (MemberRecord member : rig.members(all.fleetId())) {
            assertEquals(MemberRecord.ADMITTED, member.disposition());
            assertEquals("restart-all", rig.superuser.queryForObject(
                    "SELECT strategy FROM sm_data.apply_request WHERE apply_id = :a",
                    new MapSqlParameterSource("a", member.applyId()), String.class));
            assertEquals(FleetItSupport.ACTOR.toString(), rig.superuser.queryForObject(
                    "SELECT outage_confirmed_by FROM sm_data.apply_request WHERE apply_id = :a",
                    new MapSqlParameterSource("a", member.applyId()), String.class),
                    "each member's request carries the same confirming principal");
        }

        Inst c = rig.inst();
        FleetAcceptedView noValidation = fleets.submitFleet(rig.tenant, rig.command("rolling-no-validation", 1,
                FleetRecord.CONTINUE, FleetItSupport.ok(c)));
        assertNull(rig.superuser.queryForObject(
                "SELECT outage_confirmed_by FROM sm_data.fleet_action WHERE tenant_id = :t AND fleet_id = :f",
                new MapSqlParameterSource().addValue("t", rig.tenant).addValue("f", noValidation.fleetId()), String.class),
                "rolling-no-validation asserts no outage");
    }

    // ---- section 5.6: a fleet and other fleets ------------------------------------------------------------------------

    @Test
    void aSecondRunningFleetOnAReservedPlacementIsRefused409() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        FleetAcceptedView first = fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b)));

        // member 1 of the second fleet is b: reserved by the first, which is still RUNNING
        FleetConflictException conflict = assertThrows(FleetConflictException.class, () -> fleets.submitFleet(
                rig.tenant, rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(c), FleetItSupport.ok(b))));
        assertEquals(1, conflict.ordinal(), "names the member's ordinal, never the other fleet's id");
        assertFalse(conflict.getMessage().contains(first.fleetId().toString()));
        assertEquals(1, rig.superuser.queryForObject("SELECT count(*)::int FROM sm_data.fleet_action WHERE tenant_id = :t",
                new MapSqlParameterSource("t", rig.tenant), Integer.class),
                "the whole second submit rolled back: not even its first, free member is written");

        // a double-clicked submit is the same refusal, and harmless
        assertThrows(FleetConflictException.class, () -> fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b))));

        // once the first fleet's members have settled the placements are free again
        rig.generation(a.instance(), 2);
        fleets.advance(rig.tenant, first.fleetId());
        rig.drive(rig.member(first.fleetId(), 0).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, first.fleetId());
        rig.drive(rig.member(first.fleetId(), 1).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, first.fleetId());
        assertEquals(FleetRecord.SUCCEEDED, rig.fleetState(first.fleetId()));
        assertDoesNotThrow(() -> fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.CONTINUE,
                new FleetApplyCommand.Member(a.instance(), a.placement(), 2, null))));
    }

    // ---- section 5.3: a settlement is written once ------------------------------------------------------------------

    @Test
    void aSettledMemberNeverChangesItsSettlement() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b))).fleetId();
        fleets.advance(rig.tenant, fleet);
        UUID first = rig.member(fleet, 0).applyId();
        rig.drive(first, RequestState.STAGED);
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.SUCCEEDED, rig.member(fleet, 0).settled(), "STAGED is the manual strategy's success");

        // a LATER apply supersedes the staged request: the fleet's verdict on it must not flip
        rig.generation(a.instance(), 2);
        assertInstanceOf(AdmissionResult.Admitted.class, rig.executor.admit(new ApplyCommand(
                new ScopeKey(new TenantId(rig.tenant), new InstanceId(a.instance()), new PlacementId(a.placement())),
                Operation.APPLY, 2L, Strategy.MANUAL, null, "someone-else"), GenerationBinding.MUST_BE_STAGED));
        assertEquals("SUPERSEDED", rig.requestState(first));
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.SUCCEEDED, rig.member(fleet, 0).settled(), "written once, never revisited");

        // and the database refuses the rewrite from any other writer
        Refusals.assertRaised("settlement", () -> rig.runtime.update("UPDATE sm_data.fleet_member SET settled = 'failed'"
                + " WHERE tenant_id = :t AND fleet_id = :f AND ordinal = 0", new MapSqlParameterSource()
                .addValue("t", rig.tenant).addValue("f", fleet)));
    }

    // ---- section 7: what a member shows -----------------------------------------------------------------------------

    @Test
    void aMemberShowsNoApplyIdUntilItIsAdmitted() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        Inst c = rig.inst();
        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.HALT_REMAINING,
                FleetItSupport.ok(a), FleetItSupport.ok(b), FleetItSupport.ok(c)));
        assertTrue(accepted.members().stream().allMatch(m -> m.applyId() == null && m.requestState() == null
                && m.requestStateVersion() == null && m.disposition().equals("pending")),
                "the 202 reports every authorized member as pending, with no id: it names no request yet");
        assertEquals("RUNNING", accepted.state());
        assertEquals(0, fleets.observeFleet(rig.tenant, accepted.fleetId()).orElseThrow().currentBatch());

        fleets.advance(rig.tenant, accepted.fleetId());
        FleetObservationView seen = fleets.observeFleet(rig.tenant, accepted.fleetId()).orElseThrow();
        FleetMemberView admitted = seen.members().get(0);
        assertEquals("admitted", admitted.disposition());
        assertEquals(rig.member(accepted.fleetId(), 0).applyId(), admitted.applyId(), "the pre-minted id, now real");
        assertEquals("REQUESTED", admitted.requestState());
        assertTrue(admitted.requestStateVersion() != null && admitted.requestStateVersion() > 0);
        assertNull(seen.members().get(1).applyId(), "a pending member of a later batch still shows none");

        rig.drive(rig.member(accepted.fleetId(), 0).applyId(), RequestState.FAILED_NO_EFFECT);
        fleets.advance(rig.tenant, accepted.fleetId());          // halt-remaining: b and c skipped
        for (FleetMemberView m : fleets.observeFleet(rig.tenant, accepted.fleetId()).orElseThrow().members()
                .subList(1, 3)) {
            assertEquals("skipped", m.disposition());
            assertNull(m.applyId(), "a skipped member never will name a request, so none is shown");
            assertNull(m.requestState());
        }
    }

    @Test
    void anAbsentOrForeignFleetIsAnEmptyObservation() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a))).fleetId();

        assertTrue(fleets.observeFleet(rig.tenant, UUID.randomUUID()).isEmpty());
        assertTrue(fleets.observeFleet(UUID.randomUUID(), fleet).isEmpty(), "another organization's fleet: indistinguishable");
        assertEquals(fleet, fleets.observeFleet(rig.tenant, fleet).orElseThrow().fleetId());
    }

    // ---- section 4.3, OR-35: the stamp ------------------------------------------------------------------------------

    @Test
    void aFleetTransitionStampsFleetAction() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();
        UUID fleet = fleets.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b))).fleetId();
        long submitted = rig.fleetStamp(fleet);
        assertTrue(submitted > 0, "stamped on insert from the one sequence");

        fleets.advance(rig.tenant, fleet);                                   // a admitted
        long afterAdmission = rig.fleetStamp(fleet);
        assertTrue(afterAdmission > submitted, "a MEMBER change stamps the parent: no change goes unstamped");

        rig.drive(rig.member(fleet, 0).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, fleet);                                   // a settled, batch 1 opened, b admitted
        long afterSettlement = rig.fleetStamp(fleet);
        assertTrue(afterSettlement > afterAdmission);

        rig.drive(rig.member(fleet, 1).applyId(), RequestState.APPLIED);
        fleets.advance(rig.tenant, fleet);                                   // b settled, the fleet lands
        assertEquals(FleetRecord.SUCCEEDED, rig.fleetState(fleet));
        assertTrue(rig.fleetStamp(fleet) > afterSettlement, "landing stamps too");
        long landed = rig.fleetStamp(fleet);
        fleets.advance(rig.tenant, fleet);
        assertEquals(landed, rig.fleetStamp(fleet), "an advance that changes nothing writes nothing and stamps nothing");
    }

    // ---- section 4.2: the pre-minted id, and the crash window -------------------------------------------------------

    @Test
    void aPreMintedIdIsAdmittedOnceAcrossACrashBetweenAdmissionAndRecord() {
        FleetItSupport rig = new FleetItSupport();
        Inst a = rig.inst();
        AtomicBoolean crash = new AtomicBoolean(true);
        FleetService crashing = rig.service(FleetItSupport.crashing(rig.rows, "markAdmitted", crash::get),
                rig.realAdmitter());
        UUID fleet = crashing.submitFleet(rig.tenant,
                rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a))).fleetId();
        UUID preMinted = rig.member(fleet, 0).applyId();

        // pass (ii) commits the admission; the process dies before pass (iii) can record it
        assertThrows(SimulatedCrash.class, () -> crashing.advance(rig.tenant, fleet));
        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 0), "nothing recorded it");
        assertTrue(rig.requestExists(preMinted), "the request exists, under the id the fleet minted at submit");

        crash.set(false);
        crashing.advance(rig.tenant, fleet);                                 // the next tick

        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 0), "recorded admitted, not refused as a busy scope");
        assertEquals(1, rig.requestsOf(a.instance()), "exactly one request for the member: nothing was admitted twice");
        assertTrue(rig.requestExists(preMinted), "under its pre-minted id");
    }

    @Test
    void reAdmittingAnAdmittedIdAnswersAlreadyAdmittedInEveryRequestState() {
        FleetItSupport rig = new FleetItSupport();
        // assertAll, not a loop that stops at the first red: each state is its own claim, and a planted violation shows
        // its own symptom in each (SCOPE_BUSY, the fence, a rethrown primary-key conflict, a ClassCastException)
        org.junit.jupiter.api.Assertions.assertAll(List.of(RequestState.REQUESTED, RequestState.RUNNING,
                RequestState.APPLIED, RequestState.STAGED, RequestState.FAILED_NO_EFFECT).stream()
                .map(state -> (org.junit.jupiter.api.function.Executable) () -> reAdmitIn(rig, state)));
    }

    private static void reAdmitIn(FleetItSupport rig, RequestState state) {
        Inst inst = rig.inst();
        ApplyCommand command = new ApplyCommand(new ScopeKey(new TenantId(rig.tenant),
                new InstanceId(inst.instance()), new PlacementId(inst.placement())), Operation.APPLY, 1L,
                Strategy.MANUAL, null, "operator-1");
        ApplyId id = ApplyId.next();

        assertEquals(new AdmissionResult.Admitted(id), rig.executor.admit(command, GenerationBinding.MUST_BE_STAGED, id),
                state + ": the request is inserted UNDER the supplied id");
        rig.drive(id.value(), state);
        assertEquals(state.name(), rig.requestState(id.value()));

        // the blocking check would answer SCOPE_BUSY (REQUESTED, RUNNING), the fence GENERATION_NOT_ABOVE_WATERMARK
        // (APPLIED, STAGED), and the insert a rethrown primary-key conflict (FAILED_NO_EFFECT): each wrong
        assertEquals(new AdmissionResult.AlreadyAdmitted(id), rig.executor.admit(command,
                GenerationBinding.MUST_BE_STAGED, id), state.name());
        assertEquals(new AdmissionResult.AlreadyAdmitted(id), rig.executor.admit(command, id), state + " (two-argument)");
        assertEquals(1, rig.requestsOf(inst.instance()), state + ": nothing was written");
        assertEquals(state.name(), rig.requestState(id.value()), state + ": and nothing was touched");
    }

    @Test
    void aRefusedAdmissionIsRecordedAdmittedWhenTheMembersRequestExists() {
        FleetItSupport rig = new FleetItSupport();
        Inst a = rig.inst();
        // (iii) is handed Refused(SCOPE_BUSY) although the member's own request exists under its pre-minted id: with the
        // id check in place admission cannot produce that answer, so the seam is driven directly
        FleetService.Admitter lying = (command, id) -> {
            assertInstanceOf(AdmissionResult.Admitted.class, rig.realAdmitter().admit(command, id));
            return new AdmissionResult.NotAdmitted(RefusalReason.SCOPE_BUSY, "simulated");
        };
        FleetService fleets = rig.service(rig.rows, lying);
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(1, FleetRecord.CONTINUE, FleetItSupport.ok(a))).fleetId();

        fleets.advance(rig.tenant, fleet);

        MemberRecord member = rig.member(fleet, 0);
        assertEquals(MemberRecord.ADMITTED, member.disposition(),
                "the request exists, so the member is admitted whatever admission said");
        assertNull(member.admissionRefusal());
        assertNull(member.settled());
    }

    @Test
    void anExceptionFromAdmissionIsNeitherAnAnswerNorAStall() {
        FleetItSupport rig = new FleetItSupport();
        Inst a = rig.inst();
        Inst b = rig.inst();
        AtomicBoolean explode = new AtomicBoolean(true);
        FleetService.Admitter flaky = (command, id) -> {
            if (explode.get() && command.scope().instance().value().equals(a.instance())) {
                throw new IllegalStateException("the rethrown primary-key conflict, say");
            }
            return rig.realAdmitter().admit(command, id);
        };
        FleetService fleets = rig.service(rig.rows, flaky);
        UUID fleet = fleets.submitFleet(rig.tenant, rig.manual(2, FleetRecord.CONTINUE, FleetItSupport.ok(a),
                FleetItSupport.ok(b))).fleetId();

        fleets.advance(rig.tenant, fleet);

        assertEquals(MemberRecord.PENDING, rig.disposition(fleet, 0),
                "an exception is never a refusal: the member stays pending for the next tick");
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 1), "and never stalls the others");

        explode.set(false);
        fleets.advance(rig.tenant, fleet);
        assertEquals(MemberRecord.ADMITTED, rig.disposition(fleet, 0), "the next tick admits it");
    }

    // ---- section 5.5: one advancer ----------------------------------------------------------------------------------

    @Test
    void onlyTheFleetSchedulerAdvancesAndOnlyUnderTheWorkerSingletonGuard() {
        FleetItSupport rig = new FleetItSupport();
        FleetService fleets = rig.service();
        Inst a = rig.inst();
        Inst b = rig.inst();

        // (1) submit never advances: it wakes the scheduler and returns
        FleetAcceptedView accepted = fleets.submitFleet(rig.tenant,
                rig.manual(2, FleetRecord.CONTINUE, FleetItSupport.ok(a), FleetItSupport.ok(b)));
        assertEquals(1, rig.wakes.get(), "the scheduler is woken, once");
        assertEquals(List.of("pending", "pending"), rig.members(accepted.fleetId()).stream()
                .map(MemberRecord::disposition).toList(), "...and nothing was admitted on the request thread");
        assertEquals(0, rig.requests());

        // (2) the scheduler starts only in a process that holds the guard: a second one is refused
        String guardDb = "smdb_fleet_guard";
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();
        if (Boolean.FALSE.equals(su.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :n)",
                new MapSqlParameterSource("n", guardDb), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE " + guardDb);
        }
        WorkerSingletonGuard other = new WorkerSingletonGuard(guardDataSource(guardDb));
        WorkerSingletonGuard mine = new WorkerSingletonGuard(guardDataSource(guardDb));
        other.acquireOrRefuseToStart();
        try {
            FleetScheduler refused = new FleetScheduler(() -> true, tenantSource, fleets, mine,
                    new com.solace.sm.dp.fleet.FleetProperties(true, Duration.ofMillis(200), null));
            assertThrows(IllegalStateException.class, refused::start,
                    "a second process cannot run a second scheduler: the guard is what refuses it");
            assertFalse(refused.isRunning());
        } finally {
            other.release();
        }

        FleetScheduler owned = new FleetScheduler(() -> true, tenantSource, fleets, mine,
                new com.solace.sm.dp.fleet.FleetProperties(true, Duration.ofMillis(200), null));
        try {
            owned.start();
            assertTrue(owned.isRunning(), "the guard is free, so the scheduler starts");
            WorkerSingletonGuard third = new WorkerSingletonGuard(guardDataSource(guardDb));
            assertThrows(IllegalStateException.class, third::acquireOrRefuseToStart,
                    "and it now HOLDS the guard: that is what it started under");
        } finally {
            owned.stop();
            mine.release();
        }
    }

    @Test
    void aSchedulerWithNoExecutorIsInertAndTakesNoLock() {
        FleetItSupport rig = new FleetItSupport();
        String guardDb = "smdb_fleet_guard";
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();
        if (Boolean.FALSE.equals(su.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :n)",
                new MapSqlParameterSource("n", guardDb), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE " + guardDb);
        }
        WorkerSingletonGuard guard = new WorkerSingletonGuard(guardDataSource(guardDb));
        FleetScheduler inert = new FleetScheduler(() -> false, tenantSource, rig.service(), guard,
                new com.solace.sm.dp.fleet.FleetProperties(true, Duration.ofMillis(200), null));

        inert.start();

        assertFalse(inert.isRunning(), "nothing can be admitted without an executor, so there is nothing to advance");
        WorkerSingletonGuard probe = new WorkerSingletonGuard(guardDataSource(guardDb));
        try {
            assertDoesNotThrow(probe::acquireOrRefuseToStart, "and the singleton lock was never taken");
        } finally {
            probe.release();
        }
    }

    private static DataSource guardDataSource(String database) {
        return SmDataPostgres.dataSource(SmDataPostgres.jdbcUrl(database), SmDataPostgres.container().getUsername(),
                SmDataPostgres.container().getPassword());
    }
}
