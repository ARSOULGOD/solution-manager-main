package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (f), (g) and (q) — the intent table's two UNIQUEs, its two shape CHECKs, and the one
 * rule it has no artifact for.
 *
 * <p>Obligation (q) is the interesting one: the design says intents-only-for-open-requests is
 * engine-enforced because the FK can only say the request EXISTS. This class proves that claim from
 * both sides — the service refuses, and the raw repository insert against the same terminal request
 * SUCCEEDS. Without the second half, "engine-enforced" would be an assertion about the schema that
 * nobody had checked.
 */
@Testcontainers(disabledWithoutDocker = true)
class StepIntentIT {

    private static final String HASH = "sha256:1111111111111111111111111111111111111111";
    private static final String OTHER_HASH = "sha256:2222222222222222222222222222222222222222";

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static StepIntentRepository intents;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        intents = ApplyEngineHarness.intents();
    }

    // -------------------------------------------------------------------------------------------
    // (g): one durable intent per logical step, and the two ways of getting it wrong.
    // -------------------------------------------------------------------------------------------

    @Test
    void ensuringTheSameIntentTwiceWithTheSameKeyAndHashReturnsTheSameRow() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        StepIntentRow first = service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE,
                f.replica, f.key, HASH);
        StepIntentRow again = service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE,
                f.replica, f.key, HASH);

        assertEquals(first.intentId(), again.intentId(),
                "the binding is a ROW; a retry must land on the one that exists");
        assertEquals(1, intents.forRequest(f.tenant, f.apply).size());
        assertEquals(0, service.arbiterReFinds(),
                "the second call found the row on its first read — no race, no re-find");
    }

    @Test
    void aRetryPresentingTheRecordedKeyWithADifferentHashIsRejectedByTheReadCompare() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica, f.key, HASH);

        StepIntentService.IntentMismatch e = assertThrows(StepIntentService.IntentMismatch.class,
                () -> service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica, f.key,
                        OTHER_HASH));
        assertTrue(e.getMessage().contains("intends hash"), e.getMessage());
    }

    @Test
    void aSecondIntentForTheSameStepAndReplicaWithADifferentKeyIsRejectedAtBothLayers() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica, f.key, HASH);

        assertThrows(StepIntentService.IntentMismatch.class,
                () -> service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica,
                        f.key + "-different", HASH),
                "the engine's read-compare catches it first");

        // …and the composite UNIQUE catches a writer that went around the engine. The key differs,
        // so only ONE of the two indexes can fire here — which is what makes this the composite's
        // own proof rather than a repeat of the idempotency-key case below.
        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.INTENT_STEP_REPLICA_UNIQUE,
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement,
                        Step.MATERIALIZE, f.replica, f.key + "-different", HASH, Instant.now())));
    }

    @Test
    void reusingAnIdempotencyKeyUnderAnotherStepIsRejectedAtBothLayers() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica, f.key, HASH);

        assertThrows(StepIntentService.IdempotencyKeyReused.class,
                () -> service.ensureIntent(f.tenant, f.apply, f.placement, Step.LIFECYCLE, f.replica, f.key,
                        null));

        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION,
                Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE,
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement,
                        Step.LIFECYCLE, f.replica, f.key, null, Instant.now())));
    }

    /**
     * WHICH index PostgreSQL names when a duplicate violates BOTH — the fact
     * {@link StepIntentService}'s classification is built around.
     *
     * <p>Design §2.2 rule 2 originally called the composite the "arbiter" and classified by name.
     * Measured here: an identical re-insert trips {@code apply_step_intent_idempotency_key_key}
     * first. Recorded rather than merely asserted, since the design's amended text cites the name.
     */
    @Test
    void anIdenticalRawReInsertNamesTheIdempotencyKeyIndexNotTheCompositeArbiter() {
        Fixture f = new Fixture();
        StepIntentRow row = new StepIntentRow(Uuid7.next(), f.apply, f.placement, Step.MATERIALIZE, f.replica,
                f.key, HASH, Instant.now());
        intents.insert(f.tenant, row);

        PgConstraintViolation v = Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, null,
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement,
                        Step.MATERIALIZE, f.replica, f.key, HASH, Instant.now())));
        System.out.println("MEASURED: a duplicate violating BOTH intent UNIQUEs is reported as "
                + v.constraint());
        assertEquals(Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE, v.constraint(),
                "if this ever becomes the composite, StepIntentService's comment is stale");
    }

    @Test
    void twoThreadsEnsuringTheSameIntentConcurrentlyBothReceiveTheOneRowAndTheLoserReFinds()
            throws Exception {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<StepIntentRow>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica,
                            f.key, HASH);
                }));
            }
            StepIntentRow a = futures.get(0).get(60, TimeUnit.SECONDS);
            StepIntentRow b = futures.get(1).get(60, TimeUnit.SECONDS);

            assertEquals(a.intentId(), b.intentId(),
                    "the race must resolve to exactly one durable intent, both callers seeing it");
            assertEquals(1, intents.forRequest(f.tenant, f.apply).size(),
                    "…and exactly one row must exist");
            System.out.println("MEASURED: arbiter race re-find path executed "
                    + service.arbiterReFinds() + " time(s) for 2 concurrent ensureIntent calls");
            assertEquals(1, service.arbiterReFinds(),
                    "the loser must actually have gone down the fresh-transaction re-find path. "
                            + "Zero would mean the two calls never really collided and this test "
                            + "proves nothing about the race");
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------------------------
    // (q): engine-enforced, with the proof that the database does NOT enforce it.
    // -------------------------------------------------------------------------------------------

    @Test
    void theServiceRefusesAnIntentOnATerminalRequestWhileTheRawInsertStillSucceeds() {
        Fixture f = new Fixture();
        outcomes.start(f.tenant, f.apply);
        outcomes.land(f.tenant, f.apply, RequestState.APPLIED);

        StepIntentService service = ApplyEngineHarness.stepIntents();
        StepIntentService.TerminalRequest e = assertThrows(StepIntentService.TerminalRequest.class,
                () -> service.ensureIntent(f.tenant, f.apply, f.placement, Step.MATERIALIZE, f.replica, f.key,
                        HASH));
        assertTrue(e.getMessage().contains("obligation (q)"), e.getMessage());

        // The other half of the claim: no DDL artifact stops this.
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, Step.MATERIALIZE,
                f.replica, f.key + "-raw", HASH, Instant.now()));
        assertEquals(1, intents.forRequest(f.tenant, f.apply).size(),
                "the FK only says the request EXISTS — 'still open' is a temporal rule no CHECK can "
                        + "hold, which is exactly why obligation (q) is engine-side");
    }

    // -------------------------------------------------------------------------------------------
    // (f): hash_on_config_steps and placement_scoped_steps, both directions each.
    // -------------------------------------------------------------------------------------------

    @Test
    void aConfigCarryingStepWithoutAHashIsRefusedByHashOnConfigSteps() {
        Fixture f = new Fixture();
        for (Step step : List.of(Step.MATERIALIZE, Step.VERIFY, Step.ROLLBACK)) {
            ReplicaRef replica = step.placementScoped() ? ReplicaRef.PLACEMENT : f.replica;
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, "hash_on_config_steps",
                    () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, step,
                            replica, f.freshKey(), null, Instant.now())));
        }
    }

    @Test
    void aHashlessStepCarryingAHashIsRefusedByTheSameCheckFromTheOtherSide() {
        Fixture f = new Fixture();
        for (Step step : List.of(Step.STOP, Step.PREFLIGHT, Step.LIFECYCLE, Step.ROLLBACK_ASSESS)) {
            ReplicaRef replica = step.placementScoped() ? ReplicaRef.PLACEMENT : f.replica;
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, "hash_on_config_steps",
                    () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, step,
                            replica, f.freshKey(), HASH, Instant.now())));
        }
    }

    @Test
    void aPlacementScopedStepAddressingARealReplicaIsRefusedByPlacementScopedSteps() {
        Fixture f = new Fixture();
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "placement_scoped_steps",
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement,
                        Step.PREFLIGHT, new ReplicaRef("r1"), f.freshKey(), null, Instant.now())));
    }

    @Test
    void aReplicaScopedStepAddressingThePlacementSentinelIsRefusedByTheSameCheck() {
        Fixture f = new Fixture();
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "placement_scoped_steps",
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement,
                        Step.MATERIALIZE, ReplicaRef.PLACEMENT, f.freshKey(), HASH, Instant.now())));
    }

    @Test
    void theLegalCombinationsOfBothChecksAreAcceptedThePositiveControl() {
        Fixture f = new Fixture();
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, f.freshKey(), null, Instant.now()));
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, Step.MATERIALIZE,
                f.replica, f.freshKey(), HASH, Instant.now()));
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, Step.LIFECYCLE,
                f.replica, f.freshKey(), null, Instant.now()));
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, f.placement, Step.ROLLBACK,
                ReplicaRef.PLACEMENT, f.freshKey(), HASH, Instant.now()));
        assertEquals(4, intents.forRequest(f.tenant, f.apply).size());
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A fresh tenant with one admitted, still-open apply to hang intents off. It is PLACEMENT-scoped since V021
     * ({@code BE-D44}): an intent's placement must be its request's own, and an instance-wide APPLY is not a request
     * the engine ever admits ({@code BE-D45}), so a fixture that used one was borrowing a scope with no placement.
     */
    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final InstanceId instance = new InstanceId(UUID.randomUUID());
        private final PlacementId placement = new PlacementId(UUID.randomUUID());
        private final ScopeKey wide = InventoryFixtures.registered(new ScopeKey(tenant, instance, placement));
        private final ReplicaRef replica = new ReplicaRef("cache-engine-1");
        private final ApplyId apply;
        private final String key;

        Fixture() {
            apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(wide, 10))).applyId();
            key = "idem-" + apply.value();
        }

        String freshKey() {
            return "idem-" + UUID.randomUUID();
        }
    }
}
