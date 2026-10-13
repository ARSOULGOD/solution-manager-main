package com.solace.sm.dp.retirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.RetirementOutcome;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.RetirementRefusedException.Reason;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 012 T3: the orchestration of undeclare's data-plane steps, against fakes that record every call in order. What the
 * database does with the writes (the trigger refusals, the race, the readers) is the ITs'; what is pinned here is the ORDER
 * (the scope lock first, the checks under it, the record closed before the lifecycle moves) and which refusal is which.
 */
class InstanceRetirementServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final List<String> calls = new ArrayList<>();
    private Fakes fakes;
    private InstanceRetirementServiceImpl service;

    @BeforeEach
    void rig() {
        calls.clear();
        fakes = new Fakes();
        service = new InstanceRetirementServiceImpl(new TransactionTemplate(new NoTransactions()), fakes.instances,
                fakes.placements, fakes.rows, Clock.fixed(NOW, ZoneOffset.UTC), () -> fakes.nextRetirementId);
    }

    // ---- beginRetire ------------------------------------------------------------------------------------------------

    @Test
    void beginTakesTheScopeLockFirstThenChecksWhatIsInFlightThenMovesTheLifecycleThenWritesTheRecord() {
        fakes.lifecycle = InstanceLifecycle.ACTIVE;

        RetirementBegun begun = service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION, WorkloadDisposition.LEAVE_RUNNING);

        assertFalse(begun.resumed());
        assertEquals(List.of("lockScope", "requestInFlight", "fleetReserves", "scheduleReserves", "markRetiring", "insertOpen", "open"),
                calls.stream().filter(c -> !c.startsWith("find")).toList(),
                "the lock admission takes is the first thing taken, and nothing is written before the checks pass");
        assertEquals(ADMIN, begun.retirement().requestedBy());
        assertEquals(CORRELATION, begun.retirement().correlationId());
        assertEquals(NOW, begun.retirement().requestedAt(), "the injected clock stamps the request");
        assertEquals(7L, begun.retirement().stateVersion(), "the instance's own stamp, not the record's");
    }

    @Test
    void anOpenRequestRefusesWithItsOwnReasonAndNothingIsWritten() {
        fakes.lifecycle = InstanceLifecycle.ACTIVE;
        fakes.requestInFlight = true;

        assertRefused(Reason.REQUEST_IN_FLIGHT, () -> service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION,
                WorkloadDisposition.LEAVE_RUNNING));
        assertNothingWritten();
    }

    @Test
    void aRunningFleetReservationRefusesWithItsOwnReasonAndNothingIsWritten() {
        fakes.lifecycle = InstanceLifecycle.ACTIVE;
        fakes.fleetReserves = true;

        assertRefused(Reason.FLEET_IN_FLIGHT, () -> service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION,
                WorkloadDisposition.LEAVE_RUNNING));
        assertNothingWritten();
    }

    /** OR-131 (W5 review W5B-5). */
    @Test
    void aPendingScheduleRefusesWithItsOwnReasonAfterTheLockAndNothingIsWritten() {
        fakes.lifecycle = InstanceLifecycle.ACTIVE;
        fakes.scheduleReserves = true;

        assertRefused(Reason.SCHEDULE_PENDING, () -> service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION,
                WorkloadDisposition.LEAVE_RUNNING));
        assertEquals("lockScope", calls.stream().filter(c -> !c.startsWith("find")).findFirst().orElseThrow(),
                "read under the apply_scope lock, which schedule() also takes before inserting");
        assertNothingWritten();
    }

    @Test
    void pendingAbandonedRetiredAndUnknownInstancesAreNotFoundAndTakeNoLock() {
        for (InstanceLifecycle lifecycle : List.of(InstanceLifecycle.PENDING, InstanceLifecycle.ABANDONED,
                InstanceLifecycle.RETIRED)) {
            calls.clear();
            fakes.lifecycle = lifecycle;
            assertRefused(Reason.NOT_FOUND, () -> service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION,
                    WorkloadDisposition.LEAVE_RUNNING));
            assertFalse(calls.contains("lockScope"), lifecycle + ": no lock and so no apply_scope row for a 404");
        }
        calls.clear();
        fakes.lifecycle = null;
        assertRefused(Reason.NOT_FOUND, () -> service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION,
                WorkloadDisposition.LEAVE_RUNNING));
        assertFalse(calls.contains("lockScope"));
    }

    @Test
    void anInstanceThatLeavesActiveWhileTheCallerWaitedForTheLockIsReadAgainUnderIt() {
        fakes.lifecycle = InstanceLifecycle.ACTIVE;
        fakes.onLock = () -> fakes.lifecycle = InstanceLifecycle.RETIRED;      // another undeclare won while this queued

        assertRefused(Reason.NOT_FOUND, () -> service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION,
                WorkloadDisposition.LEAVE_RUNNING));
        assertFalse(calls.contains("markRetiring"), "the read before the lock is a courtesy; the one under it decides");
    }

    @Test
    void aRetryOnARetiringInstanceResumesItsOwnOpenRecordAndWritesNothing() {
        fakes.lifecycle = InstanceLifecycle.RETIRING;
        UUID original = UUID.randomUUID();
        fakes.open = Optional.of(view(original, Optional.empty(), Optional.empty()));

        RetirementBegun begun = service.beginRetire(TENANT, INSTANCE, ADMIN, CORRELATION, WorkloadDisposition.LEAVE_RUNNING);

        assertTrue(begun.resumed());
        assertEquals(original, begun.retirement().requestedBy(), "requestedBy stays the original requester");
        assertEquals(List.of("lockScope", "open"), calls.stream().filter(c -> !c.startsWith("find")).toList(),
                "no in-flight check (admission is already refused), no write");
    }

    // ---- completeRetire ---------------------------------------------------------------------------------------------

    @Test
    void completeRetiresThePlacementsAndClosesTheRecordBeforeItMovesTheLifecycle() {
        fakes.lifecycle = InstanceLifecycle.RETIRING;
        fakes.open = Optional.of(view(ADMIN, Optional.empty(), Optional.empty()));
        fakes.closed = Optional.of(view(ADMIN, Optional.of(NOW), Optional.of(RetirementOutcome.RETIRED)));

        RetirementView done = service.completeRetire(TENANT, INSTANCE);

        assertEquals(List.of("lockScope", "open", "retireAll", "close:RETIRED", "markRetired", "latestClosed:RETIRED"),
                calls.stream().filter(c -> !c.startsWith("find")).toList(),
                "the database refuses 'retired' while a placement is live and the record is the audit: both come first");
        assertEquals(Optional.of(RetirementOutcome.RETIRED), done.outcome());
        assertEquals(7L, done.stateVersion());
    }

    @Test
    void completeOnAnAlreadyRetiredInstanceAnswersTheClosedRecordAndWritesNothing() {
        fakes.lifecycle = InstanceLifecycle.RETIRED;
        fakes.closed = Optional.of(view(ADMIN, Optional.of(NOW), Optional.of(RetirementOutcome.RETIRED)));

        RetirementView done = service.completeRetire(TENANT, INSTANCE);

        assertEquals(Optional.of(RetirementOutcome.RETIRED), done.outcome());
        assertEquals(List.of("lockScope", "latestClosed:RETIRED"), calls.stream().filter(c -> !c.startsWith("find")).toList());
    }

    @Test
    void completeOnAnActiveInstanceIsNotRetiringAndOnAnUnknownOneNotFound() {
        fakes.lifecycle = InstanceLifecycle.ACTIVE;
        assertRefused(Reason.NOT_RETIRING, () -> service.completeRetire(TENANT, INSTANCE));
        assertNothingWritten();

        fakes.lifecycle = InstanceLifecycle.PENDING;
        assertRefused(Reason.NOT_FOUND, () -> service.completeRetire(TENANT, INSTANCE));
        fakes.lifecycle = null;
        assertRefused(Reason.NOT_FOUND, () -> service.completeRetire(TENANT, INSTANCE));
    }

    // ---- rollBackRetire ---------------------------------------------------------------------------------------------

    @Test
    void rollBackClosesTheRecordRolledBackBeforeTheInstanceReturnsToActive() {
        fakes.lifecycle = InstanceLifecycle.RETIRING;
        fakes.open = Optional.of(view(ADMIN, Optional.empty(), Optional.empty()));

        assertTrue(service.rollBackRetire(TENANT, INSTANCE));

        assertEquals(List.of("lockScope", "open", "close:ROLLED_BACK", "markActive"),
                calls.stream().filter(c -> !c.startsWith("find")).toList(),
                "the trigger accepts retiring -> active only after the record was closed rolled-back in the same transaction");
    }

    @Test
    void rollBackOnAnythingButARetiringInstanceIsFalseAndTakesNoLock() {
        for (InstanceLifecycle lifecycle : List.of(InstanceLifecycle.ACTIVE, InstanceLifecycle.RETIRED,
                InstanceLifecycle.PENDING, InstanceLifecycle.ABANDONED)) {
            calls.clear();
            fakes.lifecycle = lifecycle;
            assertFalse(service.rollBackRetire(TENANT, INSTANCE), lifecycle.name());
            assertNothingWritten();
            assertFalse(calls.contains("lockScope"));
        }
        fakes.lifecycle = null;
        assertFalse(service.rollBackRetire(TENANT, INSTANCE));
    }

    @Test
    void aRollBackThatLostTheRaceToACompletionIsFalseAndWritesNothing() {
        fakes.lifecycle = InstanceLifecycle.RETIRING;
        fakes.onLock = () -> fakes.lifecycle = InstanceLifecycle.RETIRED;

        assertFalse(service.rollBackRetire(TENANT, INSTANCE));
        assertFalse(calls.contains("markActive"));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("close")));
    }

    // ---- retiring ---------------------------------------------------------------------------------------------------

    @Test
    void retiringIsTheRowsAnswerForThatTenantAlone() {
        RetiringInstance one = new RetiringInstance(INSTANCE, UUID.randomUUID(), NOW);
        fakes.retiring = List.of(one);

        assertEquals(List.of(one), service.retiring(TENANT));
        assertSame(TENANT, fakes.lastTenant);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void assertRefused(Reason reason, Runnable call) {
        RetirementRefusedException e = assertThrows(RetirementRefusedException.class, call::run);
        assertEquals(reason, e.reason());
        assertEquals(reason.name(), e.getMessage(), "the message is the reason's name and carries no id (I6)");
    }

    private void assertNothingWritten() {
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("mark") || c.startsWith("insert") || c.startsWith("close")
                || c.startsWith("retireAll")), "nothing written: " + calls);
    }

    private static RetirementView view(UUID requestedBy, Optional<Instant> completedAt, Optional<RetirementOutcome> outcome) {
        return new RetirementView(UUID.randomUUID(), INSTANCE, requestedBy, NOW, CORRELATION,
                WorkloadDisposition.LEAVE_RUNNING, List.of(), completedAt, outcome, 3L);
    }

    /** The fakes share the one ordered call log of the test. */
    private final class Fakes {
        InstanceLifecycle lifecycle;
        boolean requestInFlight;
        boolean fleetReserves;
        boolean scheduleReserves;
        Optional<RetirementView> open = Optional.empty();
        Optional<RetirementView> closed = Optional.empty();
        List<RetiringInstance> retiring = List.of();
        Runnable onLock = () -> { };
        UUID nextRetirementId = UUID.randomUUID();
        UUID lastTenant;

        final InstanceRows instances = new InstanceRows() {
            @Override public void insert(InstanceRecord row) {
                throw new AssertionError();
            }

            @Override public Optional<InstanceRecord> find(UUID tenantId, UUID instanceId) {
                calls.add("find");
                return lifecycle == null ? Optional.empty() : Optional.of(new InstanceRecord(tenantId, instanceId,
                        UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4", "orders", lifecycle,
                        AcquisitionMode.VIA_SSH, UUID.randomUUID(), NOW, 7L));
            }

            @Override public List<InstanceRecord> list(UUID t, InstanceLifecycle l, Optional<UUID> c) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingCreatedBefore(UUID t, Instant cutoff) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingIds(UUID t, int limit) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingCreatedBefore(UUID t, Instant cutoff, int limit) {
                throw new AssertionError();
            }

            @Override public int activate(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public int abandon(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public Optional<InstanceRecord> lockForPlacementChange(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public int touch(UUID t, UUID i) {
                throw new AssertionError();
            }
        };

        final PlacementRows placements = new PlacementRows() {
            @Override public void insert(PlacementRecord row) {
                throw new AssertionError();
            }

            @Override public Optional<PlacementRecord> findLive(UUID t, UUID i, UUID p) {
                throw new AssertionError();
            }

            @Override public Optional<PlacementRecord> find(UUID t, UUID i, UUID p) {
                throw new AssertionError();
            }

            @Override public List<PlacementRecord> live(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public int retire(UUID t, UUID i, UUID p, Instant at, UUID by) {
                throw new AssertionError();
            }

            @Override public boolean lockApplyScope(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public boolean inFlightOn(UUID t, UUID i, UUID p) {
                throw new AssertionError();
            }

            @Override public boolean instanceWideInFlight(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public int retireAll(UUID t, UUID i, Instant at) {
                calls.add("retireAll");
                assertEquals(NOW, at, "the injected clock stamps the placements' retirement");
                return 1;
            }
        };

        final RetirementRows rows = new RetirementRows() {
            @Override public void lockScope(UUID t, UUID i) {
                calls.add("lockScope");
                onLock.run();
            }

            @Override public boolean requestInFlight(UUID t, UUID i) {
                calls.add("requestInFlight");
                return requestInFlight;
            }

            @Override public boolean scheduleReserves(UUID t, UUID i) {
                calls.add("scheduleReserves");
                return scheduleReserves;
            }

            @Override public boolean fleetReserves(UUID t, UUID i) {
                calls.add("fleetReserves");
                return fleetReserves;
            }

            @Override public int markRetiring(UUID t, UUID i) {
                calls.add("markRetiring");
                return 1;
            }

            @Override public int markRetired(UUID t, UUID i) {
                calls.add("markRetired");
                return 1;
            }

            @Override public int markActive(UUID t, UUID i) {
                calls.add("markActive");
                return 1;
            }

            @Override public void insertOpen(UUID t, UUID id, UUID i, UUID by, Instant at, UUID correlation,
                    WorkloadDisposition w) {
                calls.add("insertOpen");
                open = Optional.of(new RetirementView(id, i, by, at, correlation, w, List.of(), Optional.empty(),
                        Optional.empty(), 99L));
            }

            @Override public Optional<RetirementView> open(UUID t, UUID i) {
                calls.add("open");
                return open;
            }

            @Override public Optional<RetirementView> latestClosed(UUID t, UUID i, RetirementOutcome o) {
                calls.add("latestClosed:" + o);
                return closed;
            }

            @Override public int close(UUID t, UUID id, RetirementOutcome o) {
                calls.add("close:" + o);
                return 1;
            }

            @Override public List<RetiringInstance> retiring(UUID t) {
                lastTenant = t;
                return retiring;
            }
        };
    }

    /** No database: a transaction that is only a boundary, so the ORDER of calls inside it is what is observed. */
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
