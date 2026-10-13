package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.RetirementOutcome;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.RetirementRefusedException.Reason;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.retirement.InstanceRetirementServiceImpl;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 012 T3 - the data plane's half of undeclare over a real PostgreSQL ({@code ops-501.md} section 4.4): the begin, the
 * resume, the complete and the roll-back; what is refused while something is in flight and with which reason; the race
 * against admission (each ordering held deterministically, then released by a barrier); and that every reader which serves
 * an instance stops seeing it at {@code retiring} and sees it again after a roll-back. As {@code sm_dp_runtime}, through the
 * real repositories and the one service.
 */
@Testcontainers(disabledWithoutDocker = true)
class InstanceRetirementServiceIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;
    private static JdbcInstanceStateRepository instances;
    private static JdbcPlacementRepository placements;
    private static InstanceRetirementServiceImpl service;
    private static AdmissionService admission;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
        instances = new JdbcInstanceStateRepository(runtime);
        placements = new JdbcPlacementRepository(runtime);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        service = new InstanceRetirementServiceImpl(tx, instances, placements,
                new JdbcRetirementRepository(runtime, new ApplyScopeRepository(runtime)), Clock.systemUTC());
        admission = ApplyEngineHarness.admission();
    }

    /** An active instance with one live placement and its apply_scope row. */
    private record Live(UUID tenant, UUID instance, UUID target, UUID placement) {
    }

    private static Live live() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        UUID placement = InventoryFixtures.placement(tenant, instance, target);
        ApplyRows.insertScope(runtime, tenant, instance);
        return new Live(tenant, instance, target, placement);
    }

    private static MapSqlParameterSource key(Live l) {
        return new MapSqlParameterSource("t", l.tenant()).addValue("i", l.instance());
    }

    private static RetirementBegun begin(Live l, UUID by) {
        return service.beginRetire(l.tenant(), l.instance(), by, UUID.randomUUID(), WorkloadDisposition.LEAVE_RUNNING);
    }

    private static InstanceLifecycle lifecycle(Live l) {
        return instances.find(l.tenant(), l.instance()).orElseThrow().lifecycle();
    }

    private static int count(String sql, Live l) {
        return superuser.queryForObject(sql, key(l), Integer.class);
    }

    private static int records(Live l) {
        return count("SELECT count(*)::int FROM sm_data.instance_retirement WHERE tenant_id = :t AND instance_id = :i", l);
    }

    private static void assertRefused(Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(RetirementRefusedException.class, call::run).reason());
    }

    // ---- begin ------------------------------------------------------------------------------------------------------

    @Test
    void beginMovesTheInstanceToRetiringAndWritesTheOpenRecordWithWhatItWasLastKnownTo() {
        Live l = live();
        UUID second = InventoryFixtures.placement(l.tenant(), l.instance(), l.target());
        runtime.update("INSERT INTO sm_data.placement_health (tenant_id, placement_id, instance_id, state, checked_at,"
                + " changed_at) VALUES (:t, :p, :i, 'GREEN', now(), now())", key(l).addValue("p", l.placement()));
        UUID admin = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        RetirementBegun begun = service.beginRetire(l.tenant(), l.instance(), admin, correlation,
                WorkloadDisposition.LEAVE_RUNNING);

        assertFalse(begun.resumed());
        RetirementView view = begun.retirement();
        assertEquals(InstanceLifecycle.RETIRING, lifecycle(l));
        assertEquals(admin, view.requestedBy());
        assertEquals(correlation, view.correlationId());
        assertEquals(l.instance(), view.instanceId());
        assertEquals(Optional.empty(), view.completedAt(), "open");
        assertEquals(Optional.empty(), view.outcome());
        assertEquals(WorkloadDisposition.LEAVE_RUNNING, view.workload());
        assertEquals(2, view.placements().size());
        assertEquals(Optional.of("GREEN"), view.placements().stream()
                .filter(p -> p.placementId().equals(l.placement())).findFirst().orElseThrow().lastHealth());
        assertEquals(Optional.empty(), view.placements().stream()
                .filter(p -> p.placementId().equals(second)).findFirst().orElseThrow().lastHealth(),
                "a placement the poller never probed reads null, not a guess");
        assertEquals(instances.find(l.tenant(), l.instance()).orElseThrow().stateVersion(), view.stateVersion(),
                "the instance's own stamp (OR-35), not the record's");
        assertEquals(1, records(l));
    }

    @Test
    void aRetryOnARetiringInstanceResumesTheOriginalRecordAndWritesNothing() {
        Live l = live();
        UUID original = UUID.randomUUID();
        RetirementView first = begin(l, original).retirement();
        long stampBefore = instances.find(l.tenant(), l.instance()).orElseThrow().stateVersion();

        RetirementBegun retry = begin(l, UUID.randomUUID());

        assertTrue(retry.resumed());
        assertEquals(first.retirementId(), retry.retirement().retirementId());
        assertEquals(original, retry.retirement().requestedBy(), "the original requester stays");
        assertEquals(first.correlationId(), retry.retirement().correlationId(), "and so does its correlation id");
        assertEquals(1, records(l));
        assertEquals(stampBefore, instances.find(l.tenant(), l.instance()).orElseThrow().stateVersion(),
                "a resume writes nothing, so nothing is stamped");
    }

    @Test
    void pendingAbandonedRetiredUnknownAndAnotherTenantsInstancesAreAllNotFound() {
        UUID tenant = SmDataPostgres.freshTenant();
        for (String lifecycle : List.of("pending", "abandoned")) {
            UUID instance = UUID.randomUUID();
            InventoryFixtures.declare(tenant, instance, "pending");
            if (lifecycle.equals("abandoned")) {
                runtime.update("UPDATE sm_data.instance_state SET lifecycle = 'abandoned' WHERE tenant_id = :t"
                        + " AND instance_id = :i", new MapSqlParameterSource("t", tenant).addValue("i", instance));
            }
            assertRefused(Reason.NOT_FOUND, () -> service.beginRetire(tenant, instance, UUID.randomUUID(),
                    UUID.randomUUID(), WorkloadDisposition.LEAVE_RUNNING));
            assertEquals(0, superuser.queryForObject("SELECT count(*)::int FROM sm_data.apply_scope WHERE tenant_id = :t"
                    + " AND instance_id = :i", new MapSqlParameterSource("t", tenant).addValue("i", instance), Integer.class),
                    lifecycle + ": a 404 creates no apply_scope row");
        }
        Live gone = live();
        begin(gone, UUID.randomUUID());
        service.completeRetire(gone.tenant(), gone.instance());
        assertRefused(Reason.NOT_FOUND, () -> begin(gone, UUID.randomUUID()));
        assertRefused(Reason.NOT_FOUND, () -> service.beginRetire(tenant, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), WorkloadDisposition.LEAVE_RUNNING));

        Live mine = live();
        UUID another = SmDataPostgres.freshTenant();                       // I5: the same instance id, another tenant
        assertRefused(Reason.NOT_FOUND, () -> service.beginRetire(another, mine.instance(), UUID.randomUUID(),
                UUID.randomUUID(), WorkloadDisposition.LEAVE_RUNNING));
        assertEquals(InstanceLifecycle.ACTIVE, lifecycle(mine), "untouched");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "RUNNING", "APPLY_INDETERMINATE"})
    void anOpenRequestRefusesTheBeginAndLeavesTheInstanceActive(String state) {
        Live l = live();
        ApplyRows.insertRequest(runtime, l.tenant(), l.instance(), l.placement(), state);

        assertRefused(Reason.REQUEST_IN_FLIGHT, () -> begin(l, UUID.randomUUID()));

        assertEquals(InstanceLifecycle.ACTIVE, lifecycle(l));
        assertEquals(0, records(l), "refused: no record");
    }

    @Test
    void aStopOnTheInstanceIsInFlightToo() {
        Live l = live();
        ApplyRows.insertRequest(runtime, l.tenant(), l.instance(), l.placement(), "stop", "RUNNING");
        assertRefused(Reason.REQUEST_IN_FLIGHT, () -> begin(l, UUID.randomUUID()));
    }

    @Test
    void aFinishedRequestDoesNotBlockTheBegin() {
        Live l = live();
        for (String state : List.of("APPLIED", "FAILED_NO_EFFECT", "CONFIG_DIVERGED")) {
            runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                            + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                            + " VALUES (gen_random_uuid(), :t, :i, :p, 'apply', 1, 'manual', 'operator-1', now(), :s, now())",
                    key(l).addValue("p", l.placement()).addValue("s", state));
        }
        assertFalse(begin(l, UUID.randomUUID()).resumed(), "history is not in-flight work");
    }

    @Test
    void aRunningFleetThatReservesAPlacementRefusesAndAnotherInstancesFleetDoesNot() {
        Live l = live();
        Live other = live();
        UUID fleet = insertFleet(l.tenant(), "RUNNING");
        insertMember(l.tenant(), fleet, 0, other.instance(), other.placement(), "pending", null);   // another instance: no reservation here
        assertFalse(begin(other, UUID.randomUUID()).resumed(), "the fleet's member is on ANOTHER instance of the tenant");
        UUID spare = insertFleet(l.tenant(), "RUNNING");
        insertMember(l.tenant(), spare, 0, l.instance(), l.placement(), "admitted", "succeeded");   // settled: it holds nothing
        assertFalse(begin(l, UUID.randomUUID()).resumed());
    }

    @Test
    void aPendingOrUnsettledAdmittedFleetMemberIsAReservation() {
        for (String disposition : List.of("pending", "admitted")) {
            Live l = live();
            UUID fleet = insertFleet(l.tenant(), "RUNNING");
            insertMember(l.tenant(), fleet, 0, l.instance(), l.placement(), disposition, null);

            assertRefused(Reason.FLEET_IN_FLIGHT, () -> begin(l, UUID.randomUUID()));
            assertEquals(InstanceLifecycle.ACTIVE, lifecycle(l), disposition);
            assertEquals(0, records(l), disposition);
        }
    }

    // ---- complete ---------------------------------------------------------------------------------------------------

    @Test
    void completeRetiresEveryLivePlacementClosesTheRecordAndRetiresTheInstanceInOneTransaction() {
        Live l = live();
        UUID second = InventoryFixtures.placement(l.tenant(), l.instance(), l.target());
        RetirementView opened = begin(l, UUID.randomUUID()).retirement();

        RetirementView done = service.completeRetire(l.tenant(), l.instance());

        assertEquals(InstanceLifecycle.RETIRED, lifecycle(l));
        assertEquals(opened.retirementId(), done.retirementId());
        assertEquals(Optional.of(RetirementOutcome.RETIRED), done.outcome());
        assertTrue(done.completedAt().isPresent());
        assertEquals(opened.requestedBy(), done.requestedBy());
        assertEquals(List.of(), placements.live(l.tenant(), l.instance()), "no live placement is left");
        assertEquals(2, count("SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i"
                + " AND retired_at IS NOT NULL", l), "the rows are kept, retired (BE-D32 rule 7)");
        assertEquals(instances.find(l.tenant(), l.instance()).orElseThrow().stateVersion(), done.stateVersion());
        assertEquals(1, records(l));
        assertNotEquals(null, second);
    }

    @Test
    void completeIsIdempotentAndAnswersTheClosedRecordWithoutWriting() {
        Live l = live();
        begin(l, UUID.randomUUID());
        RetirementView first = service.completeRetire(l.tenant(), l.instance());
        long stamp = instances.find(l.tenant(), l.instance()).orElseThrow().stateVersion();

        RetirementView again = service.completeRetire(l.tenant(), l.instance());

        assertEquals(first.retirementId(), again.retirementId());
        assertEquals(first.completedAt(), again.completedAt());
        assertEquals(stamp, instances.find(l.tenant(), l.instance()).orElseThrow().stateVersion(), "nothing written");
        assertEquals(1, records(l));
    }

    @Test
    void completeOnAnInstanceNoUndeclareIsInProgressOnIsRefused() {
        Live l = live();
        assertRefused(Reason.NOT_RETIRING, () -> service.completeRetire(l.tenant(), l.instance()));
        assertEquals(InstanceLifecycle.ACTIVE, lifecycle(l));
        assertRefused(Reason.NOT_FOUND, () -> service.completeRetire(l.tenant(), UUID.randomUUID()));
        assertRefused(Reason.NOT_FOUND, () -> service.completeRetire(SmDataPostgres.freshTenant(), l.instance()));

        begin(l, UUID.randomUUID());
        service.rollBackRetire(l.tenant(), l.instance());                  // rolled back: active again, nothing in progress
        assertRefused(Reason.NOT_RETIRING, () -> service.completeRetire(l.tenant(), l.instance()));
    }

    // ---- roll back --------------------------------------------------------------------------------------------------

    @Test
    void rollBackClosesTheRecordRolledBackAndTheInstanceIsActiveAndAdmissibleAgain() {
        Live l = live();
        RetirementView opened = begin(l, UUID.randomUUID()).retirement();
        assertInstanceOf(AdmissionOutcome.Refused.class, admission.admit(ApplyEngineHarness.apply(
                ApplyEngineHarness.scope(l.tenant(), l.instance(), new PlacementId(l.placement())), 1)),
                "retiring: admission is refused");

        assertTrue(service.rollBackRetire(l.tenant(), l.instance()));

        assertEquals(InstanceLifecycle.ACTIVE, lifecycle(l));
        assertEquals("rolled-back", superuser.queryForObject("SELECT outcome FROM sm_data.instance_retirement"
                + " WHERE tenant_id = :t AND retirement_id = :r", key(l).addValue("r", opened.retirementId()), String.class));
        assertEquals(1, records(l), "the attempt stays on record, closed rolled-back");

        // a later undeclare is a NEW attempt with its own record, and rolls back the same way
        RetirementBegun next = begin(l, UUID.randomUUID());
        assertFalse(next.resumed());
        assertNotEquals(opened.retirementId(), next.retirement().retirementId());
        assertTrue(service.rollBackRetire(l.tenant(), l.instance()));
        assertEquals(2, records(l), "one record per attempt, both kept");

        assertInstanceOf(AdmissionOutcome.Admitted.class, admission.admit(ApplyEngineHarness.apply(
                ApplyEngineHarness.scope(l.tenant(), l.instance(), new PlacementId(l.placement())), 1)),
                "after the roll-back the instance is admissible again");
        assertRefused(Reason.REQUEST_IN_FLIGHT, () -> begin(l, UUID.randomUUID()));      // ... and what it admitted now blocks
    }

    @Test
    void rollBackOnAnythingButARetiringInstanceIsFalseAndWritesNothing() {
        Live l = live();
        assertFalse(service.rollBackRetire(l.tenant(), l.instance()), "active");
        begin(l, UUID.randomUUID());
        service.completeRetire(l.tenant(), l.instance());
        assertFalse(service.rollBackRetire(l.tenant(), l.instance()), "retired");
        assertEquals(InstanceLifecycle.RETIRED, lifecycle(l));
        assertFalse(service.rollBackRetire(l.tenant(), UUID.randomUUID()), "unknown");
        assertFalse(service.rollBackRetire(SmDataPostgres.freshTenant(), l.instance()), "another tenant's");
    }

    @Test
    void retiringListsThisTenantsOpenRecordsOldestFirstAndNoOthers() {
        Live a = live();
        Live b = new Live(a.tenant(), UUID.randomUUID(), null, null);
        InventoryFixtures.declare(a.tenant(), b.instance());
        Live elsewhere = live();
        begin(a, UUID.randomUUID());
        sleep(10);                                                         // requested_at is the order: keep it strict
        begin(b, UUID.randomUUID());
        begin(elsewhere, UUID.randomUUID());

        var rows = service.retiring(a.tenant());

        assertEquals(List.of(a.instance(), b.instance()), rows.stream().map(r -> r.instanceId()).toList());
        service.completeRetire(a.tenant(), a.instance());
        assertEquals(List.of(b.instance()), service.retiring(a.tenant()).stream().map(r -> r.instanceId()).toList(),
                "a finished one is no longer retiring");
    }

    // ---- the race against admission --------------------------------------------------------------------------------

    /** Whoever holds the scope lock decides: an apply admitted while the begin queued is what the begin then sees. */
    @Test
    void aRequestAdmittedWhileTheBeginQueuedForTheScopeLockRefusesTheBegin() throws Exception {
        Live l = live();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = SmDataPostgres.runtimeDataSource().getConnection()) {
            holder.setAutoCommit(false);
            lockScope(holder, l);
            Future<Object> pending = pool.submit(() -> attempt(() -> begin(l, UUID.randomUUID())));
            awaitSomeoneWaitingOnALock();
            assertFalse(pending.isDone(), "the begin is queued behind the lock admission holds");
            insertRequested(holder, l);                                       // admission wins: its request commits first
            holder.commit();

            Object result = pending.get(15, TimeUnit.SECONDS);
            RetirementRefusedException refused = assertInstanceOf(RetirementRefusedException.class, result);
            assertEquals(Reason.REQUEST_IN_FLIGHT, refused.reason(), "checked UNDER the lock, so it saw the commit");
            assertEquals(InstanceLifecycle.ACTIVE, lifecycle(l));
            assertEquals(0, records(l));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAdmissionThatQueuedForTheScopeLockWhileTheBeginCommittedIsRefused() throws Exception {
        Live l = live();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = SmDataPostgres.runtimeDataSource().getConnection()) {
            holder.setAutoCommit(false);
            lockScope(holder, l);
            Future<AdmissionOutcome> pending = pool.submit(() -> admission.admit(ApplyEngineHarness.apply(
                    ApplyEngineHarness.scope(l.tenant(), l.instance(), new PlacementId(l.placement())), 1)));
            awaitSomeoneWaitingOnALock();
            assertFalse(pending.isDone(), "admission is queued behind the lock the begin holds");
            try (PreparedStatement ps = holder.prepareStatement("UPDATE sm_data.instance_state SET lifecycle = 'retiring'"
                    + " WHERE tenant_id = ? AND instance_id = ?")) {
                ps.setObject(1, l.tenant());
                ps.setObject(2, l.instance());
                assertEquals(1, ps.executeUpdate());                          // the begin wins: retiring commits first
            }
            holder.commit();

            AdmissionOutcome out = pending.get(15, TimeUnit.SECONDS);
            assertEquals(RefusalReason.INSTANCE_NOT_DECLARED, assertInstanceOf(AdmissionOutcome.Refused.class, out).reason(),
                    "admission re-reads the lifecycle UNDER the lock, so it saw retiring");
            assertEquals(0, count("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t AND instance_id = :i", l));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUndeclareAndAnAdmissionReleasedTogetherLeaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                Live l = live();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> undeclare = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return attempt(() -> begin(l, UUID.randomUUID()));
                });
                Future<Object> admit = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return admission.admit(ApplyEngineHarness.apply(
                            ApplyEngineHarness.scope(l.tenant(), l.instance(), new PlacementId(l.placement())), 1));
                });
                Object undeclared = undeclare.get(20, TimeUnit.SECONDS);
                Object admitted = admit.get(20, TimeUnit.SECONDS);

                boolean beginWon = undeclared instanceof RetirementBegun;
                boolean admissionWon = admitted instanceof AdmissionOutcome.Admitted;
                assertTrue(beginWon ^ admissionWon, "round " + round + ": exactly one wins: " + undeclared + " / " + admitted);
                if (beginWon) {
                    assertEquals(RefusalReason.INSTANCE_NOT_DECLARED,
                            assertInstanceOf(AdmissionOutcome.Refused.class, admitted).reason());
                    assertEquals(InstanceLifecycle.RETIRING, lifecycle(l));
                    assertEquals(0, count("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t"
                            + " AND instance_id = :i", l));
                } else {
                    assertEquals(Reason.REQUEST_IN_FLIGHT,
                            assertInstanceOf(RetirementRefusedException.class, undeclared).reason());
                    assertEquals(InstanceLifecycle.ACTIVE, lifecycle(l));
                    assertEquals(1, count("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t"
                            + " AND instance_id = :i", l));
                    assertEquals(0, records(l));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoBeginsReleasedTogetherWriteOneRecordAndOneOfThemResumes() throws Exception {
        Live l = live();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<RetirementBegun>> both = List.of(
                    pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return begin(l, UUID.randomUUID()); }),
                    pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return begin(l, UUID.randomUUID()); }));
            RetirementBegun one = both.get(0).get(20, TimeUnit.SECONDS);
            RetirementBegun two = both.get(1).get(20, TimeUnit.SECONDS);

            assertTrue(one.resumed() ^ two.resumed(), "one started it and one found it open");
            assertEquals(one.retirement().retirementId(), two.retirement().retirementId());
            assertEquals(1, records(l));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- every reader stops seeing a retiring instance, and sees it again after a roll-back ---------------------------

    @Test
    void theReadersThatServeInstancesSeeActiveOnly() {
        Live l = live();
        TenantEnumerationRepository tenants = new TenantEnumerationRepository(runtime);
        JdbcApplyAttentionRepository attention = new JdbcApplyAttentionRepository(runtime);
        runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (gen_random_uuid(), :t, :i, :p, 'apply', 1, 'manual', 'operator-1', now(),"
                        + " 'CONFIG_DIVERGED', now())", key(l).addValue("p", l.placement()));

        assertTrue(listed(l), "active: the health poller's, attention's and the summary's list (instances.list(ACTIVE))");
        assertTrue(tenants.tenantsWithActiveInstances().contains(new TenantId(l.tenant())),
                "active: OR-49's list that drives the health poller and the metrics loop");
        assertEquals(1, attention.needingAttention(l.tenant()).size(), "active: attention lists the diverged placement");

        begin(l, UUID.randomUUID());
        assertFalse(listed(l), "retiring: gone from instances.list(ACTIVE)");
        assertFalse(tenants.tenantsWithActiveInstances().contains(new TenantId(l.tenant())), "retiring: OR-49's list");
        assertEquals(0, attention.needingAttention(l.tenant()).size(), "retiring: attention");

        service.rollBackRetire(l.tenant(), l.instance());
        assertTrue(listed(l), "rolled back: back in the list");
        assertTrue(tenants.tenantsWithActiveInstances().contains(new TenantId(l.tenant())));
        assertEquals(1, attention.needingAttention(l.tenant()).size());

        begin(l, UUID.randomUUID());
        service.completeRetire(l.tenant(), l.instance());
        assertFalse(listed(l), "retired");
        assertFalse(tenants.tenantsWithActiveInstances().contains(new TenantId(l.tenant())));
        assertEquals(0, attention.needingAttention(l.tenant()).size());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean listed(Live l) {
        return instances.list(l.tenant(), InstanceLifecycle.ACTIVE, Optional.empty()).stream()
                .map(InstanceRecord::instanceId).anyMatch(l.instance()::equals);
    }

    /** The call's result, or the exception it threw as a VALUE, so a race test can classify both outcomes. */
    private static Object attempt(java.util.concurrent.Callable<Object> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return e;
        }
    }

    private static void lockScope(Connection c, Live l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sm_data.apply_scope WHERE tenant_id = ?"
                + " AND instance_id = ? FOR UPDATE")) {
            ps.setObject(1, l.tenant());
            ps.setObject(2, l.instance());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    private static void insertRequested(Connection c, Live l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO sm_data.apply_request (apply_id, tenant_id,"
                + " instance_id, placement_id, operation, generation, strategy, requested_by, requested_at, state)"
                + " VALUES (gen_random_uuid(), ?, ?, ?, 'apply', 1, 'manual', 'operator-1', now(), 'REQUESTED')")) {
            ps.setObject(1, l.tenant());
            ps.setObject(2, l.instance());
            ps.setObject(3, l.placement());
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static UUID insertFleet(UUID tenant, String state) {
        UUID fleet = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy, batch_size,"
                        + " on_failure, state, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, :f, gen_random_uuid(), 'manual', 1, 'continue', :s, 'operator-1', :now, gen_random_uuid())",
                new MapSqlParameterSource("t", tenant).addValue("f", fleet).addValue("s", state)
                        .addValue("now", OffsetDateTime.now()));
        return fleet;
    }

    private static void insertMember(UUID tenant, UUID fleet, int ordinal, UUID instance, UUID placement,
            String disposition, String settled) {
        MapSqlParameterSource key = new MapSqlParameterSource("t", tenant).addValue("f", fleet).addValue("o", ordinal);
        // a member is BORN pending (V014's guard) and gains its disposition and its settlement by UPDATE, in that order
        runtime.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, apply_id, disposition)"
                        + " VALUES (:t, :f, :o, 0, :i, :p, 1, gen_random_uuid(), 'pending')",
                new MapSqlParameterSource(key.getValues()).addValue("i", instance).addValue("p", placement));
        if (!disposition.equals("pending")) {
            runtime.update("UPDATE sm_data.fleet_member SET disposition = :d WHERE tenant_id = :t AND fleet_id = :f"
                    + " AND ordinal = :o", new MapSqlParameterSource(key.getValues()).addValue("d", disposition));
        }
        if (settled != null) {
            runtime.update("UPDATE sm_data.fleet_member SET settled = :s WHERE tenant_id = :t AND fleet_id = :f"
                    + " AND ordinal = :o", new MapSqlParameterSource(key.getValues()).addValue("s", settled));
        }
    }

    private static void awaitSomeoneWaitingOnALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = superuser.queryForObject("SELECT count(*)::int FROM pg_stat_activity"
                    + " WHERE usename = 'sm_dp_runtime' AND wait_event_type = 'Lock'", new MapSqlParameterSource(),
                    Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nothing queued behind the scope lock");
    }
}
