package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
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
import com.solace.sm.dp.config.RollbackTargetRows;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.schedule.ApplyScheduleProperties;
import com.solace.sm.dp.schedule.ApplyScheduleServiceImpl;
import com.solace.sm.dp.schedule.ScheduleApplyIds;
import com.solace.sm.dp.schedule.ScheduleTargets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 012 T7 - the data plane's half of {@code scheduled} over a real PostgreSQL ({@code ops-501.md} section 6.3), through
 * the real repository and the one service, with the engine replaced by a fake that writes a real {@code apply_request} (so
 * the foreign key from the schedule to its apply is exercised): the round trip, the cross-organization enumeration and its
 * bounds, the row lock that makes two firers - or a firer and a cancel - take turns, a deferral that stays PENDING in the
 * database, and the isolation of organizations. The engine itself, and W1-W6 end to end with a fake clock, are the launcher's
 * ({@code ScheduleEndpointsIT}).
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyScheduleServiceIT {

    private static final Instant OPENS = Instant.parse("2030-01-02T02:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-01-02T04:00:00Z");

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;
    private static JdbcApplyScheduleRepository rows;
    private static TransactionTemplate tx;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
        rows = new JdbcApplyScheduleRepository(runtime);
        tx = new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
    }

    private record Live(UUID tenant, UUID instance, UUID placement) {
    }

    private static Live live() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        UUID placement = InventoryFixtures.placement(tenant, instance, target);
        ApplyRows.insertScope(runtime, tenant, instance);
        return new Live(tenant, instance, placement);
    }

    private static final class Rig {
        final Live live;
        final FakeApply apply;
        final ApplyScheduleServiceImpl service;
        final FakeRollbackRows rollbackRows = new FakeRollbackRows();
        volatile Instant now = Instant.parse("2030-01-01T12:00:00Z");

        Rig(Live live) {
            this(live, ApplyScheduleProperties.defaults());
        }

        Rig(Live live, ApplyScheduleProperties properties) {
            this.live = live;
            this.apply = new FakeApply(live, () -> this.now);
            ScheduleTargets targets = new ScheduleTargets() {
                @Override public boolean instanceActive(UUID tenantId, UUID instanceId) {
                    return true;
                }

                @Override public boolean lockInstanceScopeAndCheckActive(UUID tenantId, UUID instanceId) {
                    return true;
                }

                @Override public boolean placementLive(UUID tenantId, UUID instanceId, UUID placementId) {
                    return placementId.equals(live.placement());
                }

                @Override public boolean generationStaged(UUID tenantId, UUID instanceId, long generation) {
                    return generation == 9L;
                }

                @Override public java.util.Optional<AdmittedRequest> admittedUnder(UUID tenantId, UUID instanceId,
                        UUID applyId) {
                    // the journal's committed row, read as production reads it (I5: this tenant and this instance)
                    return runtime.query("SELECT placement_id, generation, requested_at FROM sm_data.apply_request"
                                    + " WHERE tenant_id = :t AND instance_id = :i AND apply_id = :a",
                            new MapSqlParameterSource().addValue("t", tenantId).addValue("i", instanceId)
                                    .addValue("a", applyId),
                            (rs, n) -> new AdmittedRequest(applyId, rs.getObject("placement_id", UUID.class),
                                    rs.getLong("generation"), rs.getTimestamp("requested_at").toInstant(),
                                    apply.derivedFrom.get(applyId))).stream().findFirst();
                }
            };
            this.service = new ApplyScheduleServiceImpl(tx, rows, targets, apply, new RollbackTargets(rollbackRows),
                    properties, new Clock() {
                        @Override public ZoneOffset getZone() {
                            return ZoneOffset.UTC;
                        }

                        @Override public Clock withZone(java.time.ZoneId zone) {
                            return this;
                        }

                        @Override public Instant instant() {
                            return now;
                        }
                    }, UUID::randomUUID);
        }

        /** The data plane decides with ITS clock, read after the row lock (W3 review W3B-1): set it to the moment meant. */
        ScheduleFireResult admitAt(UUID tenant, UUID schedule, Instant at) {
            now = at;
            return service.admit(tenant, schedule);
        }

        ScheduleFireResult refuseAt(UUID tenant, UUID schedule, ScheduleReason reason, Instant at) {
            now = at;
            return service.refuse(tenant, schedule, reason);
        }

        ScheduleFireResult missAt(UUID tenant, UUID schedule, Instant at) {
            now = at;
            return service.miss(tenant, schedule);
        }

        UUID schedule(ScheduleCommand command) {
            ScheduleAcceptedView accepted = service.schedule(live.tenant(), live.instance(), command);
            return accepted.scheduleId();
        }

        ScheduleCommand apply(String strategy, UUID by) {
            return ScheduleCommand.apply(live.placement(), 9L, strategy, "restart-all".equals(strategy) ? by.toString() : null,
                    OPENS, CLOSES, by);
        }
    }

    private static String stateOf(Live l, UUID schedule) {
        return superuser.queryForObject("SELECT state FROM sm_data.apply_schedule WHERE tenant_id = :t AND schedule_id = :s",
                new MapSqlParameterSource("t", l.tenant()).addValue("s", schedule), String.class);
    }

    // ---- the round trip ------------------------------------------------------------------------------------------------

    @Test
    void aScheduleRoundTripsThroughTheRealRepositoryAndListsNewestFirst() {
        Rig rig = new Rig(live());
        UUID by = UUID.randomUUID();
        UUID first = rig.schedule(rig.apply("manual", by));
        rig.now = rig.now.plusSeconds(60);
        UUID second = rig.schedule(rig.apply("restart-all", by));

        List<ScheduleView> listed = rig.service.list(rig.live.tenant(), rig.live.instance(), 50);

        assertEquals(List.of(second, first), listed.stream().map(ScheduleView::scheduleId).toList());
        ScheduleView head = listed.get(0);
        assertEquals("PENDING", head.state());
        assertEquals("apply", head.operation());
        assertEquals(9L, head.generation());
        assertEquals("restart-all", head.strategy());
        assertEquals(OPENS, head.notBefore());
        assertEquals(CLOSES, head.notAfter());
        assertEquals(rig.live.placement(), head.placementId());
        assertTrue(head.stateVersion() > listed.get(1).stateVersion(), "stamped in commit order");
        assertNull(head.applyId());
        assertNull(head.resolvedAt());
        assertEquals(1, rig.service.list(rig.live.tenant(), rig.live.instance(), 1).size());
        assertEquals(List.of(), rig.service.list(UUID.randomUUID(), rig.live.instance(), 50), "another organization sees none");
    }

    // ---- the one cross-organization read ------------------------------------------------------------------------------

    @Test
    void theEnumerationYieldsOnlyOrganizationsWithAPendingScheduleWhoseWindowHasOpened() {
        Rig due = new Rig(live());
        Rig later = new Rig(live());
        Rig admitted = new Rig(live());
        Rig cancelled = new Rig(live());
        UUID by = UUID.randomUUID();
        due.schedule(due.apply("manual", by));
        later.schedule(ScheduleCommand.apply(later.live.placement(), 9L, "manual", null, CLOSES.plusSeconds(3600),
                CLOSES.plusSeconds(7200), by));
        UUID done = admitted.schedule(admitted.apply("manual", by));
        UUID gone = cancelled.schedule(cancelled.apply("manual", by));
        admitted.admitAt(admitted.live.tenant(), done, OPENS);
        cancelled.service.cancel(cancelled.live.tenant(), cancelled.live.instance(), gone, by);

        List<UUID> tenants = due.service.tenantsWithDueSchedules(OPENS);

        assertTrue(tenants.contains(due.live.tenant()), "a PENDING schedule whose window opened");
        assertFalse(tenants.contains(later.live.tenant()), "its window has not opened");
        assertFalse(tenants.contains(admitted.live.tenant()), "admitted: no longer pending");
        assertFalse(tenants.contains(cancelled.live.tenant()), "cancelled: no longer pending");
        assertEquals(tenants.size(), tenants.stream().distinct().count(), "DISTINCT: one entry per organization");
        assertFalse(due.service.tenantsWithDueSchedules(OPENS.minusSeconds(1)).contains(due.live.tenant()),
                "at notBefore minus a second it is not due yet");
    }

    @Test
    void dueIsOldestWindowFirstScopedToTheOrganizationAndBoundedByTheBatch() {
        Rig rig = new Rig(live(), new ApplyScheduleProperties(Duration.ofDays(7), 2));
        Rig other = new Rig(live());
        UUID by = UUID.randomUUID();
        UUID c = rig.schedule(ScheduleCommand.apply(rig.live.placement(), 9L, "manual", null, OPENS.plusSeconds(30),
                CLOSES, by));
        UUID a = rig.schedule(ScheduleCommand.apply(rig.live.placement(), 9L, "manual", null, OPENS, CLOSES, by));
        UUID b = rig.schedule(ScheduleCommand.apply(rig.live.placement(), 9L, "manual", null, OPENS.plusSeconds(10),
                CLOSES, by));
        other.schedule(other.apply("manual", by));

        List<DueSchedule> due = rig.service.due(rig.live.tenant(), OPENS.plusSeconds(60));

        assertEquals(List.of(a, b), due.stream().map(DueSchedule::scheduleId).toList(),
                "oldest window first, at most two (the batch); the third is read on the next tick");
        assertTrue(due.stream().allMatch(d -> d.instanceId().equals(rig.live.instance())), "only this organization's");
        assertEquals(List.of(), rig.service.due(rig.live.tenant(), OPENS.minusSeconds(1)));
        assertEquals(List.of(a, b, c), new Rig(rig.live).service.due(rig.live.tenant(), OPENS.plusSeconds(60)).stream()
                .map(DueSchedule::scheduleId).toList(), "with the default batch of 100 all three are read, oldest window first");
    }

    // ---- admit: the real rows ----------------------------------------------------------------------------------------

    @Test
    void anAdmittedScheduleIsMarkedWithTheRealApplyItClaimedAndIsNoLongerDue() {
        Rig rig = new Rig(live());
        UUID by = UUID.randomUUID();
        UUID schedule = rig.schedule(rig.apply("manual", by));

        ScheduleFireResult result = rig.admitAt(rig.live.tenant(), schedule, OPENS.plusSeconds(5));

        assertEquals(Outcome.ADMITTED, result.outcome());
        assertEquals("ADMITTED", stateOf(rig.live, schedule));
        ScheduleView view = rig.service.list(rig.live.tenant(), rig.live.instance(), 5).get(0);
        assertEquals(result.applyId(), view.applyId());
        assertEquals(9L, view.admittedGeneration());
        assertEquals(OPENS.plusSeconds(5), view.resolvedAt());
        assertEquals(by.toString(), rig.apply.submitted.get(0).actor().toString(), "the admitted apply is the submitter's");
        assertEquals(List.of(), rig.service.due(rig.live.tenant(), OPENS.plusSeconds(60)));
        assertEquals(Outcome.NOT_PENDING, rig.admitAt(rig.live.tenant(), schedule, OPENS.plusSeconds(6)).outcome(),
                "a second tick finds nothing to do");
        assertEquals(1, rig.apply.submitted.size());
    }

    @Test
    void aScheduledRollbackRecordsTheTargetResolvedAtFireAndTheGenerationItWasCopiedForwardAs() {
        Rig rig = new Rig(live());
        rig.rollbackRows.eligible.add(5L);
        UUID by = UUID.randomUUID();
        UUID schedule = rig.schedule(ScheduleCommand.rollback(rig.live.placement(), null, 5L, "manual", null, OPENS, CLOSES, by));
        assertEquals(List.of(), rig.apply.rolledBack, "nothing is copied forward at submit");
        rig.apply.mintedByRollback = 10L;

        ScheduleFireResult result = rig.admitAt(rig.live.tenant(), schedule, OPENS);

        assertEquals(Outcome.ADMITTED, result.outcome());
        ScheduleView view = rig.service.list(rig.live.tenant(), rig.live.instance(), 5).get(0);
        assertEquals("rollback", view.operation());
        assertEquals(5L, view.rollbackGeneration());
        assertEquals(5L, view.targetGeneration());
        assertEquals(10L, view.admittedGeneration());
    }

    @Test
    void aDeferralLeavesTheRowPendingInTheDatabaseAndALaterTickAdmitsItOrTheWindowClosesIt() {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        rig.apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.SCOPE_BUSY);

        assertEquals(Outcome.DEFERRED, rig.admitAt(rig.live.tenant(), schedule, OPENS).outcome());
        assertEquals("PENDING", stateOf(rig.live, schedule));
        assertEquals(1, rig.service.due(rig.live.tenant(), OPENS.plusSeconds(1)).size(), "still due on the next tick");

        assertEquals(Outcome.MISSED, rig.admitAt(rig.live.tenant(), schedule, CLOSES).outcome(),
                "the window closed while the fence was busy");
        assertEquals("MISSED", stateOf(rig.live, schedule));
        assertEquals(ScheduleReason.WINDOW_MISSED.wire(), rig.service.list(rig.live.tenant(), rig.live.instance(), 1)
                .get(0).reason());
        assertEquals(0, rig.apply.submitted.size(), "never ran outside the window");
    }

    @Test
    void aRefusalThatWillNotChangeIsWrittenWithItsReason() {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        rig.apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.GENERATION_NOT_ABOVE_WATERMARK);

        ScheduleFireResult result = rig.admitAt(rig.live.tenant(), schedule, OPENS);

        assertEquals(Outcome.REFUSED, result.outcome());
        assertEquals("REFUSED", stateOf(rig.live, schedule));
        assertEquals("schedule.generation.superseded",
                rig.service.list(rig.live.tenant(), rig.live.instance(), 1).get(0).reason());
    }

    @Test
    void theGatewaysRefusalAndMissClosureAreWrittenAndAreNoOpsOnceTheScheduleIsClosed() {
        Rig rig = new Rig(live());
        UUID a = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        UUID b = rig.schedule(rig.apply("manual", UUID.randomUUID()));

        assertEquals(Outcome.REFUSED, rig.refuseAt(rig.live.tenant(), a, ScheduleReason.AUTHORITY_REVOKED, OPENS).outcome());
        assertEquals(Outcome.NOT_PENDING, rig.refuseAt(rig.live.tenant(), a, ScheduleReason.AUTHORITY_REVOKED, OPENS).outcome());
        assertEquals(Outcome.MISSED, rig.missAt(rig.live.tenant(), b, CLOSES.plusSeconds(1)).outcome());
        assertEquals("REFUSED", stateOf(rig.live, a));
        assertEquals("MISSED", stateOf(rig.live, b));
        assertEquals(0, rig.apply.submitted.size());
    }

    @Test
    void aCrashAfterTheEnginesCommitLeavesTheScheduleSeenAsAdmittedByTheRetryAndNothingRunsTwice() {
        // OR-125 (5), in production's commit shape (W3 review W3B-5): the engine commits a scheduled rollback in its OWN
        // REQUIRES_NEW transaction; observeApply then fails inside the schedule's transaction, which rolls back ONLY the
        // schedule's own move. The apply is durable and the row still reads PENDING ...
        Rig rig = new Rig(live());
        rig.rollbackRows.eligible.add(5L);
        UUID schedule = rig.schedule(ScheduleCommand.rollback(rig.live.placement(), null, 5L, "manual", null, OPENS, CLOSES,
                UUID.randomUUID()));
        rig.apply.observeFailure = new IllegalStateException("the journal read failed after the engine committed");

        assertThrows(IllegalStateException.class, () -> rig.admitAt(rig.live.tenant(), schedule, OPENS));

        assertEquals(1, rig.apply.rolledBack.size(), "the engine admitted the rollback");
        assertEquals(1, requestRows(rig.live), "and its apply_request is COMMITTED, outside the schedule's rolled-back transaction");
        assertEquals("PENDING", stateOf(rig.live, schedule), "while the schedule row still reads PENDING");

        // ... and the retry does NOT admit a second time and does NOT close the row REFUSED or MISSED: it finds the apply under
        // the id the fire pre-minted and records it.
        rig.apply.observeFailure = null;
        ScheduleFireResult retried = rig.admitAt(rig.live.tenant(), schedule, OPENS.plusSeconds(15));

        assertEquals(Outcome.ADMITTED, retried.outcome());
        assertEquals(ScheduleApplyIds.forSchedule(schedule), retried.applyId());
        assertEquals(1, rig.apply.rolledBack.size(), "the engine was not asked again");
        assertEquals(1, requestRows(rig.live), "exactly one apply exists");
        assertEquals("ADMITTED", stateOf(rig.live, schedule));
        java.util.Map<String, Object> row = superuser.queryForMap("SELECT apply_id, target_generation, admitted_generation,"
                + " resolved_at FROM sm_data.apply_schedule WHERE tenant_id = :t AND schedule_id = :s",
                new MapSqlParameterSource("t", rig.live.tenant()).addValue("s", schedule));
        assertEquals(ScheduleApplyIds.forSchedule(schedule), row.get("apply_id"));
        assertEquals(5L, row.get("target_generation"), "the generation the rollback was copied forward from");
        assertEquals(77L, row.get("admitted_generation"), "the copy-forward the engine minted");
    }

    /** What an earlier fire leaves behind when it dies between the engine's commit and its own record. */
    private static void engineCommitsThenTheFirerDies(Rig rig, UUID schedule) {
        rig.apply.submitApply(rig.live.tenant(), rig.live.instance(), new SubmitApplyCommand(rig.live.placement(), 9L,
                "manual", null, UUID.randomUUID()), ScheduleApplyIds.forSchedule(schedule));
    }

    private static int requestRows(Live l) {
        return superuser.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t",
                new MapSqlParameterSource("t", l.tenant()), Integer.class);
    }

    @Test
    void aRetryAfterACrashRecordsAnAppliedScheduleAsAdmittedWhateverTheNextCallOnItWas() {
        for (String next : List.of("admit", "miss-after-window", "refuse-revoked")) {
            Rig rig = new Rig(live());
            UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
            rig.now = OPENS.plusSeconds(3);
            engineCommitsThenTheFirerDies(rig, schedule);
            assertEquals("PENDING", stateOf(rig.live, schedule), next + ": the crash window");

            ScheduleFireResult result = switch (next) {
                case "admit" -> rig.admitAt(rig.live.tenant(), schedule, OPENS.plusSeconds(15));
                case "miss-after-window" -> rig.missAt(rig.live.tenant(), schedule, CLOSES.plusSeconds(600));
                default -> rig.refuseAt(rig.live.tenant(), schedule, ScheduleReason.AUTHORITY_REVOKED, OPENS.plusSeconds(15));
            };

            assertEquals(Outcome.ADMITTED, result.outcome(), next);
            assertEquals("ADMITTED", stateOf(rig.live, schedule), next);
            assertEquals(1, requestRows(rig.live), next + ": nothing was admitted a second time");
            assertEquals(1, rig.apply.submitted.size(), next + ": the engine was asked once, by the crashed fire");
            Instant resolved = superuser.queryForObject("SELECT resolved_at FROM sm_data.apply_schedule WHERE tenant_id = :t"
                    + " AND schedule_id = :s", new MapSqlParameterSource("t", rig.live.tenant()).addValue("s", schedule),
                    java.sql.Timestamp.class).toInstant();
            assertEquals(OPENS.plusSeconds(3), resolved, next + ": stamped when the engine admitted it");
        }
    }

    @Test
    void aCancelThatArrivesAfterACrashCommitsTheReconciliationAndIsRefusedAsNotPending() {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        rig.now = OPENS.plusSeconds(3);
        engineCommitsThenTheFirerDies(rig, schedule);

        ScheduleRefusedException refused = assertThrows(ScheduleRefusedException.class,
                () -> rig.service.cancel(rig.live.tenant(), rig.live.instance(), schedule, UUID.randomUUID()));

        assertEquals(ScheduleRefusedException.Reason.NOT_PENDING, refused.reason());
        assertEquals("ADMITTED", stateOf(rig.live, schedule),
                "the refusal is thrown AFTER the transaction: the reconciliation is committed, not rolled back with it");
    }

    @Test
    void anApplyRecordedLateIsStampedInsideTheWindowEvenIfTheEngineClockReadItAtTheEdge() {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        rig.now = CLOSES;                                  // the engine's own reading landed on the exclusive edge
        engineCommitsThenTheFirerDies(rig, schedule);

        assertEquals(Outcome.ADMITTED, rig.admitAt(rig.live.tenant(), schedule, CLOSES.plusSeconds(60)).outcome());

        assertEquals("ADMITTED", stateOf(rig.live, schedule), "V025's admitted_inside_window holds: the stamp is clamped, not refused");
    }

    @Test
    void anotherOrganizationNeverSeesTheApplyUnderASchedulesId() {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        rig.now = OPENS.plusSeconds(3);
        engineCommitsThenTheFirerDies(rig, schedule);

        assertEquals(Outcome.NOT_PENDING, rig.admitAt(UUID.randomUUID(), schedule, OPENS.plusSeconds(15)).outcome());
        assertEquals("PENDING", stateOf(rig.live, schedule));
    }

    // ---- who may touch whose ------------------------------------------------------------------------------------------

    @Test
    void anotherOrganizationCannotAdmitOrCancelAScheduleAndSeesItAsAbsent() {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        UUID stranger = UUID.randomUUID();

        assertEquals(Outcome.NOT_PENDING, rig.admitAt(stranger, schedule, OPENS).outcome());
        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> rig.service.cancel(stranger, rig.live.instance(), schedule, UUID.randomUUID())).reason());
        assertEquals(ApplyRefusedException.Reason.NOT_FOUND, assertThrows(ApplyRefusedException.class,
                () -> rig.service.cancel(rig.live.tenant(), UUID.randomUUID(), schedule, UUID.randomUUID())).reason(),
                "the right organization, another instance's path");
        assertEquals("PENDING", stateOf(rig.live, schedule));
        assertEquals(0, rig.apply.submitted.size());
    }

    // ---- the lock: two firers, a firer and a cancel -------------------------------------------------------------------

    @Test
    void twoFirersAdmittingTheSameScheduleAtOnceAdmitItOnce() throws Exception {
        Rig rig = new Rig(live());
        UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
        rig.apply.pauseInsideSubmit = 300;   // widens the window a lock-less implementation would lose in
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<ScheduleFireResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return rig.admitAt(rig.live.tenant(), schedule, OPENS);
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<ScheduleFireResult> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS).outcome());
            }
            assertEquals(1, outcomes.stream().filter(o -> o == Outcome.ADMITTED).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter(o -> o == Outcome.NOT_PENDING).count(), outcomes.toString());
            assertEquals(1, rig.apply.submitted.size(), "exactly one ordinary apply was admitted");
            assertEquals("ADMITTED", stateOf(rig.live, schedule));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCancelRacingTheFireEitherWinsCleanlyAndNeverBoth() throws Exception {
        for (int round = 0; round < 6; round++) {
            Rig rig = new Rig(live());
            UUID schedule = rig.schedule(rig.apply("manual", UUID.randomUUID()));
            rig.apply.pauseInsideSubmit = 50;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<ScheduleFireResult> fire = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return rig.admitAt(rig.live.tenant(), schedule, OPENS);
                });
                Future<Optional<ScheduleView>> cancel = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return Optional.of(rig.service.cancel(rig.live.tenant(), rig.live.instance(), schedule, UUID.randomUUID()));
                    } catch (ScheduleRefusedException e) {
                        assertEquals(ScheduleRefusedException.Reason.NOT_PENDING, e.reason());
                        return Optional.empty();
                    }
                });
                Outcome fired = fire.get(30, TimeUnit.SECONDS).outcome();
                boolean cancelled = cancel.get(30, TimeUnit.SECONDS).isPresent();
                if (cancelled) {
                    assertEquals(Outcome.NOT_PENDING, fired, "round " + round + ": the cancel won, so nothing ran");
                    assertEquals("CANCELLED", stateOf(rig.live, schedule));
                    assertEquals(0, rig.apply.submitted.size());
                } else {
                    assertEquals(Outcome.ADMITTED, fired, "round " + round + ": the fire won, so the cancel is a 409");
                    assertEquals("ADMITTED", stateOf(rig.live, schedule));
                    assertEquals(1, rig.apply.submitted.size());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---- fakes --------------------------------------------------------------------------------------------------------

    private static final class FakeRollbackRows implements RollbackTargetRows {
        final java.util.Set<Long> eligible = new java.util.HashSet<>();

        @Override public OptionalLong applyGeneration(com.solace.sm.dp.apply.model.TenantId tenant,
                com.solace.sm.dp.apply.model.InstanceId instance, com.solace.sm.dp.apply.model.PlacementId placement,
                com.solace.sm.dp.apply.model.ApplyId apply) {
            return OptionalLong.empty();
        }

        @Override public OptionalLong highestEligibleBelow(com.solace.sm.dp.apply.model.TenantId tenant,
                com.solace.sm.dp.apply.model.InstanceId instance, com.solace.sm.dp.apply.model.PlacementId placement,
                long belowExclusive) {
            return OptionalLong.empty();
        }

        @Override public boolean isEligible(com.solace.sm.dp.apply.model.TenantId tenant,
                com.solace.sm.dp.apply.model.InstanceId instance, com.solace.sm.dp.apply.model.PlacementId placement,
                long generation) {
            return eligible.contains(generation);
        }
    }

    /** The engine, replaced: it writes a REAL apply_request so the schedule's foreign key to its apply is exercised. */
    private static final class FakeApply implements ApplyService {
        final Live live;
        final List<SubmitApplyCommand> submitted = new CopyOnWriteArrayList<>();
        final List<RollbackCommand> rolledBack = new CopyOnWriteArrayList<>();
        final AtomicInteger inserted = new AtomicInteger();
        volatile ApplyRefusedException failure;
        volatile long pauseInsideSubmit;
        volatile long mintedByRollback = 77;
        volatile RuntimeException observeFailure;
        private final java.util.Map<UUID, Long> generations = new java.util.concurrent.ConcurrentHashMap<>();
        /**
         * PRODUCTION's commit shape (W3 review W3B-5): the engine's admission commits in its OWN {@code REQUIRES_NEW}
         * transaction while the schedule's row lock is held by the caller's. A plain insert over the shared DataSource would
         * JOIN the caller's transaction and the race tests would measure a shape production does not have.
         */
        private final TransactionTemplate engineTransaction = new TransactionTemplate(tx.getTransactionManager(),
                requiresNew());

        private static org.springframework.transaction.support.DefaultTransactionDefinition requiresNew() {
            org.springframework.transaction.support.DefaultTransactionDefinition d =
                    new org.springframework.transaction.support.DefaultTransactionDefinition();
            d.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return d;
        }

        /** The generation each rollback request was copied forward from, by apply id (what the config of record would say). */
        final java.util.Map<UUID, Long> derivedFrom = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.function.Supplier<Instant> clock;

        FakeApply(Live live, java.util.function.Supplier<Instant> clock) {
            this.live = live;
            this.clock = clock;
        }

        /**
         * The engine under a PRE-MINTED id (OR-125 (5)), in production's commit shape: a request that already exists under the
         * id is answered as it stands and nothing is written; otherwise it is committed in its own transaction, stamped with
         * the data plane's clock, under that id.
         */
        private UUID admitUnder(UUID reserved, long generation) {
            if (failure != null) {
                throw failure;
            }
            if (pauseInsideSubmit > 0) {
                try {
                    Thread.sleep(pauseInsideSubmit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Integer existing = runtime.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t"
                    + " AND apply_id = :a", new MapSqlParameterSource().addValue("t", live.tenant())
                            .addValue("a", reserved), Integer.class);
            if (existing != null && existing > 0) {
                return reserved;
            }
            engineTransaction.executeWithoutResult(status -> runtime.update("INSERT INTO sm_data.apply_request"
                            + " (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
                            + "  requested_by, requested_at, state) VALUES (:a, :t, :i, :p, 'apply', :g, 'manual',"
                            + " 'operator-1', :at, 'REQUESTED')",
                    new MapSqlParameterSource().addValue("a", reserved).addValue("t", live.tenant())
                            .addValue("i", live.instance()).addValue("p", live.placement()).addValue("g", generation)
                            .addValue("at", java.sql.Timestamp.from(clock.get()))));
            generations.put(reserved, generation);
            inserted.incrementAndGet();
            return reserved;
        }

        @Override public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command,
                UUID reservedApplyId) {
            UUID id = admitUnder(reservedApplyId, command.generation());
            submitted.add(command);
            return new ApplyAcceptedView(id, 1L);
        }

        @Override public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command,
                UUID reservedApplyId) {
            UUID id = admitUnder(reservedApplyId, mintedByRollback);
            derivedFrom.put(id, command.generation());
            rolledBack.add(command);
            return new ApplyAcceptedView(id, 1L);
        }

        private UUID admitRealRequest(long generation) {
            if (failure != null) {
                throw failure;
            }
            if (pauseInsideSubmit > 0) {
                try {
                    Thread.sleep(pauseInsideSubmit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            UUID applyId = engineTransaction.execute(status ->
                    ApplyRows.insertRequest(runtime, live.tenant(), live.instance(), live.placement(), "REQUESTED"));
            generations.put(applyId, generation);
            inserted.incrementAndGet();
            return applyId;
        }

        @Override public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command) {
            UUID id = admitRealRequest(command.generation());
            submitted.add(command);
            return new ApplyAcceptedView(id, 1L);
        }

        @Override public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command) {
            UUID id = admitRealRequest(mintedByRollback);
            rolledBack.add(command);
            return new ApplyAcceptedView(id, 1L);
        }

        @Override public List<ApplyObservationView> observeApply(UUID organizationId, UUID instanceId, ObserveQuery query) {
            if (observeFailure != null) {
                throw observeFailure;
            }
            return List.of(new ApplyObservationView(query.applyId(), "apply", live.placement(),
                    generations.get(query.applyId()), Instant.now(), "REQUESTED", false, 1L, List.of()));
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
}
