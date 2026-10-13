package com.solace.sm.dp.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.api.fleet.FleetApplyCommand.Member;
import com.solace.sm.dp.api.fleet.FleetValidationException;
import com.solace.sm.dp.api.fleet.FleetValidationException.Rule;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 012 T6 ({@code OPS-Q8} K-2 and (x); {@code BE-D50}), as pure unit tests over an in-memory {@link FleetRows}: no
 * database, no Docker. The service under test is the real {@link FleetService}; the rows, the admission seam and the clock
 * are fakes, so every assertion is about the fleet's own decisions.
 *
 * <ul>
 *   <li><b>K-2</b>: a {@code rolling-*} fleet whose any batch holds two placements of one instance is refused at submit
 *       ({@code 422}, {@code fleet.batch.instance.repeated}) and writes nothing; {@code restart-all} and {@code manual} are
 *       exempt.
 *   <li><b>The settle delay</b>: a {@code rolling-no-validation} fleet opens its next batch no earlier than
 *       {@code settle} after the current batch's LAST {@code apply_request.terminal_at}, computed from persisted
 *       timestamps - so a second {@link FleetService} over the same rows (a scheduler restart) keeps the delay.
 * </ul>
 */
class FleetBatchSafetyTest {

    private static final Instant T0 = Instant.parse("2026-10-08T10:00:00Z");
    private static final Duration SETTLE = Duration.ofSeconds(30);

    // ---- K-2: refuse at submit --------------------------------------------------------------------------------

    @Test
    void aRollingHealthGatedBatchHoldingTwoPlacementsOfOneInstanceIsRefused() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();

        FleetValidationException refused = assertThrows(FleetValidationException.class,
                () -> rig.service().submitFleet(rig.tenant, rig.command("rolling-health-gated", 2,
                        member(a), member(a))));

