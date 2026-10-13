package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.UnexpectedRollbackException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The engine's transactions are its own — a caller's enclosing transaction can neither swallow them
 * nor be poisoned by them.
 *
 * <p>Measured in plan 004's adversarial review with the default {@code PROPAGATION_REQUIRED}:
 * {@code AttemptService.open()} returned its row inside a caller's transaction and the row then rolled
 * back with the caller — §2.2 rule 0's "no attempt row provably means nothing was issued" silently
 * voided. The fix is {@code EngineTransactions.requiresNew}; these tests are what keep it there.
 */
@Testcontainers(disabledWithoutDocker = true)
class EngineTransactionBoundaryIT {

    private static final String HASH = "sha256:6666666666666666666666666666666666666666";

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    @Test
    void anAttemptOpenedInsideACallersTransactionSurvivesTheCallersRollback() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AtomicReference<AttemptRow> opened = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> ApplyEngineHarness.transactions()
                .executeWithoutResult(status -> {
                    opened.set(service.open(f.tenant, f.apply, f.intent, null, Duration.ofMinutes(1)));
                    // Rule 0: at this point the caller "may touch the adapter". The row must already
                    // be durable — whatever the caller's own transaction does next.
                    assertEquals(1, ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()));
                    throw new IllegalStateException("the caller's own work failed");
                }));

        assertEquals(1, ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()),
                "the attempt row must outlive the caller's rollback, or resume would read 'nothing "
                        + "was issued' for a step that was");
        assertEquals(opened.get().attemptId(),
                ApplyEngineHarness.attempts().latest(f.tenant, f.intent.intentId()).orElseThrow()
                        .attemptId());
    }

    @Test
    void anAdmissionInsideACallersTransactionIsCommittedOnItsOwnAndTheCallersRollbackIsClean() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        ScopeKey scope = InventoryFixtures.registered(new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                PlacementId.INSTANCE_WIDE));
        AtomicReference<AdmissionOutcome> outcome = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> ApplyEngineHarness.transactions()
                .executeWithoutResult(status -> {
                    outcome.set(ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scope, 1)));
                    throw new IllegalStateException("the caller's own work failed");
                }));

        ApplyId admitted = assertInstanceOf(AdmissionOutcome.Admitted.class, outcome.get()).applyId();
        assertEquals(RequestState.REQUESTED,
                ApplyEngineHarness.requests().find(tenant, admitted).orElseThrow().state(),
                "admission is the DP's audit record of the operator's decision; it does not roll back "
                        + "because a caller's unrelated work did");

        // …and a refusal inside a caller's transaction is returned as a VALUE while the enclosing
        // transaction commits normally (the block below would throw otherwise). Stated precisely:
        // SCOPE_BUSY never marks anything rollback-only; the one admit path that does —
        // FENCE_RACE_LOST — is unreachable through admit (see RefusalReason). The reachable
        // setRollbackOnly path is AttemptService.open's DATABASE_FENCE-provenance ConcurrentAttempt
        // ONLY — its PRE_CHECK provenance throws directly out of the tx.execute callback and never
        // calls status.setRollbackOnly() at all, relying on TransactionTemplate's own catch-rollback-
        // rethrow instead (see AttemptService.open's own control flow). Both are exercised, INSIDE a
        // caller transaction, by aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback
        // below, but WHICH provenance that race produces is not guaranteed by construction — true
        // there today only by observed timing, not by the test's own design. The DETERMINISTIC
        // PRE_CHECK-with-no-setRollbackOnly path is what
        // aSequentialSecondOpenInsideACallersTransactionIsPreCheckAndTheCallerCommitsCleanly proves,
        // with no race required. (Fix round 1, Important finding 2: AttemptIT's own concurrent-open
        // race runs with NO caller transaction at all, so it never touched this property — moved here
        // instead.)
        AtomicReference<AdmissionOutcome> refused = new AtomicReference<>();
        ApplyEngineHarness.transactions().executeWithoutResult(status ->
                refused.set(ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scope, 1))));
        assertInstanceOf(AdmissionOutcome.Refused.class, refused.get(),
                "the second admission for the same scope is refused (SCOPE_BUSY) and the enclosing "
                        + "transaction has already committed by the time this line runs");
    }

    /**
     * The engine's SECOND stated reason for {@code REQUIRES_NEW} — fix round 1's Important finding
     * 2. {@code EngineTransactions}' own javadoc gives two reasons the propagation exists: rule 0's
     * attempt-row survival (the test above), and "a lost fence race … marks the transaction
     * rollback-only, which under an enclosing transaction surfaces as an
     * {@link UnexpectedRollbackException} at the OUTER commit instead of as the refusal value".
     * Nothing before this test exercised the second reason: {@code AttemptIT}'s own concurrent-open
     * race runs with NO caller transaction at all, so the "outer commit" property was never in play,
     * however reachable {@code ConcurrentAttempt} itself was.
     *
     * <p>Here the SAME race — two openers for one intent — is driven from inside TWO SEPARATE caller
     * transactions, one per thread, each catching {@link AttemptService.ConcurrentAttempt} INSIDE its
     * own transactional callback so the refusal is a VALUE from the caller's point of view and never
     * an exception the callback lets escape. Under the shipped {@code REQUIRES_NEW}, the loser's inner
     * {@code open()} runs in a SEPARATE physical transaction that {@code setRollbackOnly()} poisons
     * only for itself; the caller's own enclosing transaction never shares that transaction and commits
     * cleanly. Were {@code EngineTransactions.requiresNew} ever weakened to {@code REQUIRED}, the
     * loser's {@code open()} would JOIN the same physical transaction as its caller, so
     * {@code setRollbackOnly()} marks THAT shared transaction; the caller's own
     * {@code executeWithoutResult} then finds it global-rollback-only at commit time and Spring rolls
     * back and throws {@link UnexpectedRollbackException} OUT of the caller's call — which neither
     * thread here should ever see.
     *
     * <p><b>{@code BE-D20}:</b> before {@code attempt_open_fence} landed, whether the two opens ended
     * up colliding on the same {@code attempt_no} (refused by the numbering UNIQUE) or serializing
     * onto two different ones (which that UNIQUE alone cannot refuse at all — measured,
     * deterministically, by an external review's {@code BUILD FAILURE} at this task's base commit) was
     * NOT under this test's control, so a serialize-instead-of-collide run proved nothing about the
     * property under test here. The fence removes that dependency: exactly one attempt row lands
     * EITHER way, so this test's outcome no longer depends on which of the two happened. Which fence
     * name PostgreSQL actually reports for the loser is measured and recorded below, across repeated
     * runs, rather than asserted as one fixed name (design §3.4's classification note).
     * {@code AttemptService.open}'s own pre-check ({@code AttemptRepository#openState}) adds a
     * THIRD possible provenance for the loser here: if its pre-check runs after the winner has already
     * committed, it is refused before ever attempting an insert, and names no PostgreSQL constraint at
     * all. Both provenances are legitimate for this race and are asserted separately below, by
     * {@link AttemptService.ConcurrentAttempt#provenance()}.
     */
    @Test
    void aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback()
            throws Exception {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    AtomicReference<Object> outcome = new AtomicReference<>();
                    try {
                        ApplyEngineHarness.transactions().executeWithoutResult(status -> {
                            try {
                                outcome.set(service.open(f.tenant, f.apply, f.intent, null,
                                        Duration.ofMinutes(1)));
                            } catch (AttemptService.ConcurrentAttempt e) {
                                // Caught INSIDE the caller's own transactional callback — from here
                                // on the refusal is a VALUE, never an exception the callback lets
                                // escape.
                                outcome.set(e);
                            }
                        });
                    } catch (RuntimeException e) {
                        // Under a REQUIRES_NEW -> REQUIRED regression this is exactly where
                        // UnexpectedRollbackException would surface — at the CALLER's own commit,
                        // outside the try/catch around open() above.
                        outcome.set(e);
                    }
                    return outcome.get();
                }));
            }
            Object first = futures.get(0).get(60, TimeUnit.SECONDS);
            Object second = futures.get(1).get(60, TimeUnit.SECONDS);

            long rows = Stream.of(first, second).filter(o -> o instanceof AttemptRow).count();
            long losers = Stream.of(first, second)
                    .filter(o -> o instanceof AttemptService.ConcurrentAttempt).count();
            long unexpected = Stream.of(first, second)
                    .filter(o -> !(o instanceof AttemptRow)
                            && !(o instanceof AttemptService.ConcurrentAttempt))
                    .count();
            AttemptService.ConcurrentAttempt loser = Stream.of(first, second)
                    .filter(o -> o instanceof AttemptService.ConcurrentAttempt)
                    .map(o -> (AttemptService.ConcurrentAttempt) o)
                    .findFirst().orElse(null);
            String refusedBy = loser == null ? null : loser.constraintName();
            AttemptService.ConcurrentAttempt.Provenance provenance =
                    loser == null ? null : loser.provenance();
            System.out.println("MEASURED: concurrent open inside two caller transactions — " + rows
                    + " row(s) opened, " + losers + " ConcurrentAttempt, provenance " + provenance
                    + ", refused by " + refusedBy + ", " + unexpected + " other; attempts on the "
                    + "intent = " + ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()));
            assertEquals(1, ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()),
                    "exactly one attempt row lands regardless of whether the two opens collided on "
                            + "the same attempt_no or serialized onto different ones — BE-D20's fence "
                            + "removes the old dependency on which one happened");
            assertEquals(0, unexpected, "neither caller's own transaction may surface anything "
                    + "OTHER than the typed refusal caught inside it — an "
                    + "UnexpectedRollbackException escaping to the caller here IS "
                    + "EngineTransactions' second stated reason regressing. Got: " + first + " / "
                    + second);
            assertEquals(1, rows, "one winner. Got: " + first + " / " + second);
            assertEquals(1, losers, "one loser told ConcurrentAttempt, caught inside its OWN caller "
                    + "transaction, with that caller transaction then committing cleanly. Got: "
                    + first + " / " + second);
            // Widened, not weakened: the loser may have lost to its OWN pre-check (no insert ever
            // attempted, no PostgreSQL name to report) or to the database fence backstopping a
            // pre-check that ran before the winner committed (which DOES name one of the two
            // fences). Both are legitimate for this race. Stated honestly about what each branch
            // below actually proves: the PRE_CHECK branch's assertNull is guaranteed by
            // ConcurrentAttempt.preCheck's OWN construction (constraintName is hardcoded null there)
            // — it restates that invariant rather than exercising anything this race could falsify.
            // The DATABASE_FENCE branch's assertTrue IS the live check here: it fails the moment
            // PostgreSQL ever reports a name outside the two BE-D20 fences. What both branches
            // together still prove, regardless of which fires, is the assertions ABOVE this comment
            // — one winner, one ConcurrentAttempt loser, and (per `unexpected` above) no
            // UnexpectedRollbackException reaching either caller.
            if (provenance == AttemptService.ConcurrentAttempt.Provenance.PRE_CHECK) {
                assertNull(refusedBy,
                        "the pre-check path issued no insert — PostgreSQL named nothing. Got: "
                                + refusedBy);
            } else {
                assertEquals(AttemptService.ConcurrentAttempt.Provenance.DATABASE_FENCE, provenance,
                        "a ConcurrentAttempt must carry one of the two known provenances");
                assertTrue(List.of(Constraints.ATTEMPT_NO_UNIQUE, Constraints.ATTEMPT_OPEN_FENCE)
                                .contains(refusedBy),
                        "the database-fence path must name one of the two fences BE-D20 classifies "
                                + "— got " + refusedBy);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The DETERMINISTIC counterpart to the race above, no thread pool or barrier required: in
     * production, the ORDINARY trigger for {@code ConcurrentAttempt.Provenance.PRE_CHECK} is not a
     * race at all — it is a caller retrying against an intent whose prior attempt is still open,
     * sequentially, on one thread. Nothing before this test pinned that path by construction: the
     * two races above OBSERVE it sometimes (this file's own comment above named it "true today only
     * by observed timing"), which is not the same as proving it. Here the second {@code open()} runs
     * strictly after the first has committed, on the SAME thread, inside the SAME caller transaction
     * — {@code hasOpen} cannot help but read {@code true}, so this is {@code PRE_CHECK} on every run,
     * never {@code DATABASE_FENCE}, and {@code status.setRollbackOnly()} is never reached at all (the
     * pre-check throws directly out of {@code AttemptService.open}'s {@code tx.execute} callback,
     * aborting only that inner {@code REQUIRES_NEW} transaction — never the caller's own).
     */
    @Test
    void aSequentialSecondOpenInsideACallersTransactionIsPreCheckAndTheCallerCommitsCleanly() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AtomicReference<AttemptRow> opened = new AtomicReference<>();
        AtomicReference<AttemptService.ConcurrentAttempt> loser = new AtomicReference<>();

        ApplyEngineHarness.transactions().executeWithoutResult(status -> {
            opened.set(service.open(f.tenant, f.apply, f.intent, null, Duration.ofMinutes(1)));
            try {
                service.open(f.tenant, f.apply, f.intent, null, Duration.ofMinutes(1));
            } catch (AttemptService.ConcurrentAttempt e) {
                // Caught INSIDE the caller's own transactional callback, exactly as the race tests
                // above do — so the caller's executeWithoutResult below returning normally (no
                // UnexpectedRollbackException) is itself part of what this test proves.
                loser.set(e);
            }
        });

        assertEquals(1, ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()),
                "only the FIRST open's row may have landed — the second never reached an insert to "
                        + "land one, sequential or not");
        assertNotNull(loser.get(), "the second, sequential open must have been refused");
        assertEquals(AttemptService.ConcurrentAttempt.Provenance.PRE_CHECK, loser.get().provenance(),
                "deterministic: no concurrent opener is involved, so the pre-check alone refuses "
                        + "this — the database fence is never reached because no second insert is "
                        + "ever attempted");
        assertNull(loser.get().constraintName(),
                "the pre-check path issued no insert — PostgreSQL named nothing");
    }

    /** A fresh tenant with one open apply and one MATERIALIZE intent on it. */
    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final ApplyId apply;
        private final StepIntentRow intent;

        Fixture() {
            PlacementId placement = new PlacementId(UUID.randomUUID());   // placement-scoped since V021 (BE-D44)
            ScopeKey wide = InventoryFixtures.registered(new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                    placement));
            apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(wide, 10))).applyId();
            intent = new StepIntentRow(Uuid7.next(), apply, placement, Step.MATERIALIZE,
                    new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), HASH,
                    Instant.now());
            ApplyEngineHarness.intents().insert(tenant, intent);
        }
    }
}
