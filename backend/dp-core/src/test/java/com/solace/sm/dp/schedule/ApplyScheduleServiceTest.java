package com.solace.sm.dp.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.DueSchedule;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RestoreRuntimeCommand;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.ScheduleAcceptedView;
import com.solace.sm.dp.api.apply.ScheduleCommand;
import com.solace.sm.dp.api.apply.ScheduleFireResult;
import com.solace.sm.dp.api.apply.ScheduleFireResult.Outcome;
import com.solace.sm.dp.api.apply.ScheduleReason;
import com.solace.sm.dp.api.apply.ScheduleRefusedException;
import com.solace.sm.dp.api.apply.ScheduleView;
import com.solace.sm.dp.api.apply.SetLogLevelCommand;
import com.solace.sm.dp.api.apply.StopCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.RollbackTargetRows;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.schedule.ScheduleRecord.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 012 T7: the data plane's half of {@code scheduled} (Sch-2, {@code OPS-Q10} (c)), against fakes that record every
 * call in order. What the database refuses (the shapes, the forward-only trigger, the window CHECKs) is
 * {@code ApplyScheduleSchemaIT}'s and the end-to-end W1-W6 are the launcher ITs'; pinned here is WHAT the service does and
 * in which order: it validates now and holds nothing, it admits an ordinary apply only inside the window and under the
 * row's lock, a rollback is resolved and copied forward AT FIRE (never at submit), a busy fence defers and an unchanging
 * refusal closes the schedule with a reason from the closed set.
 */
class ApplyScheduleServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-08T14:00:00Z");
    private static final Instant OPENS = Instant.parse("2026-10-09T02:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-10-09T04:00:00Z");

    private final List<String> calls = new ArrayList<>();
    private FakeRows rows;
    private FakeTargets targets;
    private FakeApply apply;
    private FakeRollbackRows rollbackRows;
    private Instant clockNow;
    private UUID nextId;
    private ApplyScheduleServiceImpl service;

    @BeforeEach
    void rig() {
        calls.clear();
        rows = new FakeRows(calls);
        targets = new FakeTargets();
        apply = new FakeApply(calls);
        rollbackRows = new FakeRollbackRows();
        clockNow = NOW;
        nextId = UUID.randomUUID();
        service = build(ApplyScheduleProperties.defaults());
    }

    private ApplyScheduleServiceImpl build(ApplyScheduleProperties properties) {
        return new ApplyScheduleServiceImpl(new TransactionTemplate(new NoTransactions()), rows, targets, apply,
                new RollbackTargets(rollbackRows), properties, new Clock() {
                    @Override public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override public Instant instant() {
                        return clockNow;
                    }
                }, () -> nextId);
    }

    /** The data plane decides with ITS clock (W3 review W3B-1), so a test sets the clock to the moment it means. */
    private ScheduleFireResult admitAt(UUID schedule, Instant at) {
        clockNow = at;
        return service.admit(TENANT, schedule);
    }

    private ScheduleFireResult refuseAt(UUID schedule, ScheduleReason reason, Instant at) {
        clockNow = at;
        return service.refuse(TENANT, schedule, reason);
    }

    private ScheduleFireResult missAt(UUID schedule, Instant at) {
        clockNow = at;
        return service.miss(TENANT, schedule);
    }

    private ScheduleCommand applyAt(String strategy, String confirmation) {
        return ScheduleCommand.apply(PLACEMENT, 9L, strategy, confirmation, OPENS, CLOSES, SUBMITTER);
    }

    private ScheduleCommand rollbackByGeneration() {
        return ScheduleCommand.rollback(PLACEMENT, null, 5L, "manual", null, OPENS, CLOSES, SUBMITTER);
    }

    private UUID record(ScheduleCommand command) {
        service.schedule(TENANT, INSTANCE, command);
        return nextId;
    }

    // ---- schedule: validated NOW, nothing held -------------------------------------------------------------------

    @Test
    void aScheduledApplyIsRecordedPendingWithItsWindowAndItsSubmitterAndAdmitsNothing() {
        ScheduleAcceptedView accepted = service.schedule(TENANT, INSTANCE, applyAt("rolling-health-gated", null));

        assertEquals(nextId, accepted.scheduleId());
        assertEquals(1001L, accepted.stateVersion(), "the stamp the database drew on the insert");
        ScheduleRecord row = rows.rows.get(nextId);
        assertEquals(State.PENDING, row.state());
        assertEquals(ScheduleCommand.Operation.APPLY, row.operation());
        assertEquals(9L, row.generation());
        assertEquals("rolling-health-gated", row.strategy());
        assertEquals(OPENS, row.notBefore());
        assertEquals(CLOSES, row.notAfter());
        assertEquals(SUBMITTER, row.requestedBy());
        assertEquals(NOW, row.requestedAt(), "the injected clock stamps the request");
        assertEquals(List.of("insert"), calls, "no apply was admitted, and no fence was consulted");
    }

    @Test
    void aRestartAllScheduleStoresTheConfirmingPrincipalAndAScheduledRollbackStoresItsTargetAndNothingIsMinted() {
        record(applyAt("restart-all", SUBMITTER.toString()));
        assertEquals(SUBMITTER, rows.rows.get(nextId).outageConfirmedBy());

        UUID second = UUID.randomUUID();
        nextId = second;
        rollbackRows.eligible.add(5L);
        record(rollbackByGeneration());
        ScheduleRecord rollback = rows.rows.get(second);
        assertEquals(ScheduleCommand.Operation.ROLLBACK, rollback.operation());
        assertEquals(5L, rollback.rollbackGeneration());
        assertNull(rollback.generation());
        assertEquals(List.of(), apply.rolledBack,
                "a scheduled rollback is copied forward at FIRE (design 6.3 F12): submit only validated eligibility");
    }

    @Test
    void anInstanceThatIsNotActiveAndAPlacementThatIsNotLiveAreTheSameNotFoundAndWriteNothing() {
        targets.instanceActive = false;
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.schedule(TENANT, INSTANCE, applyAt("manual", null))).reason());
        targets.instanceActive = true;
        targets.placementLive = false;
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.schedule(TENANT, INSTANCE, applyAt("manual", null))).reason());
        assertEquals(List.of(), calls);
    }

    /** FF-1: a placement retired between the outside read and the lock is re-checked under the lock: nothing is inserted. */
    @Test
    void aPlacementRetiredBetweenTheOutsideReadAndTheLockIsRefusedNotFoundAndNothingIsInserted() {
        targets.placementLiveOnceLocked = false;
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.schedule(TENANT, INSTANCE, applyAt("manual", null))).reason());
        assertEquals(List.of("scope"), targets.locks);
        assertEquals(List.of(), calls);
    }

    /** OR-131: the insert is behind the apply_scope lock and its re-check, the same lock beginRetire holds. */
    @Test
    void aScheduleTakesTheScopeLockBeforeInsertingAndIsRefusedWhenAnUndeclareGotThereFirst() {
        service.schedule(TENANT, INSTANCE, applyAt("manual", null));
        assertEquals(List.of("scope"), targets.locks);

        targets.activeOnceLocked = false;   // the undeclare committed between the pre-check and the lock
        int before = rows.rows.size();
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.schedule(TENANT, INSTANCE, applyAt("manual", null))).reason());
        assertEquals(before, rows.rows.size(), "nothing was inserted");
    }

    @Test
    void theWindowMustOpenInTheFutureAndNoFurtherAheadThanMaxLead() {
        ScheduleCommand now = ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, NOW, CLOSES, SUBMITTER);
        assertEquals(ScheduleRefusedException.Reason.NOT_BEFORE_NOT_IN_THE_FUTURE,
                assertThrows(ScheduleRefusedException.class, () -> service.schedule(TENANT, INSTANCE, now)).reason(),
                "notBefore equal to now is not in the future: that is an apply");
        ScheduleCommand past = ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, NOW.minusSeconds(1), CLOSES, SUBMITTER);
        assertEquals(ScheduleRefusedException.Reason.NOT_BEFORE_NOT_IN_THE_FUTURE,
                assertThrows(ScheduleRefusedException.class, () -> service.schedule(TENANT, INSTANCE, past)).reason());

        Instant atBound = NOW.plus(Duration.ofDays(7));
        service.schedule(TENANT, INSTANCE,
                ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, atBound.minusSeconds(60), atBound, SUBMITTER));
        ScheduleCommand beyond = ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, atBound.plusSeconds(1),
                atBound.plusSeconds(60), SUBMITTER);
        assertEquals(ScheduleRefusedException.Reason.NOT_BEFORE_TOO_FAR_AHEAD,
                assertThrows(ScheduleRefusedException.class, () -> service.schedule(TENANT, INSTANCE, beyond)).reason(),
                "seven days ahead is the default max lead; one second more is refused");
        assertEquals(1, rows.rows.size(), "only the schedule at the bound was written");
    }

    @Test
    void notAfterIsBoundedByTheSameHorizonAsNotBefore() {
        // OR-122 (2) "at most 7 days ahead", W3 review W3B-4: the whole window lies within the horizon
        Instant horizon = NOW.plus(Duration.ofDays(7));
        ScheduleCommand oneSecondLate = ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, OPENS,
                horizon.plusSeconds(1), SUBMITTER);
        assertEquals(ScheduleRefusedException.Reason.NOT_AFTER_TOO_FAR_AHEAD,
                assertThrows(ScheduleRefusedException.class, () -> service.schedule(TENANT, INSTANCE, oneSecondLate)).reason(),
                "a window that closes beyond the horizon would fire weeks late after an outage instead of being MISSED");
        ScheduleCommand absurd = ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, OPENS,
                Instant.parse("+300000-01-01T00:00:00Z"), SUBMITTER);
        assertEquals(ScheduleRefusedException.Reason.NOT_AFTER_TOO_FAR_AHEAD,
                assertThrows(ScheduleRefusedException.class, () -> service.schedule(TENANT, INSTANCE, absurd)).reason(),
                "an instant past PostgreSQL's timestamptz range is a 422 here, never an insert that fails");
        assertEquals(0, rows.rows.size(), "neither was written");
        service.schedule(TENANT, INSTANCE, ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, OPENS, horizon, SUBMITTER));
        assertEquals(1, rows.rows.size(), "notAfter exactly at the horizon is accepted (it is exclusive in the window anyway)");
    }

    @Test
    void maxLeadHasAHardCeilingOfSevenDaysThatConfigurationMayOnlyLower() {
        assertEquals(Duration.ofDays(7), ApplyScheduleProperties.MAX_LEAD_CEILING);
        new ApplyScheduleProperties(Duration.ofDays(7), 100);
        new ApplyScheduleProperties(Duration.ofHours(1), 100);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ApplyScheduleProperties(Duration.ofDays(7).plusSeconds(1), 100),
                "one second over refuses to boot: OR-122 (2) rules 'at most 7 days ahead', it is not a default");
        assertTrue(refused.getMessage().contains("max-lead"), "names the key. Was: " + refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new ApplyScheduleProperties(Duration.ofDays(90), 100));
    }

    @Test
    void maxLeadIsConfigurable() {
        service = build(new ApplyScheduleProperties(Duration.ofHours(1), 100));
        assertEquals(ScheduleRefusedException.Reason.NOT_BEFORE_TOO_FAR_AHEAD,
                assertThrows(ScheduleRefusedException.class,
                        () -> service.schedule(TENANT, INSTANCE, applyAt("manual", null))).reason());
    }

    @Test
    void anApplyOfAGenerationThatIsNotStagedAndARollbackTargetThatIsNotEligibleAreRefusedAtSubmit() {
        targets.staged.remove(9L);
        assertEquals(Reason.GENERATION_NOT_STAGED, assertThrows(ApplyRefusedException.class,
                () -> service.schedule(TENANT, INSTANCE, applyAt("manual", null))).reason());
        assertEquals(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, assertThrows(ApplyRefusedException.class,
                () -> service.schedule(TENANT, INSTANCE, rollbackByGeneration())).reason());
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class, () -> service.schedule(TENANT, INSTANCE,
                ScheduleCommand.rollback(PLACEMENT, UUID.randomUUID(), null, "manual", null, OPENS, CLOSES,
                        SUBMITTER))).reason(), "an applyId that is not this placement's is not found");
        assertEquals(0, rows.rows.size());
    }

    // ---- admit: an ordinary apply, inside the window, under the row's lock ----------------------------------------

    @Test
    void anAdmittedScheduleCallsTheOrdinaryApplyWithTheSubmittersIdentityAndIsMarkedAdmittedWithItsApply() {
        UUID schedule = record(applyAt("restart-all", SUBMITTER.toString()));
        calls.clear();
        UUID applyId = UUID.randomUUID();
        apply.nextApplyId = applyId;

        ScheduleFireResult result = admitAt(schedule, OPENS.plusSeconds(5));

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals(applyId, result.applyId());
        assertEquals(List.of("lock", "submitApply", "admitted"), calls,
                "the row is locked FIRST, the ordinary apply admitted, and only then is the row marked");
        SubmitApplyCommand sent = apply.submitted.get(0);
        assertEquals(PLACEMENT, sent.placementId());
        assertEquals(9L, sent.generation());
        assertEquals("restart-all", sent.strategy());
        assertEquals(SUBMITTER.toString(), sent.outageConfirmedBy(), "the stored confirming principal, never a typed name");
        assertEquals(SUBMITTER, sent.actor(), "the admitted apply's requested_by is the submitter");
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(State.ADMITTED, row.state());
        assertEquals(applyId, row.applyId());
        assertEquals(9L, row.admittedGeneration());
        assertEquals(OPENS.plusSeconds(5), row.resolvedAt());
    }

    @Test
    void aRollbackIsResolvedAndCopiedForwardAtFireAndTheRowRecordsBothNumbers() {
        // W6: a stage of generation 9 landed meanwhile; the rollback to 5 is copied forward as 10, above it.
        rollbackRows.eligible.add(5L);
        UUID schedule = record(ScheduleCommand.rollback(PLACEMENT, null, 5L, "manual", null, OPENS, CLOSES, SUBMITTER));
        calls.clear();
        UUID applyId = UUID.randomUUID();
        apply.nextApplyId = applyId;
        apply.generationOf.put(applyId, 10L);

        ScheduleFireResult result = admitAt(schedule, OPENS);

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals(List.of("lock", "rollbackApply", "observeApply", "admitted"), calls);
        RollbackCommand sent = apply.rolledBack.get(0);
        assertEquals(5L, sent.generation(), "the target resolved AT FIRE is what is copied forward");
        assertNull(sent.applyId());
        assertEquals(SUBMITTER, sent.actor());
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(5L, row.targetGeneration());
        assertEquals(10L, row.admittedGeneration(), "the copy-forward's new number, above whatever was staged meanwhile");
    }

    @Test
    void aRollbackNamedByApplyIsReResolvedAtFireBelowThatApplysGeneration() {
        UUID target = UUID.randomUUID();
        rollbackRows.applyGeneration.put(target, 9L);
        rollbackRows.eligible.add(6L);
        rollbackRows.highestBelow.put(9L, 6L);
        UUID schedule = record(ScheduleCommand.rollback(PLACEMENT, target, null, "manual", null, OPENS, CLOSES, SUBMITTER));
        UUID applyId = UUID.randomUUID();
        apply.nextApplyId = applyId;
        apply.generationOf.put(applyId, 12L);

        admitAt(schedule, OPENS);

        assertEquals(6L, apply.rolledBack.get(0).generation());
        assertEquals(6L, rows.rows.get(schedule).targetGeneration());
        assertEquals(12L, rows.rows.get(schedule).admittedGeneration());
    }

    // ---- OR-125 (5): the fire pre-mints the ApplyId and reconciles a retry --------------------------------------------

    @Test
    void theApplyIdIsAPureFunctionOfTheScheduleAndDiffersFromIt() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertEquals(ScheduleApplyIds.forSchedule(a), ScheduleApplyIds.forSchedule(a), "the same every attempt");
        assertFalse(ScheduleApplyIds.forSchedule(a).equals(ScheduleApplyIds.forSchedule(b)), "one per schedule");
        assertFalse(ScheduleApplyIds.forSchedule(a).equals(a), "never the schedule's own id");
    }

    @Test
    void everyFireAdmitsUnderTheIdPreMintedFromTheScheduleOnEveryAttempt() {
        UUID schedule = record(applyAt("manual", null));
        apply.failure = new ApplyRefusedException(Reason.SCOPE_BUSY);
        admitAt(schedule, OPENS);                                      // a busy fence defers
        apply.failure = null;
        admitAt(schedule, OPENS.plusSeconds(15));                      // the next tick admits

        assertEquals(List.of(ScheduleApplyIds.forSchedule(schedule), ScheduleApplyIds.forSchedule(schedule)),
                apply.reservedSeen, "a retry admits under the SAME id, so the engine can recognise it as its own");
    }

    @Test
    void aScheduledRollbackIsAdmittedUnderThePreMintedIdToo() {
        rollbackRows.eligible.add(5L);
        UUID schedule = record(rollbackByGeneration());
        apply.generationOf.put(apply.nextApplyId, 10L);

        admitAt(schedule, OPENS);

        assertEquals(List.of(ScheduleApplyIds.forSchedule(schedule)), apply.reservedSeen);
    }

    /** What an earlier fire left behind: the engine's commit, and no move of the schedule row. */
    private ScheduleTargets.AdmittedRequest leftBehind(UUID schedule, long generation, Long derivedFrom, Instant at) {
        ScheduleTargets.AdmittedRequest request = new ScheduleTargets.AdmittedRequest(
                ScheduleApplyIds.forSchedule(schedule), PLACEMENT, generation, at, derivedFrom);
        targets.admittedUnder.put(request.applyId(), request);
        return request;
    }

    @Test
    void aRetryFindsTheApplyAnEarlierFireAdmittedAndMarksTheScheduleAdmittedWithoutAskingTheEngineAgain() {
        UUID schedule = record(applyAt("manual", null));
        calls.clear();
        leftBehind(schedule, 9L, null, OPENS.plusSeconds(3));

        ScheduleFireResult result = admitAt(schedule, OPENS.plusSeconds(15));

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals(ScheduleApplyIds.forSchedule(schedule), result.applyId());
        assertEquals(List.of("lock", "admitted"), calls, "the engine is not asked a second time");
        assertEquals(List.of(), apply.submitted);
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(State.ADMITTED, row.state());
        assertEquals(ScheduleApplyIds.forSchedule(schedule), row.applyId());
        assertEquals(9L, row.admittedGeneration());
        assertEquals(OPENS.plusSeconds(3), row.resolvedAt(), "stamped when the apply was ADMITTED, not when the retry noticed");
    }

    @Test
    void aRetryAfterTheWindowClosedStillRecordsTheApplyThatRanInsideItAndNeverMissesIt() {
        UUID schedule = record(applyAt("manual", null));
        leftBehind(schedule, 9L, null, CLOSES.minusSeconds(1));

        ScheduleFireResult viaMiss = missAt(schedule, CLOSES.plusSeconds(600));

        assertEquals(Outcome.ADMITTED, viaMiss.outcome(), "the firer found the window closed and asked for a miss: the apply ran");
        assertEquals(State.ADMITTED, rows.rows.get(schedule).state());
        assertEquals(CLOSES.minusSeconds(1), rows.rows.get(schedule).resolvedAt());
    }

    @Test
    void aRevocationFoundAfterTheApplyRanDoesNotRewriteHistoryEither() {
        UUID schedule = record(applyAt("manual", null));
        leftBehind(schedule, 9L, null, OPENS.plusSeconds(1));

        ScheduleFireResult refused = refuseAt(schedule, ScheduleReason.AUTHORITY_REVOKED, OPENS.plusSeconds(20));

        assertEquals(Outcome.ADMITTED, refused.outcome(), "the submitter held the verbs when it was admitted");
        assertEquals(State.ADMITTED, rows.rows.get(schedule).state());
    }

    @Test
    void aRollbacksReconciliationRecordsTheTargetItCopiedForwardAndTheGenerationItMinted() {
        rollbackRows.eligible.add(5L);
        UUID schedule = record(rollbackByGeneration());
        leftBehind(schedule, 10L, 5L, OPENS.plusSeconds(2));
        rollbackRows.eligible.clear();                 // the rollback ran, so 5 is no longer an eligible TARGET: do not ask

        ScheduleFireResult result = admitAt(schedule, OPENS.plusSeconds(30));

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals(List.of(), apply.rolledBack, "nothing is resolved or copied forward a second time");
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(5L, row.targetGeneration());
        assertEquals(10L, row.admittedGeneration());
    }

    @Test
    void aGuardOneCopyForwardThatMintedNothingStillReconcilesToTheTargetTheScheduleNamedW5A1() {
        // OR-33 guard 1: the latest generation already had the target's content, so the engine admitted THAT generation - an
        // edit (derived_from NULL) - under the pre-minted id. The crash left the row PENDING; reconcile must not throw, now or
        // on any later tick, miss or cancel.
        rollbackRows.eligible.add(5L);
        UUID schedule = record(rollbackByGeneration());
        leftBehind(schedule, 6L, null, OPENS.plusSeconds(2));
        rollbackRows.eligible.clear();

        ScheduleFireResult result = admitAt(schedule, OPENS.plusSeconds(30));

        assertEquals(Outcome.ADMITTED, result.outcome());
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(5L, row.targetGeneration(), "the target the schedule named");
        assertEquals(6L, row.admittedGeneration(), "the generation the engine actually admitted");
        assertEquals(List.of(), apply.rolledBack);
    }

    @Test
    void aGuardOneCopyOfSomeOtherGenerationRecordsTheScheduleNamedTargetNotTheCopysProvenance() {
        rollbackRows.eligible.add(5L);
        UUID schedule = record(rollbackByGeneration());
        leftBehind(schedule, 8L, 3L, OPENS.plusSeconds(2));                    // a copy of 3 whose content equals 5's

        assertEquals(Outcome.ADMITTED, admitAt(schedule, OPENS.plusSeconds(30)).outcome());

        assertEquals(5L, rows.rows.get(schedule).targetGeneration());
        assertEquals(8L, rows.rows.get(schedule).admittedGeneration());
    }

    @Test
    void aRollbackNamedByApplyWhoseCopyWasDerivedRecordsTheDerivationAndOneWithNoneIsReResolvedW5A1() {
        UUID anchor = UUID.randomUUID();
        rollbackRows.applyGeneration.put(anchor, 9L);
        rollbackRows.eligible.add(6L);
        rollbackRows.highestBelow.put(9L, 6L);
        UUID derived = record(ScheduleCommand.rollback(PLACEMENT, anchor, null, "manual", null, OPENS, CLOSES, SUBMITTER));
        leftBehind(derived, 12L, 4L, OPENS.plusSeconds(2));
        assertEquals(Outcome.ADMITTED, admitAt(derived, OPENS.plusSeconds(30)).outcome());
        assertEquals(4L, rows.rows.get(derived).targetGeneration(), "the copy's provenance wins over a re-resolution");

        UUID second = UUID.randomUUID();
        nextId = second;
        clockNow = NOW;
        record(ScheduleCommand.rollback(PLACEMENT, anchor, null, "manual", null, OPENS, CLOSES, SUBMITTER));
        leftBehind(second, 12L, null, OPENS.plusSeconds(2));                    // guard 1: no provenance
        assertEquals(Outcome.ADMITTED, admitAt(second, OPENS.plusSeconds(30)).outcome());
        assertEquals(6L, rows.rows.get(second).targetGeneration(), "re-resolved by apply id");
        assertEquals(12L, rows.rows.get(second).admittedGeneration());
    }

    @Test
    void aRollbackWhoseApplyTargetCannotBeReResolvedRecordsTheGenerationItAppliedAndDoesNotWedgeW5A1() {
        UUID anchor = UUID.randomUUID();
        rollbackRows.applyGeneration.put(anchor, 9L);
        rollbackRows.highestBelow.put(9L, 6L);
        UUID schedule = record(ScheduleCommand.rollback(PLACEMENT, anchor, null, "manual", null, OPENS, CLOSES, SUBMITTER));
        rollbackRows.applyGeneration.clear();                                    // by fire the anchor is gone: NOT_FOUND
        leftBehind(schedule, 12L, null, OPENS.plusSeconds(2));

        ScheduleRefusedException cancelled = assertThrows(ScheduleRefusedException.class,
                () -> service.cancel(TENANT, INSTANCE, schedule, OTHER));

        assertEquals(ScheduleRefusedException.Reason.NOT_PENDING, cancelled.reason(),
                "a cancel on the crashed row reconciles and refuses; it is not a 500");
        assertEquals(State.ADMITTED, rows.rows.get(schedule).state());
        assertEquals(12L, rows.rows.get(schedule).targetGeneration());
    }

    @Test
    void anApplyUnderTheIdThatIsNotTheSchedulesClosesTheScheduleRefusedWithAClosedReasonAndIsNeverRecordedAsItsOwn() {
        // Fable F-8: it used to throw, which left the row PENDING forever (every later admit, miss and cancel reconciles first).
        UUID schedule = record(applyAt("manual", null));
        leftBehind(schedule, 8L, null, OPENS.plusSeconds(1));                   // another generation
        ScheduleFireResult otherGeneration = admitAt(schedule, OPENS.plusSeconds(15));
        assertEquals(Outcome.REFUSED, otherGeneration.outcome());
        assertEquals(ScheduleReason.GENERATION_SUPERSEDED, otherGeneration.reason());
        assertEquals(State.REFUSED, rows.rows.get(schedule).state(), "unknown is not admitted, and no longer wedged");
        assertNull(rows.rows.get(schedule).applyId());

        UUID second = UUID.randomUUID();
        nextId = second;
        clockNow = NOW;
        record(applyAt("manual", null));
        targets.admittedUnder.put(ScheduleApplyIds.forSchedule(second), new ScheduleTargets.AdmittedRequest(
                ScheduleApplyIds.forSchedule(second), UUID.randomUUID(), 9L, OPENS, null));   // another placement
        ScheduleFireResult otherPlacement = admitAt(second, OPENS.plusSeconds(15));
        assertEquals(Outcome.REFUSED, otherPlacement.outcome());
        assertEquals(ScheduleReason.TARGET_GONE, otherPlacement.reason());
        assertEquals(State.REFUSED, rows.rows.get(second).state());
        assertEquals(ScheduleFireResult.notPending().outcome(), admitAt(second, OPENS.plusSeconds(30)).outcome(),
                "closed: a later tick finds nothing to do");
    }

    @Test
    void noApplyUnderTheIdIsTheOrdinaryFireAndTheJournalIsAskedExactlyOnceAboutIt() {
        UUID schedule = record(applyAt("manual", null));

        admitAt(schedule, OPENS);

        assertEquals(List.of(ScheduleApplyIds.forSchedule(schedule)), targets.askedAbout);
        assertEquals(State.ADMITTED, rows.rows.get(schedule).state());
    }

    @Test
    void aCancelThatFindsTheApplyAlreadyAdmittedReconcilesAndRefusesAsNotPending() {
        UUID schedule = record(applyAt("manual", null));
        leftBehind(schedule, 9L, null, OPENS.plusSeconds(1));

        ScheduleRefusedException refused = assertThrows(ScheduleRefusedException.class,
                () -> service.cancel(TENANT, INSTANCE, schedule, OTHER));

        assertEquals(ScheduleRefusedException.Reason.NOT_PENDING, refused.reason());
        assertEquals(State.ADMITTED, rows.rows.get(schedule).state(), "the history says an apply ran, not that it was cancelled");
    }

    @Test
    void beforeTheWindowOpensNothingIsAdmittedAndTheScheduleStaysPending() {
        UUID schedule = record(applyAt("manual", null));
        calls.clear();

        ScheduleFireResult result = admitAt(schedule, OPENS.minusMillis(1));

        assertEquals(Outcome.DEFERRED, result.outcome());
        assertEquals(State.PENDING, rows.rows.get(schedule).state());
        assertEquals(List.of("lock"), calls, "no apply, no write");
    }

    @Test
    void atOrAfterNotAfterTheScheduleIsMissedAndNothingIsAdmittedW3() {
        UUID schedule = record(applyAt("manual", null));
        calls.clear();

        ScheduleFireResult result = admitAt(schedule, CLOSES);

        assertEquals(Outcome.MISSED, result.outcome());
        assertEquals(ScheduleReason.WINDOW_MISSED, result.reason());
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(State.MISSED, row.state());
        assertEquals(ScheduleReason.WINDOW_MISSED, row.reason());
        assertEquals(CLOSES, row.resolvedAt());
        assertEquals(List.of("lock", "close"), calls, "never runs outside the window");
    }

    @Test
    void aBusyFenceDefersAndLeavesTheScheduleUntouchedForTheNextTick() {
        UUID schedule = record(applyAt("manual", null));
        for (Reason busy : List.of(Reason.SCOPE_BUSY, Reason.STOP_IN_FLIGHT, Reason.CONCURRENT_ADMISSION)) {
            apply.failure = new ApplyRefusedException(busy);
            ScheduleFireResult result = admitAt(schedule, OPENS);
            assertEquals(Outcome.DEFERRED, result.outcome(), busy.name());
            assertEquals(State.PENDING, rows.rows.get(schedule).state(), busy.name());
        }
        apply.failure = null;
        assertEquals(Outcome.ADMITTED, admitAt(schedule, OPENS.plusSeconds(30)).outcome(),
                "the same schedule runs on a later tick once the fence is free");
    }

    @Test
    void aRefusalThatWillNotChangeClosesTheScheduleWithItsReasonFromTheClosedSet() {
        Map<Reason, ScheduleReason> expected = new LinkedHashMap<>();
        expected.put(Reason.GENERATION_NOT_ABOVE_WATERMARK, ScheduleReason.GENERATION_SUPERSEDED);   // W5
        expected.put(Reason.NOT_FOUND, ScheduleReason.TARGET_GONE);
        expected.put(Reason.GENERATION_NOT_STAGED, ScheduleReason.GENERATION_NOT_STAGED);
        expected.put(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE, ScheduleReason.ROLLBACK_TARGET_NOT_ELIGIBLE);
        for (Map.Entry<Reason, ScheduleReason> e : expected.entrySet()) {
            rows.rows.clear();
            clockNow = NOW;   // admitAt moved the data plane's clock to the window; a new schedule is submitted before it opens
            UUID schedule = record(applyAt("manual", null));
            apply.failure = new ApplyRefusedException(e.getKey());

            ScheduleFireResult result = admitAt(schedule, OPENS);

            assertEquals(Outcome.REFUSED, result.outcome(), e.getKey().name());
            assertEquals(e.getValue(), result.reason(), e.getKey().name());
            assertEquals(State.REFUSED, rows.rows.get(schedule).state());
            assertEquals(e.getValue(), rows.rows.get(schedule).reason());
            nextId = UUID.randomUUID();
        }
    }

    @Test
    void aRollbackWhoseTargetIsNoLongerEligibleAtFireIsRefusedWithoutCallingTheEngine() {
        rollbackRows.eligible.add(5L);
        UUID schedule = record(rollbackByGeneration());
        rollbackRows.eligible.clear();
        calls.clear();

        ScheduleFireResult result = admitAt(schedule, OPENS);

        assertEquals(Outcome.REFUSED, result.outcome());
        assertEquals(ScheduleReason.ROLLBACK_TARGET_NOT_ELIGIBLE, result.reason());
        assertEquals(List.of(), apply.rolledBack, "nothing was copied forward");
    }

    @Test
    void aScheduleThatIsNoLongerPendingIsLeftAloneWhoeverGotThereFirst() {
        UUID schedule = record(applyAt("manual", null));
        service.cancel(TENANT, INSTANCE, schedule, OTHER);
        calls.clear();

        assertEquals(Outcome.NOT_PENDING, admitAt(schedule, OPENS).outcome());
        assertEquals(Outcome.NOT_PENDING, admitAt(UUID.randomUUID(), OPENS).outcome(), "absent: the same answer");
        assertEquals(List.of("lock", "lock"), calls, "no apply was admitted");
        assertEquals(State.CANCELLED, rows.rows.get(schedule).state());
    }

    @Test
    void anUnexpectedFailureOfTheEnginePropagatesAndLeavesTheRowPending() {
        UUID schedule = record(applyAt("manual", null));
        apply.unexpected = new IllegalStateException("no ApplyExecutor is configured");

        assertThrows(IllegalStateException.class, () -> admitAt(schedule, OPENS));
        assertEquals(State.PENDING, rows.rows.get(schedule).state(), "retried on the next tick, closed MISSED at notAfter");
    }

    // ---- refuse and miss: the gateway's two closures -----------------------------------------------------------------

    @Test
    void theGatewayCanRefuseADueScheduleBecauseItsSubmitterLostTheVerbW4() {
        UUID schedule = record(applyAt("manual", null));
        calls.clear();

        ScheduleFireResult result = refuseAt(schedule, ScheduleReason.AUTHORITY_REVOKED, OPENS);

        assertEquals(Outcome.REFUSED, result.outcome());
        assertEquals(ScheduleReason.AUTHORITY_REVOKED, rows.rows.get(schedule).reason());
        assertEquals(State.REFUSED, rows.rows.get(schedule).state());
        assertEquals(List.of("lock", "close"), calls);
        assertEquals(Outcome.NOT_PENDING, refuseAt(schedule, ScheduleReason.AUTHORITY_REVOKED, OPENS).outcome(),
                "a second refusal is a no-op");
    }

    @Test
    void refuseOnlyTakesAReasonThatIsNotMissedAndMissOnlyAfterTheWindowClosed() {
        UUID schedule = record(applyAt("manual", null));
        assertThrows(IllegalArgumentException.class,
                () -> refuseAt(schedule, ScheduleReason.WINDOW_MISSED, OPENS),
                "MISSED is miss()'s, with its own state");
        assertThrows(IllegalStateException.class, () -> missAt(schedule, CLOSES.minusMillis(1)),
                "a schedule whose window is still open is not missed");
        assertEquals(Outcome.MISSED, missAt(schedule, CLOSES).outcome());
        assertEquals(Outcome.NOT_PENDING, missAt(schedule, CLOSES).outcome());
    }

    @Test
    void theWindowIsDecidedWithTheDataPlanesClockReadAfterTheLockNotWithAnEarlierReading() {
        // W3 review W3B-1: the firer read 02:29:59.990 (inside [02:00, 02:30)), then waited on a control-plane round trip and on
        // the row lock; by the time the data plane holds the lock it is 02:30:00.030. There is nothing for the caller to pass:
        // the data plane reads its own clock once it holds the lock.
        Instant closes = OPENS.plusSeconds(1800);
        UUID schedule = record(ScheduleCommand.apply(PLACEMENT, 9L, "manual", null, OPENS, closes, SUBMITTER));
        calls.clear();
        clockNow = closes.minusMillis(10);
        rows.onLock = () -> clockNow = closes.plusMillis(30);

        ScheduleFireResult result = service.admit(TENANT, schedule);

        assertEquals(Outcome.MISSED, result.outcome(), "decided after the lock, against the clock as it then was");
        assertEquals(List.of(), apply.submitted, "an ordinary apply was NOT admitted after the window closed");
        assertEquals(List.of("lock", "close"), calls);
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(State.MISSED, row.state());
        assertEquals(closes.plusMillis(30), row.resolvedAt(), "resolved_at is the time of the decision, not of a stale reading");
    }

    @Test
    void anAdmissionRecordsTheLockedClockAsResolvedAtEvenWhenTheLockWasWaitedFor() {
        UUID schedule = record(applyAt("manual", null));
        clockNow = OPENS;
        rows.onLock = () -> clockNow = OPENS.plusSeconds(7);

        ScheduleFireResult result = service.admit(TENANT, schedule);

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals(OPENS.plusSeconds(7), rows.rows.get(schedule).resolvedAt());
    }

    // ---- cancel -------------------------------------------------------------------------------------------------------

    @Test
    void anyPrincipalCancelsAPendingScheduleOfThisInstanceAndTheRowRecordsWho() {
        UUID schedule = record(applyAt("manual", null));
        clockNow = NOW.plusSeconds(3600);

        ScheduleView view = service.cancel(TENANT, INSTANCE, schedule, OTHER);

        assertEquals("CANCELLED", view.state());
        ScheduleRecord row = rows.rows.get(schedule);
        assertEquals(OTHER, row.cancelledBy(), "not only the submitter");
        assertEquals(clockNow, row.resolvedAt());
        assertEquals(List.of(), apply.submitted, "cancelling admits nothing");
    }

    @Test
    void cancelAfterAnyOtherEndIsNotPendingAndAnotherInstancesScheduleIsNotFound() {
        UUID schedule = record(applyAt("manual", null));
        admitAt(schedule, OPENS);
        assertEquals(ScheduleRefusedException.Reason.NOT_PENDING,
                assertThrows(ScheduleRefusedException.class, () -> service.cancel(TENANT, INSTANCE, schedule, OTHER)).reason(),
                "cancel after admission is a 409");
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.cancel(TENANT, UUID.randomUUID(), schedule, OTHER)).reason(),
                "the same schedule id named through another instance is not found");
        assertEquals(Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> service.cancel(UUID.randomUUID(), INSTANCE, schedule, OTHER)).reason(),
                "and through another tenant");
        assertEquals(State.ADMITTED, rows.rows.get(schedule).state());
    }

    // ---- the firer's enumeration and the list ---------------------------------------------------------------------

    @Test
    void theTenantsWithDueSchedulesAreTheRowsOwnAndDueMapsToWhatTheFirerNeeds() {
        rows.tenants = List.of(TENANT);
        assertEquals(List.of(TENANT), service.tenantsWithDueSchedules(OPENS));
        assertEquals(List.of(OPENS), rows.dueTenantsAsked);

        UUID schedule = record(applyAt("rolling-no-validation", null));
        List<DueSchedule> due = service.due(TENANT, OPENS);
        assertEquals(1, due.size());
        DueSchedule d = due.get(0);
        assertEquals(schedule, d.scheduleId());
        assertEquals(INSTANCE, d.instanceId());
        assertEquals(SUBMITTER, d.requestedBy());
        assertEquals(ScheduleCommand.Operation.APPLY, d.operation());
        assertEquals("rolling-no-validation", d.strategy());
        assertFalse(d.expiredAt(CLOSES.minusMillis(1)));
        assertTrue(d.expiredAt(CLOSES));
        assertEquals(100, rows.dueLimitAsked, "bounded by sm.dp.apply.schedule.due-batch");
    }

    @Test
    void listIsScopedToTheInstanceNewestFirstAndDoesNotCarryTheSubmittersIdentity() {
        UUID first = record(applyAt("manual", null));
        clockNow = NOW.plusSeconds(10);
        nextId = UUID.randomUUID();
        UUID second = record(applyAt("manual", null));

        List<ScheduleView> listed = service.list(TENANT, INSTANCE, 50);

        assertEquals(List.of(second, first), listed.stream().map(ScheduleView::scheduleId).toList());
        assertEquals("PENDING", listed.get(0).state());
        assertEquals(List.of(), service.list(TENANT, UUID.randomUUID(), 50), "another instance's list is empty");
        assertEquals(List.of(), service.list(UUID.randomUUID(), INSTANCE, 50), "another tenant's list is empty");
        assertEquals(1, service.list(TENANT, INSTANCE, 1).size());
        assertSame(null, listed.get(0).reason());
    }

    // ---- fakes ----------------------------------------------------------------------------------------------------

    private static final class FakeRows implements ScheduleRows {
        final List<String> calls;
        final Map<UUID, ScheduleRecord> rows = new LinkedHashMap<>();
        List<UUID> tenants = List.of();
        final List<Instant> dueTenantsAsked = new ArrayList<>();
        int dueLimitAsked;
        long stamp = 1000;

        FakeRows(List<String> calls) {
            this.calls = calls;
        }

        @Override public long insert(ScheduleRecord row) {
            calls.add("insert");
            rows.put(row.scheduleId(), row);
            return ++stamp;
        }

        private Optional<ScheduleRecord> scoped(UUID tenantId, UUID scheduleId) {
            return Optional.ofNullable(rows.get(scheduleId)).filter(r -> r.tenantId().equals(tenantId));
        }

        /** Runs when the row lock is granted: stands for the time a firer waits behind a cancel or a second firer. */
        Runnable onLock = () -> { };

        @Override public Optional<ScheduleRecord> lock(UUID tenantId, UUID scheduleId) {
            calls.add("lock");
            onLock.run();
            return scoped(tenantId, scheduleId);
        }

        @Override public Optional<ScheduleRecord> lockOfInstance(UUID tenantId, UUID instanceId, UUID scheduleId) {
            calls.add("lock");
            return scoped(tenantId, scheduleId).filter(r -> r.instanceId().equals(instanceId));
        }

        @Override public Optional<ScheduleRecord> find(UUID tenantId, UUID instanceId, UUID scheduleId) {
            return scoped(tenantId, scheduleId).filter(r -> r.instanceId().equals(instanceId));
        }

        @Override public List<ScheduleRecord> list(UUID tenantId, UUID instanceId, int limit) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.instanceId().equals(instanceId))
                    .sorted(Comparator.comparing(ScheduleRecord::requestedAt).reversed())
                    .limit(limit).toList();
        }

        @Override public List<UUID> dueTenants(Instant now) {
            dueTenantsAsked.add(now);
            return tenants;
        }

        @Override public List<ScheduleRecord> due(UUID tenantId, Instant now, int limit) {
            dueLimitAsked = limit;
            return rows.values().stream().filter(r -> r.tenantId().equals(tenantId) && r.state() == State.PENDING
                    && !r.notBefore().isAfter(now)).limit(limit).toList();
        }

        private int move(UUID tenantId, UUID scheduleId, java.util.function.UnaryOperator<ScheduleRecord> change) {
            ScheduleRecord r = rows.get(scheduleId);
            if (r == null || !r.tenantId().equals(tenantId) || r.state() != State.PENDING) {
                return 0;
            }
            rows.put(scheduleId, change.apply(r));
            return 1;
        }

        @Override public int admitted(UUID tenantId, UUID scheduleId, UUID applyId, Long targetGeneration,
                long admittedGeneration, Instant at) {
            calls.add("admitted");
            return move(tenantId, scheduleId, r -> new ScheduleRecord(r.tenantId(), r.scheduleId(), r.instanceId(),
                    r.placementId(), r.operation(), r.generation(), r.rollbackApplyId(), r.rollbackGeneration(),
                    r.strategy(), r.outageConfirmedBy(), r.notBefore(), r.notAfter(), r.requestedBy(), r.requestedAt(),
                    State.ADMITTED, null, applyId, targetGeneration, admittedGeneration, null, at, ++stamp));
        }

        @Override public int close(UUID tenantId, UUID scheduleId, State state, ScheduleReason reason, Instant at) {
            calls.add("close");
            return move(tenantId, scheduleId, r -> new ScheduleRecord(r.tenantId(), r.scheduleId(), r.instanceId(),
                    r.placementId(), r.operation(), r.generation(), r.rollbackApplyId(), r.rollbackGeneration(),
                    r.strategy(), r.outageConfirmedBy(), r.notBefore(), r.notAfter(), r.requestedBy(), r.requestedAt(),
                    state, null, null, null, null, reason, at, ++stamp));
        }

        @Override public int cancel(UUID tenantId, UUID scheduleId, UUID cancelledBy, Instant at) {
            calls.add("cancel");
            return move(tenantId, scheduleId, r -> new ScheduleRecord(r.tenantId(), r.scheduleId(), r.instanceId(),
                    r.placementId(), r.operation(), r.generation(), r.rollbackApplyId(), r.rollbackGeneration(),
                    r.strategy(), r.outageConfirmedBy(), r.notBefore(), r.notAfter(), r.requestedBy(), r.requestedAt(),
                    State.CANCELLED, cancelledBy, null, null, null, null, at, ++stamp));
        }
    }

    private static final class FakeTargets implements ScheduleTargets {
        boolean instanceActive = true;
        boolean placementLive = true;
        /** Flipped by the lock, as a committed retirePlacement would be seen once the lock is held (FF-1). */
        boolean placementLiveOnceLocked = true;
        boolean locked;
        final java.util.Set<Long> staged = new java.util.HashSet<>(List.of(9L));
        /** The apply requests that exist in the journal, by id: what an earlier fire left behind before it died (OR-125 (5)). */
        final Map<UUID, ScheduleTargets.AdmittedRequest> admittedUnder = new LinkedHashMap<>();
        final List<UUID> askedAbout = new ArrayList<>();

        @Override public boolean instanceActive(UUID tenantId, UUID instanceId) {
            return instanceActive;
        }

        final java.util.List<String> locks = new java.util.ArrayList<>();
        /** Flipped by the lock, as a committed undeclare would be seen once the lock is held. */
        boolean activeOnceLocked = true;

        @Override public boolean lockInstanceScopeAndCheckActive(UUID tenantId, UUID instanceId) {
            locks.add("scope");
            locked = true;
            return instanceActive && activeOnceLocked;
        }

        @Override public boolean placementLive(UUID tenantId, UUID instanceId, UUID placementId) {
            return placementLive && (!locked || placementLiveOnceLocked);
        }

        @Override public boolean generationStaged(UUID tenantId, UUID instanceId, long generation) {
            return staged.contains(generation);
        }

        @Override public Optional<ScheduleTargets.AdmittedRequest> admittedUnder(UUID tenantId, UUID instanceId,
                UUID applyId) {
            askedAbout.add(applyId);
            return Optional.ofNullable(admittedUnder.get(applyId));
        }
    }

    private static final class FakeRollbackRows implements RollbackTargetRows {
        final java.util.Set<Long> eligible = new java.util.HashSet<>();
        final Map<UUID, Long> applyGeneration = new LinkedHashMap<>();
        final Map<Long, Long> highestBelow = new LinkedHashMap<>();

        @Override public OptionalLong applyGeneration(TenantId tenant, InstanceId instance, PlacementId placement,
                ApplyId apply) {
            Long g = applyGeneration.get(apply.value());
            return g == null ? OptionalLong.empty() : OptionalLong.of(g);
        }

        @Override public OptionalLong highestEligibleBelow(TenantId tenant, InstanceId instance, PlacementId placement,
                long belowExclusive) {
            Long g = highestBelow.get(belowExclusive);
            return g == null ? OptionalLong.empty() : OptionalLong.of(g);
        }

        @Override public boolean isEligible(TenantId tenant, InstanceId instance, PlacementId placement, long generation) {
            return eligible.contains(generation);
        }
    }

    private static final class FakeApply implements ApplyService {
        final List<String> calls;
        final List<SubmitApplyCommand> submitted = new ArrayList<>();
        final List<RollbackCommand> rolledBack = new ArrayList<>();
        final Map<UUID, Long> generationOf = new LinkedHashMap<>();
        /** The pre-minted id each admission came with (null for the one-argument forms). */
        final List<UUID> reservedSeen = new ArrayList<>();
        UUID nextApplyId = UUID.randomUUID();
        ApplyRefusedException failure;
        RuntimeException unexpected;

        FakeApply(List<String> calls) {
            this.calls = calls;
        }

        @Override public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command,
                UUID reservedApplyId) {
            reservedSeen.add(reservedApplyId);
            return submitApply(organizationId, instanceId, command);
        }

        @Override public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command,
                UUID reservedApplyId) {
            reservedSeen.add(reservedApplyId);
            return rollbackApply(organizationId, instanceId, command);
        }

        @Override public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command) {
            calls.add("submitApply");
            maybeFail();
            submitted.add(command);
            return new ApplyAcceptedView(nextApplyId, 42L);
        }

        @Override public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command) {
            calls.add("rollbackApply");
            maybeFail();
            rolledBack.add(command);
            return new ApplyAcceptedView(nextApplyId, 43L);
        }

        private void maybeFail() {
            if (unexpected != null) {
                throw unexpected;
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Override public List<ApplyObservationView> observeApply(UUID organizationId, UUID instanceId, ObserveQuery query) {
            calls.add("observeApply");
            return List.of(new ApplyObservationView(query.applyId(), "apply", PLACEMENT,
                    generationOf.get(query.applyId()), NOW, "REQUESTED", false, 43L, List.of()));
        }

        @Override public ApplyAcceptedView restoreRuntime(UUID organizationId, UUID instanceId, RestoreRuntimeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override public ApplyAcceptedView setLogLevel(UUID organizationId, UUID instanceId, SetLogLevelCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override public ApplyAcceptedView stopInstance(UUID organizationId, UUID instanceId, StopCommand command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoTransactions implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override public void commit(TransactionStatus status) {
        }

        @Override public void rollback(TransactionStatus status) {
        }
    }
}