        assertEquals(Rule.BATCH_INSTANCE_REPEATED, refused.rule());
        assertEquals(1, refused.ordinal(), "the second placement of the pair is the one named");
        assertTrue(rig.rows.fleets.isEmpty(), "a refused fleet writes nothing");
        assertTrue(rig.rows.members.isEmpty());
    }

    @Test
    void aRollingNoValidationBatchHoldingTwoPlacementsOfOneInstanceIsRefused() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();

        FleetValidationException refused = assertThrows(FleetValidationException.class,
                () -> rig.service().submitFleet(rig.tenant, rig.command("rolling-no-validation", 3,
                        member(a), member(a), member(a))));

        assertEquals(Rule.BATCH_INSTANCE_REPEATED, refused.rule());
        assertEquals(1, refused.ordinal());
        assertTrue(rig.rows.fleets.isEmpty());
    }

    @Test
    void theRefusalNamesTheOrdinalAndTheClassifierAndNeverAnId() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();

        FleetValidationException refused = assertThrows(FleetValidationException.class,
                () -> rig.service().submitFleet(rig.tenant, rig.command("rolling-no-validation", 2,
                        member(a), member(a))));

        assertEquals("fleet.batch.instance.repeated", Rule.BATCH_INSTANCE_REPEATED.code());
        assertTrue(refused.getMessage().startsWith("member 1 "), refused.getMessage());
        assertTrue(refused.getMessage().contains("fleet.batch.instance.repeated"), refused.getMessage());
        assertFalse(refused.getMessage().contains(a.toString()), "no id from the request is echoed (I6)");
    }

    @Test
    void restartAllIsExemptBecauseItsConfirmationCoversTheMultiPlacementOutage() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();

        var accepted = rig.service().submitFleet(rig.tenant, rig.command("restart-all", 3, "principal-1",
                member(a), member(a), member(a)));

        assertEquals(3, accepted.members().size());
        assertEquals(1, rig.rows.fleets.size());
    }

    @Test
    void manualIsExemptBecauseItRestartsNothing() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();

        rig.service().submitFleet(rig.tenant, rig.command("manual", 2, member(a), member(a)));

        assertEquals(1, rig.rows.fleets.size());
    }

    @Test
    void batchSizeOneNeverRepeatsAnInstanceWithinABatchSoEveryRollingStrategyIsAdmitted() {
        for (String strategy : List.of("rolling-health-gated", "rolling-no-validation")) {
            Rig rig = new Rig();
            UUID a = UUID.randomUUID();
            rig.service().submitFleet(rig.tenant, rig.command(strategy, 1, member(a), member(a), member(a)));
            assertEquals(1, rig.rows.fleets.size(), strategy);
        }
    }

    @Test
    void anInstanceMayRecurInALaterBatch() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        // batch 0 = {a/1, b/1}, batch 1 = {a/2, b/2}: no batch holds an instance twice
        rig.service().submitFleet(rig.tenant, rig.command("rolling-no-validation", 2,
                member(a), member(b), member(a), member(b)));

        assertEquals(1, rig.rows.fleets.size());
    }

    @Test
    void twoPlacementsOfOneInstanceInDifferentBatchesByBatchSizeAreAdmittedAndTheNextBatchSizeRefuses() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // [a, b, a]: batchSize 2 puts a in batch 0 and batch 1 (allowed); batchSize 3 puts both in batch 0 (refused)
        Rig allowed = new Rig();
        allowed.service().submitFleet(allowed.tenant, allowed.command("rolling-health-gated", 2,
                member(a), member(b), member(a)));
        assertEquals(1, allowed.rows.fleets.size());

        Rig refused = new Rig();
        FleetValidationException e = assertThrows(FleetValidationException.class,
                () -> refused.service().submitFleet(refused.tenant, refused.command("rolling-health-gated", 3,
                        member(a), member(b), member(a))));
        assertEquals(2, e.ordinal());
    }

    @Test
    void aMemberTheCallerMayNotSeeNeverCountsTowardsARepeat() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();
        // member 0 was refused by the gateway (it never runs, and the data plane never reads it): it cannot make a batch
        // "hold two placements" - and counting it would let a caller probe, by the 422, whether it shares an instance
        Member hidden = new Member(a, UUID.randomUUID(), 1, FleetApplyCommand.RefusalCode.NOT_FOUND);

        rig.service().submitFleet(rig.tenant, rig.command("rolling-no-validation", 2, hidden, member(a)));

        assertEquals(1, rig.rows.fleets.size());
    }

    @Test
    void aDuplicateMemberStillAnswersItsOwnRuleFirst() {
        Rig rig = new Rig();
        UUID a = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        Member same = new Member(a, p, 1, null);

        FleetValidationException refused = assertThrows(FleetValidationException.class,
                () -> rig.service().submitFleet(rig.tenant, rig.command("rolling-no-validation", 2, same, same)));

        assertEquals(Rule.DUPLICATE_MEMBER, refused.rule());
    }

    // ---- the settle delay -------------------------------------------------------------------------------------

    @Test
    void theNextBatchOpensNoEarlierThanTheSettleDelayAfterTheCurrentBatchsLastTerminalAt() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 3);

        rig.pass(fleetId);                       // admits member 0
        assertEquals(List.of(0), rig.admittedOrdinals());
        rig.finish(0, RequestState.APPLIED, T0); // terminal_at = T0

        rig.clock.set(T0.plusSeconds(29));
        rig.pass(fleetId);
        assertEquals(0, rig.rows.fleet(fleetId).currentBatch(), "29 s after terminal_at: still inside the settle delay");
        assertEquals(List.of(0), rig.admittedOrdinals(), "member 1 is not admitted early");

        rig.clock.set(T0.plusSeconds(30));
        rig.pass(fleetId);
        assertEquals(1, rig.rows.fleet(fleetId).currentBatch(), "exactly settle after terminal_at the batch opens");
        assertEquals(List.of(0, 1), rig.admittedOrdinals());
    }

    @Test
    void theDelayIsComputedFromPersistedTimestampsSoASchedulerRestartKeepsIt() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 2);
        rig.pass(fleetId);
        rig.finish(0, RequestState.APPLIED, T0);
        rig.clock.set(T0.plusSeconds(5));
        rig.pass(fleetId);
        assertEquals(0, rig.rows.fleet(fleetId).currentBatch());

        // "restart": a brand-new service and nothing carried over but the rows and the clock
        FleetService restarted = rig.newService();
        restarted.advance(rig.tenant, fleetId);
        assertEquals(0, rig.rows.fleet(fleetId).currentBatch(), "a fresh scheduler does not reset the delay");

        rig.clock.set(T0.plusSeconds(30));
        restarted.advance(rig.tenant, fleetId);
        assertEquals(1, rig.rows.fleet(fleetId).currentBatch());
    }

    @Test
    void theDelayRunsFromTheLastMemberOfTheBatchNotTheFirst() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 2, 4);
        rig.pass(fleetId);                       // admits members 0 and 1
        rig.finish(0, RequestState.APPLIED, T0);
        rig.finish(1, RequestState.APPLIED, T0.plusSeconds(10));

        rig.clock.set(T0.plusSeconds(39));
        rig.pass(fleetId);
        assertEquals(0, rig.rows.fleet(fleetId).currentBatch(), "the first member's 30 s are over; the last member's are not");

        rig.clock.set(T0.plusSeconds(40));
        rig.pass(fleetId);
        assertEquals(1, rig.rows.fleet(fleetId).currentBatch());
    }

    @Test
    void theOtherStrategiesOpenTheNextBatchWithNoDelay() {
        for (String strategy : List.of("rolling-health-gated", "restart-all", "manual")) {
            Rig rig = new Rig();
            var fleetId = rig.submit(strategy, 1, 2);
            rig.pass(fleetId);
            rig.finish(0, strategy.equals("manual") ? RequestState.STAGED : RequestState.APPLIED, T0);
            rig.clock.set(T0);       // the very instant the member terminated

            rig.pass(fleetId);

            assertEquals(1, rig.rows.fleet(fleetId).currentBatch(), strategy + " does not wait");
            assertEquals(List.of(0, 1), rig.admittedOrdinals(), strategy);
        }
    }

    @Test
    void aZeroSettleDelayDisablesTheWait() {
        Rig rig = new Rig(Duration.ZERO);
        var fleetId = rig.submit("rolling-no-validation", 1, 2);
        rig.pass(fleetId);
        rig.finish(0, RequestState.APPLIED, T0);
        rig.clock.set(T0);

        rig.pass(fleetId);

        assertEquals(1, rig.rows.fleet(fleetId).currentBatch());
    }

    @Test
    void theLastBatchLandsTheFleetWithoutWaiting() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 1);
        rig.pass(fleetId);
        rig.finish(0, RequestState.APPLIED, T0);
        rig.clock.set(T0.plusSeconds(1));

        rig.pass(fleetId);

        assertEquals(FleetRecord.SUCCEEDED, rig.rows.fleet(fleetId).state(), "a delay protects the next restart, and there is none");
    }

    @Test
    void aFailedMemberStillStartsTheDelayBecauseItsHostWasStillRestarted() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 2, FleetRecord.CONTINUE);
        rig.pass(fleetId);
        rig.finish(0, RequestState.CONFIG_DIVERGED, T0);
        rig.clock.set(T0.plusSeconds(10));

        rig.pass(fleetId);

        assertEquals(0, rig.rows.fleet(fleetId).currentBatch(), "on-failure continue also waits");
    }

    @Test
    void aBatchWhoseMembersWereNeverAdmittedHasNothingToSettle() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 2, FleetRecord.CONTINUE);
        rig.admission = (command, id) -> new AdmissionResult.StrategyUnsupported(command.strategy());
        rig.pass(fleetId);                       // member 0 is not admitted: no request, no terminal_at
        rig.clock.set(T0);

        rig.pass(fleetId);

        assertEquals(1, rig.rows.fleet(fleetId).currentBatch(), "nothing was restarted, so nothing needs to settle");
    }

    /** The member's request is APPLY_INDETERMINATE and the reconciler gave up at {@code gaveUpAt}: no terminal_at. */
    private static void giveUp(Rig rig, int ordinal, Instant gaveUpAt) {
        rig.rows.requests.put(rig.rows.member(ordinal).applyId(), new MemberRequest(RequestState.APPLY_INDETERMINATE, null,
                "reconcile.foreign.content", 3L, null, gaveUpAt));
    }

    @Test
    void aGivenUpIndeterminateMemberAnchorsTheDelayOnItsGaveUpAtNotOnNothing() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 2, FleetRecord.CONTINUE);
        rig.pass(fleetId);
        giveUp(rig, 0, T0);                      // the reconciler gave up at its first inspection; terminal_at is NULL

        rig.clock.set(T0.plusSeconds(29));
        rig.pass(fleetId);
        assertEquals(0, rig.rows.fleet(fleetId).currentBatch(), "29 s after the give-up the hosts may still be restarting");
        assertEquals(MemberRecord.FAILED, rig.rows.member(0).settled(), "the member is settled failed all the same");

        rig.clock.set(T0.plusSeconds(30));
        rig.pass(fleetId);
        assertEquals(1, rig.rows.fleet(fleetId).currentBatch());
    }

    @Test
    void theLastAnchorOfTheBatchWinsWhetherItIsATerminalAtOrAGaveUpAt() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 2, 4, FleetRecord.CONTINUE);
        rig.pass(fleetId);
        giveUp(rig, 0, T0.plusSeconds(5));
        rig.finish(1, RequestState.APPLIED, T0);

        rig.clock.set(T0.plusSeconds(34));
        rig.pass(fleetId);
        assertEquals(0, rig.rows.fleet(fleetId).currentBatch(), "the later gave_up_at (T0+5) still holds the batch");

        rig.clock.set(T0.plusSeconds(35));
        rig.pass(fleetId);
        assertEquals(1, rig.rows.fleet(fleetId).currentBatch());
    }

    @Test
    void aSettledMemberWithNeitherInstantAnchorsNothing() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 2, FleetRecord.CONTINUE);
        rig.pass(fleetId);
        // not reachable through the reconciler (it writes gave_up_at with the classifier); the fail-safe direction is
        // recorded: no persisted instant, so there is nothing to measure from
        rig.rows.requests.put(rig.rows.member(0).applyId(),
                new MemberRequest(RequestState.APPLY_INDETERMINATE, null, "reconcile.gave-up", 3L, null));

        rig.clock.set(T0);
        rig.pass(fleetId);

        assertEquals(1, rig.rows.fleet(fleetId).currentBatch());
    }

    // ---- halt-remaining over a settle delay -------------------------------------------------------------------

    @Test
    void aVerifyMismatchOnMemberTwoOfThreeSkipsMemberThreeAndLandsWithoutWaiting() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 3, FleetRecord.HALT_REMAINING);
        rig.pass(fleetId);
        rig.finish(0, RequestState.APPLIED, T0);
        rig.clock.set(T0.plusSeconds(30));
        rig.pass(fleetId);                       // opens batch 1, admits member 1
        rig.finish(1, RequestState.CONFIG_DIVERGED, T0.plusSeconds(31));   // the verify read-back disagreed

        rig.clock.set(T0.plusSeconds(32));
        rig.pass(fleetId);

        assertEquals(MemberRecord.SKIPPED, rig.rows.member(2).disposition(), "member 3 never starts");
        assertEquals(FleetRecord.HALTED, rig.rows.fleet(fleetId).state(), "the halt lands the fleet at once");
        assertEquals(List.of(0, 1), rig.admittedOrdinals());
        assertEquals(2, rig.admissions.size(), "no rollback is admitted: exactly the two members' requests were");
    }

    @Test
    void onFailureContinueRunsMemberThreeAfterTheDelayEvenWhenMemberTwoDiverged() {
        Rig rig = new Rig();
        var fleetId = rig.submit("rolling-no-validation", 1, 3, FleetRecord.CONTINUE);
        rig.pass(fleetId);
        rig.finish(0, RequestState.APPLIED, T0);
        rig.clock.set(T0.plusSeconds(30));
        rig.pass(fleetId);
        rig.finish(1, RequestState.CONFIG_DIVERGED, T0.plusSeconds(31));

        rig.clock.set(T0.plusSeconds(60));
        rig.pass(fleetId);
        assertEquals(1, rig.rows.fleet(fleetId).currentBatch(), "30 s after member 2's terminal_at (T0+31) is T0+61");

        rig.clock.set(T0.plusSeconds(61));
        rig.pass(fleetId);
        assertEquals(2, rig.rows.fleet(fleetId).currentBatch());
        assertEquals(List.of(0, 1, 2), rig.admittedOrdinals());
    }

    // ---- the property --------------------------------------------------------------------------------------------

    @Test
    void theSettleDelayDefaultIsThirtySecondsAndMustNotBeNegative() {
        assertEquals(Duration.ofSeconds(30), new FleetProperties(true, Duration.ofSeconds(1), null).noValidation().settle());
        assertThrows(IllegalArgumentException.class, () -> new FleetProperties.NoValidation(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new FleetProperties.NoValidation(null));
        assertEquals(Duration.ZERO, new FleetProperties.NoValidation(Duration.ZERO).settle(), "zero withdraws the delay");
    }

    // ---- the rig -------------------------------------------------------------------------------------------------

    private static Member member(UUID instance) {
        return new Member(instance, UUID.randomUUID(), 1, null);
    }

    private static final class MutableClock extends Clock {
        private Instant now = T0;

        void set(Instant at) {
            this.now = at;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Rig {
        final UUID tenant = UUID.randomUUID();
        final UUID solution = UUID.randomUUID();
        final MutableClock clock = new MutableClock();
        final InMemoryFleetRows rows = new InMemoryFleetRows();
        final List<UUID> admissions = new ArrayList<>();
        FleetService.Admitter admission = (command, id) -> new AdmissionResult.Admitted(id);
        private final Duration settle;
        private FleetService service;

        Rig() {
            this(SETTLE);
        }

        Rig(Duration settle) {
            this.settle = settle;
        }

        FleetService service() {
            if (service == null) {
                service = newService();
            }
            return service;
        }

        FleetService newService() {
            InstanceRows instances = stub(InstanceRows.class, (method, args) -> {
                if (method.getName().equals("find")) {
                    return Optional.of(new InstanceRecord(tenant, (UUID) args[1], UUID.randomUUID(), solution, "cache",
                            "2.4", "i-" + args[1], InstanceLifecycle.ACTIVE, AcquisitionMode.DIRECT_HTTP,
                            UUID.randomUUID(), T0, 1L));
                }
                throw new UnsupportedOperationException(method.getName());
            });
            PlacementRows placements = stub(PlacementRows.class, (method, args) -> {
                if (method.getName().equals("findLive")) {
                    return Optional.of(new PlacementRecord(tenant, (UUID) args[2], (UUID) args[1], UUID.randomUUID(),
                            "c", "/d", "/c", "f", "BIND_MOUNT", 5, Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), T0));
                }
                throw new UnsupportedOperationException(method.getName());
            });
            ConfigGenerationRows generations = stub(ConfigGenerationRows.class, (method, args) -> {
                if (method.getName().equals("view")) {
                    return Optional.of(new GenerationView((Long) args[2], "cache", "2.4", "d", GenerationOrigin.EDIT,
                            null, UUID.randomUUID(), T0, 1L));
                }
                throw new UnsupportedOperationException(method.getName());
            });
            return new FleetService(noTransactions(), rows, instances, placements, generations,
                    (command, id) -> {
                        admissions.add(id.value());
                        return admission.admit(command, id);
                    }, clock, settle, () -> { });
        }

        FleetApplyCommand command(String strategy, int batchSize, Member... members) {
            return command(strategy, batchSize, null, members);
        }

        FleetApplyCommand command(String strategy, int batchSize, String confirmedBy, Member... members) {
            return new FleetApplyCommand(solution, List.of(members), strategy, confirmedBy, batchSize,
                    FleetRecord.CONTINUE, UUID.randomUUID(), UUID.randomUUID());
        }

        /** Submits a fleet of {@code count} single-placement members, each on its own instance. */
        UUID submit(String strategy, int batchSize, int count) {
            return submit(strategy, batchSize, count, FleetRecord.CONTINUE);
        }

        UUID submit(String strategy, int batchSize, int count, String onFailure) {
            Member[] members = new Member[count];
            for (int i = 0; i < count; i++) {
                members[i] = member(UUID.randomUUID());
            }
            FleetApplyCommand base = command(strategy, batchSize, strategy.equals("restart-all") ? "p" : null, members);
            FleetApplyCommand command = new FleetApplyCommand(base.solutionId(), base.members(), base.strategy(),
                    base.outageConfirmedBy(), base.batchSize(), onFailure, base.actor(), base.correlationId());
            return service().submitFleet(tenant, command).fleetId();
        }

        void pass(UUID fleetId) {
            service().advance(tenant, fleetId);
        }

        /** The member's request lands in {@code state} at {@code terminalAt}. */
        void finish(int ordinal, RequestState state, Instant terminalAt) {
            rows.requests.put(rows.member(ordinal).applyId(), new MemberRequest(state, null, null, 2L, terminalAt));
        }

        List<Integer> admittedOrdinals() {
            return rows.members.values().stream().filter(m -> m.is(MemberRecord.ADMITTED)).map(MemberRecord::ordinal)
                    .sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.handle(method, args));
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static TransactionTemplate noTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    /** {@link FleetRows} in memory: the same conditional updates the JDBC repository makes, nothing more. */
    private static final class InMemoryFleetRows implements FleetRows {
        final Map<UUID, FleetRecord> fleets = new LinkedHashMap<>();
        final Map<Integer, MemberRecord> members = new LinkedHashMap<>();
        final Map<UUID, MemberRequest> requests = new HashMap<>();

        FleetRecord fleet(UUID fleetId) {
            return fleets.get(fleetId);
        }

        MemberRecord member(int ordinal) {
            return members.get(ordinal);
        }

        @Override
        public void insertFleet(FleetRecord fleet) {
            fleets.put(fleet.fleetId(), fleet);
        }

        @Override
        public void insertMember(MemberRecord member) {
            members.put(member.ordinal(), member);
        }

        @Override
        public Optional<FleetRecord> find(UUID tenantId, UUID fleetId) {
            return Optional.ofNullable(fleets.get(fleetId));
        }

        @Override
        public Optional<FleetRecord> lock(UUID tenantId, UUID fleetId) {
            return find(tenantId, fleetId);
        }

        @Override
        public List<UUID> runningFleets(UUID tenantId) {
            return fleets.values().stream().filter(FleetRecord::running).map(FleetRecord::fleetId).toList();
        }

        @Override
        public List<MemberRecord> members(UUID tenantId, UUID fleetId) {
            return members.values().stream().sorted((x, y) -> Integer.compare(x.ordinal(), y.ordinal())).toList();
        }

        @Override
        public Map<UUID, MemberRequest> requestsOf(UUID tenantId, Collection<UUID> applyIds) {
            Map<UUID, MemberRequest> found = new HashMap<>();
            for (UUID id : applyIds) {
                if (requests.containsKey(id)) {
                    found.put(id, requests.get(id));
                }
            }
            return found;
        }

        @Override
        public boolean requestExists(UUID tenantId, UUID applyId) {
            return requests.containsKey(applyId);
        }

        private int update(int ordinal, String from, java.util.function.UnaryOperator<MemberRecord> change) {
            MemberRecord m = members.get(ordinal);
            if (m == null || (from != null && !m.is(from))) {
                return 0;
            }
            members.put(ordinal, change.apply(m));
            return 1;
        }

        @Override
        public int markAdmitted(UUID tenantId, UUID fleetId, int ordinal) {
            return update(ordinal, MemberRecord.PENDING, m -> new MemberRecord(m.tenantId(), m.fleetId(), m.ordinal(),
                    m.batch(), m.instanceId(), m.placementId(), m.generation(), m.applyId(), MemberRecord.ADMITTED,
                    m.refusalCode(), m.admissionRefusal(), m.settled()));
        }

        @Override
        public int markNotAdmitted(UUID tenantId, UUID fleetId, int ordinal, String admissionRefusal) {
            return update(ordinal, MemberRecord.PENDING, m -> new MemberRecord(m.tenantId(), m.fleetId(), m.ordinal(),
                    m.batch(), m.instanceId(), m.placementId(), m.generation(), m.applyId(), MemberRecord.NOT_ADMITTED,
                    m.refusalCode(), admissionRefusal, MemberRecord.FAILED));
        }

        @Override
        public int markSkipped(UUID tenantId, UUID fleetId, int ordinal) {
            return update(ordinal, MemberRecord.PENDING, m -> new MemberRecord(m.tenantId(), m.fleetId(), m.ordinal(),
                    m.batch(), m.instanceId(), m.placementId(), m.generation(), m.applyId(), MemberRecord.SKIPPED,
                    m.refusalCode(), m.admissionRefusal(), m.settled()));
        }

        @Override
        public int settle(UUID tenantId, UUID fleetId, int ordinal, String settled) {
            MemberRecord m = members.get(ordinal);
            if (m == null || !m.is(MemberRecord.ADMITTED) || m.settled() != null) {
                return 0;
            }
            members.put(ordinal, new MemberRecord(m.tenantId(), m.fleetId(), m.ordinal(), m.batch(), m.instanceId(),
                    m.placementId(), m.generation(), m.applyId(), m.disposition(), m.refusalCode(),
                    m.admissionRefusal(), settled));
            return 1;
        }

        @Override
        public int moveToBatch(UUID tenantId, UUID fleetId, int batch) {
            FleetRecord f = fleets.get(fleetId);
            fleets.put(fleetId, new FleetRecord(f.tenantId(), f.fleetId(), f.catalogSolutionId(), f.strategy(),
                    f.outageConfirmedBy(), f.batchSize(), f.onFailure(), f.state(), batch, f.requestedBy(),
                    f.requestedAt(), f.correlationId(), f.terminalAt(), f.stateVersion()));
            return 1;
        }

        @Override
        public int land(UUID tenantId, UUID fleetId, String state, Instant at) {
            FleetRecord f = fleets.get(fleetId);
            fleets.put(fleetId, new FleetRecord(f.tenantId(), f.fleetId(), f.catalogSolutionId(), f.strategy(),
                    f.outageConfirmedBy(), f.batchSize(), f.onFailure(), state, f.currentBatch(), f.requestedBy(),
                    f.requestedAt(), f.correlationId(), at, f.stateVersion()));
            return 1;
        }
    }
}
