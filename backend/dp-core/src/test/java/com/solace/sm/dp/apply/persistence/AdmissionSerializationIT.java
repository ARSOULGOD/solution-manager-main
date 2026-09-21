package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation (d), LIVE — "proven with two concurrent sessions (the second blocks … then refuses
 * cleanly)".
 *
 * <p>The blocking half is the part prose cannot carry. Every other admission test would pass against
 * an implementation that read the scope row without {@code FOR UPDATE}: the checks are all correct
 * when nothing runs concurrently. So this class holds the lock in one thread, watches the other stall
 * in {@code pg_stat_activity} from a THIRD (superuser) connection, and records how long it stalled.
 *
 * <p>Reported measurements are printed with a {@code MEASURED:} prefix rather than asserted to the
 * millisecond — the assertion is the ORDERING (blocked while held, refused after release), which is
 * deterministic; the duration is evidence, which is not.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdmissionSerializationIT {

    /** How long the holder keeps the scope row. Long enough for the poller to catch the wait. */
    private static final long HOLD_MILLIS = 3000;

    private static AdmissionService admission;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
    }

    /**
     * The harness precondition, measured rather than assumed: the runtime data source must hand out
     * two SIMULTANEOUS connections, or every test below deadlocks instead of proving anything.
     *
     * <p>{@code SmDataPostgres.runtimeDataSource()} is a {@code SimpleDriverDataSource} — not a pool,
     * which turns out to be the stronger property here: it opens a fresh physical connection per
     * {@code getConnection()} with no maximum, so no Hikari substitute is needed in the harness.
     */
    @Test
    void theRuntimeDataSourceHandsOutTwoSimultaneousConnections() throws SQLException {
        try (Connection a = SmDataPostgres.runtimeDataSource().getConnection();
                Connection b = SmDataPostgres.runtimeDataSource().getConnection()) {
            assertNotEquals(a, b, "two distinct physical connections");
            assertEquals(1, a.createStatement().executeQuery("SELECT 1").next() ? 1 : 0);
            assertEquals(1, b.createStatement().executeQuery("SELECT 1").next() ? 1 : 0);
            System.out.println("MEASURED: runtime DataSource is "
                    + SmDataPostgres.runtimeDataSource().getClass().getSimpleName()
                    + "; two concurrent connections opened, backends "
                    + backendPid(a) + " and " + backendPid(b));
        }
    }

    @Test
    void aPlacementScopedAdmissionBlocksOnTheScopeRowHeldByAnInstanceWideOneThenRefusesCleanly()
            throws Exception {
        Fixture f = new Fixture();
        f.run(f.wide, f.placement, "placement-scoped apply behind an instance-wide holder");
    }

    @Test
    void anInstanceWideAdmissionBlocksOnTheScopeRowHeldByAPlacementScopedOneThenRefusesCleanly()
            throws Exception {
        Fixture f = new Fixture();
        f.run(f.placement, f.wide, "instance-wide apply behind a placement-scoped holder");
    }

    /**
     * The same-scope PURE race: no artificial hold, two {@code admit} calls released together.
     *
     * <p>What it measures is WHICH refusal the design's two mechanisms produce when they are both
     * live. Under the scope-row lock the loser cannot reach the insert until the winner has
     * committed, at which point it SEES the winner's {@code REQUESTED} row and refuses
     * {@code SCOPE_BUSY} — the braces. {@code FENCE_RACE_LOST}, the belt, is therefore unreachable
     * through this service, and that is the point: the belt exists for writers that bypass admission,
     * which {@code AdmissionFencesIT}'s raw-insert cases exercise directly.
     */
    @Test
    void twoConcurrentAdmissionsForOneScopeProduceExactlyOneAdmittedAndOneRefused() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ScopeKey scope = ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AdmissionOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final long generation = 10L + i;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return admission.admit(ApplyEngineHarness.apply(scope, generation));
                }));
            }
            AdmissionOutcome first = futures.get(0).get(60, TimeUnit.SECONDS);
            AdmissionOutcome second = futures.get(1).get(60, TimeUnit.SECONDS);

            List<AdmissionOutcome> outcomes = List.of(first, second);
            long admitted = outcomes.stream()
                    .filter(o -> o instanceof AdmissionOutcome.Admitted).count();
            assertEquals(1, admitted, "exactly one may win: " + outcomes);
            AdmissionOutcome.Refused loser = outcomes.stream()
                    .filter(o -> o instanceof AdmissionOutcome.Refused)
                    .map(AdmissionOutcome.Refused.class::cast).findFirst().orElseThrow();
            System.out.println("MEASURED: same-scope pure race loser refused with "
                    + loser.reason() + " — " + loser.detail());
            // The class's headline claim, asserted rather than merely printed: under the row lock the
            // loser SEES the winner's committed row and refuses SCOPE_BUSY (the braces) before the
            // belt can fire — FENCE_RACE_LOST is unreachable through this service (see the class and
            // RefusalReason javadocs). A regression that let the race reach the unique-index insert
            // instead would still leave "exactly one admitted" true, so that count alone cannot catch
            // it; only naming the reason can.
            assertEquals(com.solace.sm.dp.apply.engine.RefusalReason.SCOPE_BUSY, loser.reason(),
                    "the pure race must lose on the scope-row lock (braces), never on a fence index "
                            + "(belt) — detail was: " + loser.detail());
            assertEquals(1, ApplyEngineHarness.requests()
                    .nonTerminal(ApplyEngineHarness.tenant(tenant)).size(),
                    "exactly one row landed");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Admission ‖ landing, run live — the placement's FIRST landing, where the watermark row does not
     * yet exist.
     *
     * <p>This case has always passed, and the external review explained why that was luck rather than
     * design: a first placement landing INSERTs a {@code placement_watermark} row whose FOREIGN KEY
     * takes {@code FOR KEY SHARE} on the {@code apply_scope} row, and {@code admit}'s
     * {@code SELECT … FOR UPDATE} conflicts with that. The lock was a side effect of a foreign-key
     * check. Since finding 3, {@code OutcomeService.land} takes the scope lock EXPLICITLY and first, so
     * the manual landing below mirrors that — lock, transition, raise — and the test is honest about
     * which mechanism it exercises.
     */
    @Test
    void anAdmissionDuringAnUncommittedFirstLandingBlocksOnTheScopeRowThenIsFencedByTheWatermark()
            throws Exception {
        Landing landing = new Landing();
        landing.run(10, "the placement's FIRST landing (INSERT branch of the watermark upsert)");
    }

    /**
     * The same race on a placement that ALREADY HAS a watermark row — the external review's finding 3,
     * proved red then green.
     *
     * <p>Measured with {@code pgrowlocks} before the fix: the upsert's {@code DO UPDATE} branch takes no
     * lock at all on the parent {@code apply_scope} row, because no foreign-key check runs when the
     * child row is updated rather than inserted. So the serialization the design claimed held for a
     * placement's first landing and silently stopped from the second onwards — every later landing on
     * that placement raced admission with only the {@code blocksScope()} backstop between them. Run
     * with the {@code lockScope} call removed from the manual landing below, this test measured
     * {@code no sm_dp_runtime backend was ever seen waiting on a Lock} and the contender returning
     * {@code SCOPE_BUSY} immediately. With the explicit lock it blocks on {@code FOR UPDATE} exactly as
     * the first landing does.
     */
    @Test
    void anAdmissionDuringAnUncommittedLaterLandingBlocksToo() throws Exception {
        Landing landing = new Landing();
        // Land STAGED at 5 first, so the placement_watermark row EXISTS and the landing under test
        // takes the DO UPDATE branch. Through the service, so the row is committed and real.
        com.solace.sm.dp.apply.model.ApplyId first = landing.admitAndStart(5);
        ApplyEngineHarness.outcomes().land(landing.scope.tenant(), first,
                com.solace.sm.dp.apply.model.RequestState.STAGED);
        assertEquals(5, ApplyEngineHarness.scopes().placementWatermark(landing.scope.tenant(),
                        landing.scope.instance(), landing.scope.placement()),
                "the watermark row must exist, or this is the INSERT case again");

        landing.run(6, "a LATER landing (DO UPDATE branch — finding 3's case)");
    }

    /**
     * {@code OutcomeService.land} takes the scope lock ITSELF — the mutation prover for finding 3.
     *
     * <p>The two tests above hold a landing open by hand, because a transaction cannot be paused from
     * outside and {@code land()} runs {@code REQUIRES_NEW} and commits on its own. That makes them
     * proofs about the MECHANISM (a landing that locks the scope row serializes with admission) and
     * not about {@code land()} keeping its call to it — delete {@code scopes.lockScope} from
     * {@code OutcomeService.land} and both stay green.
     *
     * <p>So this one turns the telescope round: a third party holds the scope row, and the REAL
     * {@code land()} is measured blocking on it. Deliberately on a placement that already has a
     * watermark row, so the {@code DO UPDATE} branch runs and the explicit lock is the ONLY thing
     * that can be blocking.
     */
    @Test
    void landItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch() throws Exception {
        Landing l = new Landing();
        com.solace.sm.dp.apply.model.ApplyId first = l.admitAndStart(5);
        ApplyEngineHarness.outcomes().land(l.scope.tenant(), first,
                com.solace.sm.dp.apply.model.RequestState.STAGED);
        com.solace.sm.dp.apply.model.ApplyId second = l.admitAndStart(6);

        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Long> blockedMillis = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> ApplyEngineHarness.transactions()
                    .execute(status -> {
                        ApplyEngineHarness.scopes().lockScope(l.scope.tenant(), l.scope.instance());
                        held.countDown();
                        sleep(HOLD_MILLIS);
                        return null;
                    }));
            assertTrue(held.await(30, TimeUnit.SECONDS), "the holder never took the scope row");

            Future<?> landing = pool.submit(() -> {
                long t0 = System.nanoTime();
                ApplyEngineHarness.outcomes().land(l.scope.tenant(), second,
                        com.solace.sm.dp.apply.model.RequestState.STAGED);
                blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                return null;
            });
            String wait = pollForLockWait();
            holder.get(60, TimeUnit.SECONDS);
            landing.get(60, TimeUnit.SECONDS);

            System.out.println("MEASURED: OutcomeService.land (DO UPDATE branch) blocked "
                    + blockedMillis.get() + " ms behind a held scope row (holder held "
                    + HOLD_MILLIS + " ms); pg_stat_activity showed " + wait);
            assertEquals("Lock", waitEventType(wait),
                    "land() must be parked on a LOCK while a third party holds the scope row. "
                            + "Was: " + wait);
            assertTrue(wait.contains("FOR UPDATE"),
                    "…and on lockScope's SELECT … FOR UPDATE specifically: on the DO UPDATE branch "
                            + "nothing else in land() touches apply_scope at all. Was: " + wait);
            assertTrue(blockedMillis.get() >= HOLD_MILLIS - 250,
                    "land() returned in " + blockedMillis.get() + " ms, before the " + HOLD_MILLIS
                            + " ms hold ended — it did not take the scope lock");
            assertEquals(com.solace.sm.dp.apply.model.RequestState.STAGED,
                    ApplyEngineHarness.requests().find(l.scope.tenant(), second).orElseThrow()
                            .state(),
                    "…and it landed once the lock was free");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code OutcomeService.start} takes the scope lock ITSELF — fix round 1's Important finding 2,
     * {@code start}'s own pin.
     *
     * <p>Measured (fix round 1 review): before this test, deleting {@code scopes.lockScope} from
     * {@code start} left the whole suite (41/41) green — nothing anywhere exercised {@code start}
     * concurrently with a held scope row. The Global Constraint is that admission AND ALL THREE
     * landing walks take the scope row first; {@code start} is one of the three, and only {@code land}
     * had a test of this shape until now. Same choreography as
     * {@link #landItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch}, aimed at {@code start} instead:
     * a third party holds the scope row, and the REAL {@code start()} is measured blocking on it.
     */
    @Test
    void startItselfBlocksOnAHeldScopeRow() throws Exception {
        Landing l = new Landing();
        com.solace.sm.dp.apply.model.ApplyId requested = assertInstanceOf(
                AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(l.scope, 5))).applyId();

        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Long> blockedMillis = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> ApplyEngineHarness.transactions()
                    .execute(status -> {
                        ApplyEngineHarness.scopes().lockScope(l.scope.tenant(), l.scope.instance());
                        held.countDown();
                        sleep(HOLD_MILLIS);
                        return null;
                    }));
            assertTrue(held.await(30, TimeUnit.SECONDS), "the holder never took the scope row");

            Future<?> starting = pool.submit(() -> {
                long t0 = System.nanoTime();
                ApplyEngineHarness.outcomes().start(l.scope.tenant(), requested);
                blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                return null;
            });
            String wait = pollForLockWait();
            holder.get(60, TimeUnit.SECONDS);
            starting.get(60, TimeUnit.SECONDS);

            System.out.println("MEASURED: OutcomeService.start blocked " + blockedMillis.get()
                    + " ms behind a held scope row (holder held " + HOLD_MILLIS
                    + " ms); pg_stat_activity showed " + wait);
            assertEquals("Lock", waitEventType(wait),
                    "start() must be parked on a LOCK while a third party holds the scope row. "
                            + "Was: " + wait);
            assertTrue(wait.contains("FOR UPDATE"),
                    "…and on lockScope's SELECT … FOR UPDATE specifically. Was: " + wait);
            assertTrue(blockedMillis.get() >= HOLD_MILLIS - 250,
                    "start() returned in " + blockedMillis.get() + " ms, before the " + HOLD_MILLIS
                            + " ms hold ended — it did not take the scope lock");
            assertEquals(com.solace.sm.dp.apply.model.RequestState.RUNNING,
                    ApplyEngineHarness.requests().find(l.scope.tenant(), requested).orElseThrow()
                            .state(),
                    "…and it started once the lock was free");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code OutcomeService.resolveIndeterminate} takes the scope lock ITSELF — fix round 1's
     * Important finding 2, {@code resolveIndeterminate}'s own pin.
     *
     * <p>Measured (fix round 1 review): before this test, deleting {@code scopes.lockScope} from
     * {@code resolveIndeterminate} also left the whole suite (41/41) green, for the same reason as
     * {@code start} above — the third of the design's three landing walks had no test of this shape
     * either.
     *
     * <p><b>Deliberately on the {@code DO UPDATE} branch, not the placement's first landing.</b> A
     * first-landing fixture would have let this test pass for the WRONG reason — measured while
     * writing it: with {@code lockScope} removed, a first-ever placement watermark raise still
     * blocks the holder, but only because the watermark upsert's INSERT branch takes an incidental
     * {@code FOR KEY SHARE} via the FK (finding 3's own point, restated). That is the exact accident
     * finding 3 said must not be mistaken for the explicit lock. So this fixture lands a first request
     * {@code APPLIED} at generation 5 to create the {@code placement_watermark} row for real, THEN
     * resolves a second, indeterminate request as {@code applied} at generation 6 — which raises the
     * SAME row through the upsert's {@code DO UPDATE} branch, where no FK check runs at all. On that
     * branch the explicit {@code lockScope} call is the ONLY thing that can be blocking, exactly the
     * standard {@link #landItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch} holds {@code land} to.
     */
    @Test
    void resolveIndeterminateItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch() throws Exception {
        Landing l = new Landing();
        com.solace.sm.dp.apply.model.ApplyId first = l.admitAndStart(5);
        ApplyEngineHarness.outcomes().land(l.scope.tenant(), first,
                com.solace.sm.dp.apply.model.RequestState.APPLIED);
        assertEquals(5, ApplyEngineHarness.scopes().placementWatermark(l.scope.tenant(),
                        l.scope.instance(), l.scope.placement()),
                "the watermark row must exist before the walk under test, or this is the INSERT "
                        + "case again");

        com.solace.sm.dp.apply.model.ApplyId second = l.admitAndStart(6);
        ApplyEngineHarness.outcomes().land(l.scope.tenant(), second,
                com.solace.sm.dp.apply.model.RequestState.APPLY_INDETERMINATE);

        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Long> blockedMillis = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> ApplyEngineHarness.transactions()
                    .execute(status -> {
                        ApplyEngineHarness.scopes().lockScope(l.scope.tenant(), l.scope.instance());
                        held.countDown();
                        sleep(HOLD_MILLIS);
                        return null;
                    }));
            assertTrue(held.await(30, TimeUnit.SECONDS), "the holder never took the scope row");

            Future<?> resolving = pool.submit(() -> {
                long t0 = System.nanoTime();
                ApplyEngineHarness.outcomes().resolveIndeterminate(l.scope.tenant(), second,
                        new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                                com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                                new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(6, List.of(
                                        new com.solace.sm.dp.apply.model.ReplicaObservation(
                                                new com.solace.sm.dp.apply.model.ReplicaRef(
                                                        "cache-engine-1"),
                                                true, null)))));
                blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                return null;
            });
            String wait = pollForLockWait();
            holder.get(60, TimeUnit.SECONDS);
            resolving.get(60, TimeUnit.SECONDS);

            System.out.println("MEASURED: OutcomeService.resolveIndeterminate (DO UPDATE branch) "
                    + "blocked " + blockedMillis.get() + " ms behind a held scope row (holder held "
                    + HOLD_MILLIS + " ms); pg_stat_activity showed " + wait);
            assertEquals("Lock", waitEventType(wait),
                    "resolveIndeterminate() must be parked on a LOCK while a third party holds the "
                            + "scope row. Was: " + wait);
            assertTrue(wait.contains("FOR UPDATE"),
                    "…and on lockScope's SELECT … FOR UPDATE specifically: on the DO UPDATE branch "
                            + "nothing else in resolveIndeterminate() touches apply_scope at all. "
                            + "Was: " + wait);
            assertTrue(blockedMillis.get() >= HOLD_MILLIS - 250,
                    "resolveIndeterminate() returned in " + blockedMillis.get() + " ms, before the "
                            + HOLD_MILLIS + " ms hold ended — it did not take the scope lock");
            assertEquals(com.solace.sm.dp.apply.model.RequestState.INDETERMINATE_RESOLVED,
                    ApplyEngineHarness.requests().find(l.scope.tenant(), second).orElseThrow()
                            .state(),
                    "…and it resolved once the lock was free");
            assertEquals(6, ApplyEngineHarness.scopes().placementWatermark(l.scope.tenant(),
                            l.scope.instance(), l.scope.placement()),
                    "…and the watermark raised through the DO UPDATE branch it was supposed to");
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * One placement, and the admission‖landing choreography both landing tests share.
     *
     * <p>{@link #land} mirrors {@code OutcomeService.land} statement for statement — lock the scope
     * row, transition, raise the watermark — held open by hand so a second thread can be watched
     * blocking on it. Mirroring matters: a manual landing that skipped the lock would be measuring a
     * mechanism production does not use.
     */
    private static final class Landing {

        private final UUID tenant = SmDataPostgres.freshTenant();
        private final ScopeKey scope = ApplyEngineHarness.scope(tenant, UUID.randomUUID(),
                new PlacementId(UUID.randomUUID()));

        com.solace.sm.dp.apply.model.ApplyId admitAndStart(long generation) {
            com.solace.sm.dp.apply.model.ApplyId id = assertInstanceOf(
                    AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(scope, generation))).applyId();
            ApplyEngineHarness.outcomes().start(scope.tenant(), id);
            return id;
        }

        void run(long generation, String label) throws Exception {
            com.solace.sm.dp.apply.model.ApplyId running = admitAndStart(generation);

            CountDownLatch landingOpen = new CountDownLatch(1);
            CountDownLatch mayCommit = new CountDownLatch(1);
            AtomicReference<Long> blockedMillis = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> landing = pool.submit(() -> ApplyEngineHarness.transactions()
                        .execute(status -> {
                            land(running, generation);
                            landingOpen.countDown();
                            try {
                                if (!mayCommit.await(30, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("never released");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        }));
                assertTrue(landingOpen.await(30, TimeUnit.SECONDS), "the landing never opened");

                Future<AdmissionOutcome> contender = pool.submit(() -> {
                    long t0 = System.nanoTime();
                    AdmissionOutcome o = admission.admit(
                            ApplyEngineHarness.apply(scope, generation));
                    blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                    return o;
                });
                String wait = pollForLockWait();
                assertEquals("Lock", waitEventType(wait), label
                        + " — the admission must be parked on a lock while the landing is open. "
                        + "Was: " + wait);
                assertTrue(wait.contains("FOR UPDATE"), label
                        + " — the statement it is parked on must be lockScope's SELECT … FOR "
                        + "UPDATE, which is what land() now takes explicitly. Was: " + wait);
                Thread.sleep(HOLD_MILLIS);
                assertFalse(contender.isDone(), label + " — the admission must still be blocked "
                        + "while the landing is uncommitted (" + HOLD_MILLIS + " ms later)");

                mayCommit.countDown();
                landing.get(60, TimeUnit.SECONDS);
                AdmissionOutcome after = contender.get(60, TimeUnit.SECONDS);
                System.out.println("MEASURED: " + label + " — admission blocked "
                        + blockedMillis.get() + " ms, then " + after
                        + "; pg_stat_activity showed " + wait);

                AdmissionOutcome.Refused fenced = assertInstanceOf(AdmissionOutcome.Refused.class,
                        after, "once the landing commits, its raised watermark fences the same "
                                + "generation");
                assertEquals(
                        com.solace.sm.dp.apply.engine.RefusalReason.GENERATION_NOT_ABOVE_WATERMARK,
                        fenced.reason(), fenced.detail());
                assertInstanceOf(AdmissionOutcome.Admitted.class,
                        admission.admit(ApplyEngineHarness.apply(scope, generation + 1)),
                        "…and the next generation is admissible: the STAGED elder is superseded");
            } finally {
                pool.shutdownNow();
            }
        }

        /** {@code OutcomeService.land(STAGED)}, statement for statement, inside a caller's transaction. */
        private void land(com.solace.sm.dp.apply.model.ApplyId id, long generation) {
            ApplyEngineHarness.scopes().lockScope(scope.tenant(), scope.instance());
            ApplyEngineHarness.requests().transition(scope.tenant(), id,
                    com.solace.sm.dp.apply.model.RequestState.RUNNING,
                    com.solace.sm.dp.apply.model.RequestState.STAGED, java.time.Instant.now());
            ApplyEngineHarness.scopes().raisePlacementWatermark(scope.tenant(), scope.instance(),
                    scope.placement(), generation);
        }
    }

    /** One tenant/instance, its two scope shapes, and the two-thread choreography they share. */
    private final class Fixture {

        private final UUID tenant = SmDataPostgres.freshTenant();
        private final UUID instance = UUID.randomUUID();
        private final ScopeKey wide =
                ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);
        private final ScopeKey placement =
                ApplyEngineHarness.scope(tenant, instance, new PlacementId(UUID.randomUUID()));

        /**
         * Thread A takes the scope row and holds it; thread B admits into {@code contender} and must
         * block until A commits, then be refused.
         */
        void run(ScopeKey holder, ScopeKey contender, String label) throws Exception {
            // The scope row is COMMITTED before either thread starts. Measured (plan 004's
            // adversarial review): without this, the contender blocks on the holder's UNCOMMITTED
            // ensureScopeRow index entry — pg_stat_activity showed the INSERT … ON CONFLICT — and the
            // test stayed green with FOR UPDATE deleted from lockScope. With the row pre-committed the
            // only thing left to block on is the row lock itself, which is what obligation (d) is about.
            ApplyEngineHarness.transactions().executeWithoutResult(st ->
                    ApplyEngineHarness.scopes().ensureScopeRow(holder.tenant(), holder.instance()));

            CountDownLatch held = new CountDownLatch(1);
            AtomicReference<Long> blockedMillis = new AtomicReference<>();
            AtomicReference<AdmissionOutcome> outcome = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> a = pool.submit(() -> ApplyEngineHarness.transactions()
                        .execute(status -> {
                            ApplyEngineHarness.scopes()
                                    .ensureScopeRow(holder.tenant(), holder.instance());
                            ApplyEngineHarness.scopes()
                                    .lockScope(holder.tenant(), holder.instance());
                            held.countDown();
                            sleep(HOLD_MILLIS);
                            ApplyEngineHarness.requests()
                                    .insert(ApplyEngineHarness.requestedApply(holder, 10));
                            return null;
                        }));

                assertTrue(held.await(30, TimeUnit.SECONDS), "thread A never took the lock");

                Future<?> b = pool.submit(() -> {
                    long t0 = System.nanoTime();
                    outcome.set(admission.admit(ApplyEngineHarness.apply(contender, 11)));
                    blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                    return null;
                });

                String wait = pollForLockWait();
                a.get(60, TimeUnit.SECONDS);
                b.get(60, TimeUnit.SECONDS);

                System.out.println("MEASURED: " + label + " — contender blocked "
                        + blockedMillis.get() + " ms (holder held " + HOLD_MILLIS
                        + " ms); pg_stat_activity showed " + wait);

                assertEquals("Lock", waitEventType(wait),
                        "the contender must be waiting on a LOCK while the holder holds the scope "
                                + "row, not merely be slow. pg_stat_activity said: " + wait);
                assertTrue(wait.contains("FOR UPDATE"),
                        "the statement the contender is parked on must be lockScope's SELECT … FOR "
                                + "UPDATE — blocking on anything else (measured: the holder's "
                                + "uncommitted upsert) proves nothing about the row lock. Was: " + wait);
                assertTrue(blockedMillis.get() >= HOLD_MILLIS - 250,
                        "the contender returned in " + blockedMillis.get() + " ms, before the "
                                + HOLD_MILLIS + " ms hold ended — it did not serialize on the scope "
                                + "row at all");
                AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class,
                        outcome.get(), "after the holder commits, the contender must refuse CLEANLY");
                assertEquals(com.solace.sm.dp.apply.engine.RefusalReason.SCOPE_BUSY,
                        refused.reason(), refused.detail());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Watches {@code pg_stat_activity} from a third, SUPERUSER connection for a {@code sm_dp_runtime}
     * backend parked on a lock. Superuser because a non-superuser sees only its own backends' query
     * text and wait state in PG 14.
     *
     * <p><b>The query matches ANY {@code sm_dp_runtime} backend waiting on a {@code Lock}, not this
     * test's own pid.</b> Sound as this suite is configured today — no {@code junit-platform.properties}
     * enabling parallel execution, no {@code forkCount}/parallel Surefire setting anywhere in this
     * module — but turning on parallel test execution would make this poll match some OTHER lock test's
     * blocked backend just as easily as this one's, unsounding all six lock tests in this class at once,
     * silently (a false-positive "lock seen" with no guarantee it is the lock this test caused). If this
     * suite is ever run in parallel, this method needs the specific pid, not the usename.
     *
     * @return a human-readable description of what was seen, for the report
     */
    private static String pollForLockWait() throws InterruptedException {
        // Budget: twice the hold, derived rather than hard-coded — a poll that could run out exactly
        // when the hold ends (measured: 120 × 25 ms against a 3000 ms hold had zero margin) makes
        // obligation (d) flaky on a fast host.
        long deadline = System.nanoTime() + HOLD_MILLIS * 2 * 1_000_000;
        while (System.nanoTime() < deadline) {
            List<java.util.Map<String, Object>> rows = SmDataPostgres.superuserJdbc().queryForList(
                    "SELECT pid, wait_event_type, wait_event, state, query AS q"
                            + " FROM pg_stat_activity"
                            + " WHERE usename = 'sm_dp_runtime' AND wait_event_type = 'Lock'",
                    new MapSqlParameterSource());
            if (!rows.isEmpty()) {
                return rows.toString();
            }
            Thread.sleep(25);
        }
        return "no sm_dp_runtime backend was ever seen waiting on a Lock";
    }

    private static String waitEventType(String polled) {
        return polled.contains("wait_event_type=Lock") ? "Lock" : polled;
    }

    private static int backendPid(Connection c) throws SQLException {
        var rs = c.createStatement().executeQuery("SELECT pg_backend_pid()");
        rs.next();
        return rs.getInt(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
