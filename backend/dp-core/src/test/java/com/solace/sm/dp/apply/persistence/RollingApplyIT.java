package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code SM-D15}'s restart strategies over the real {@code sm_data} journal — the half of the step
 * loop {@code manual} never reaches, and the first tests in this plan that can falsify
 * stage-everywhere ordering, because {@code manual} has no lifecycle step to interleave with.
 */
@Testcontainers(disabledWithoutDocker = true)
class RollingApplyIT {

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        instance = new InstanceId(UUID.randomUUID());
        placementId = new PlacementId(UUID.randomUUID());
        scope = InventoryFixtures.registered(new ScopeKey(tenant, instance, placementId));
        placement = new PlacementRef(tenant, instance, placementId);
    }

    private ApplyExecutor executor(RuntimeAdapter adapter) {
        return ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_7);
    }

    private ApplyCommand rolling(Strategy strategy) {
        return ApplyEngineHarness.rollingApply(scope, 7, strategy);
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    private List<StepIntentRow> intentsFor(ApplyId id, Step step) {
        return ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                .filter(i -> i.step() == step).toList();
    }

    private AttemptRow latestAttempt(StepIntentRow intent) {
        return ApplyEngineHarness.attempts().latest(tenant, intent.intentId()).orElseThrow();
    }

    // ---- one at a time -------------------------------------------------------------------------

    @Test
    void aRollingHealthGatedApplyRestartsReplicasOneAtATime() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        PreBaseline replica2Before = delegate.status(delegate.replicas().get(1)).baseline();
        Recording adapter = new Recording(delegate);

        ApplyOutcome outcome = executor(adapter).execute(rolling(Strategy.ROLLING_HEALTH_GATED));

        ApplyId id = assertInstanceOf(ApplyOutcome.Landed.class, outcome).applyId();
        assertEquals(RequestState.APPLIED, stateOf(id));
        assertEquals(2, intentsFor(id, Step.LIFECYCLE).size(), "one LIFECYCLE intent per replica");

        int first = adapter.calls.indexOf("startOrUpdate:replica-1");
        int second = adapter.calls.indexOf("startOrUpdate:replica-2");
        assertTrue(first >= 0 && second > first,
                "replica 1 restarts before replica 2. Calls were: " + adapter.calls);
        assertTrue(adapter.calls.subList(first + 1, second).contains("status:replica-1"),
                "replica 1's gate must run BETWEEN the two restarts — that ordering is the whole "
                        + "content of rolling-health-gated. Calls were: " + adapter.calls);

        // The durable half of the same claim: replica 2's LIFECYCLE attempt carries the baseline it
        // had before the apply started, so it was demonstrably not restarted earlier.
        StepIntentRow replica2Intent = intentsFor(id, Step.LIFECYCLE).stream()
                .filter(i -> i.replica().value().equals("replica-2")).findFirst().orElseThrow();
        assertEquals(replica2Before.startedAt(), latestAttempt(replica2Intent).preBaseline().startedAt(),
                "replica 2's pre_baseline is its pre-apply baseline; a value that had already moved "
                        + "would mean the loop restarted it before replica 1's gate passed");
    }

    /**
     * {@code SM-D15}'s stage-everywhere ordering, and the first test in this plan that can falsify
     * it: under {@code manual} there is no lifecycle step to interleave with.
     */
    @Test
    void everyMaterializeIntentPredatesEveryLifecycleIntent() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        ApplyId id = ((ApplyOutcome.Landed) executor(new Recording(delegate))
                .execute(rolling(Strategy.ROLLING_HEALTH_GATED))).applyId();

        Instant lastMaterialize = intentsFor(id, Step.MATERIALIZE).stream()
                .map(StepIntentRow::createdAt).max(Instant::compareTo).orElseThrow();
        Instant firstLifecycle = intentsFor(id, Step.LIFECYCLE).stream()
                .map(StepIntentRow::createdAt).min(Instant::compareTo).orElseThrow();

        assertFalse(firstLifecycle.isBefore(lastMaterialize),
                "a rollout that wrote replica 2's config only after replica 1 was already serving "
                        + "the new generation would be two generations wearing one apply id. Last "
                        + "MATERIALIZE " + lastMaterialize + ", first LIFECYCLE " + firstLifecycle
                        + ". Stated as \"no LIFECYCLE predates any MATERIALIZE\" rather than as a "
                        + "strict isBefore: created_at has microsecond resolution and equality "
                        + "would satisfy the property while failing a strict assertion.");
    }

    /**
     * {@code BE-D16} from the engine's side: a non-manual apply never even ASKS for {@code STAGED}.
     *
     * <p><b>How this is observed, and why the obvious way does not work.</b> An earlier version
     * sampled {@code ApplyRequestRepository.blockingRequests}, whose SQL is
     * {@code state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')} — {@code STAGED} is
     * structurally excluded from that result set, so the assertion could not fail whatever the
     * engine did. It was a tautology, and the mutation that was supposed to prove it fired in fact
     * fired on {@code OutcomeService.land} throwing.
     *
     * <p>Two observations that can actually fail. {@code stagedRequest} is the query that CAN
     * return a {@code STAGED} row on this scope, sampled at every adapter call; and the absence of
     * a thrown {@code IllegalStateException} is itself the proof that the engine never asked,
     * because {@code land} refuses a non-manual {@code STAGED} by name before any write. If the
     * engine asked, this test does not merely fail an assertion — {@code execute} throws.
     */
    @Test
    void aNonManualApplyIsNeverObservedStagedAndNeverAsksToBe() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        Recording adapter = new Recording(delegate);
        AtomicBoolean sawStagedOnTheScope = new AtomicBoolean();
        adapter.onEachCall = () -> {
            if (ApplyEngineHarness.requests().stagedRequest(tenant, instance, placementId)
                    .isPresent()) {
                sawStagedOnTheScope.set(true);
            }
        };

        ApplyOutcome outcome = executor(adapter).execute(rolling(Strategy.ROLLING_NO_VALIDATION));

        ApplyId id = assertInstanceOf(ApplyOutcome.Landed.class, outcome,
                "a thrown staged_requires_manual would mean the engine ASKED for STAGED").applyId();
        assertFalse(sawStagedOnTheScope.get(),
                "no STAGED row ever existed on this scope: STAGED is exclusively the manual "
                        + "strategy's terminal state (BE-D16)");
        assertEquals(RequestState.APPLIED, stateOf(id));
    }

    // ---- the health-signal refusal ---------------------------------------------------------------

    @Test
    void rollingHealthGatedIsRefusedWhenTheRuntimePublishesNoHealthSignal() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, false, "replica-1", "replica-2");

        ApplyOutcome outcome = executor(delegate).execute(rolling(Strategy.ROLLING_HEALTH_GATED));

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, outcome);
        assertEquals(RequestState.FAILED_NO_EFFECT, landed.state(),
                "refused at pre-flight, before a byte is written — so FAILED_NO_EFFECT is a "
                        + "measurement and not an assumption");
        assertEquals(new ErrorClass("strategy.health.signal.absent"), landed.errorClass());
        assertEquals(0, delegate.materializeCalls(), "nothing was written");
        assertTrue(intentsFor(landed.applyId(), Step.MATERIALIZE).isEmpty(),
                "no MATERIALIZE intent exists — that absence IS the no-effect evidence");
        assertEquals(0, delegate.restarts());
    }

    /** The same runtime, the strategy that does not need the signal: it must still succeed. */
    @Test
    void rollingNoValidationIsNotRefusedByAnAbsentHealthSignal() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, false, "replica-1", "replica-2");

        ApplyOutcome outcome = executor(delegate).execute(rolling(Strategy.ROLLING_NO_VALIDATION));

        assertEquals(RequestState.APPLIED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state(),
                "the refusal is a property of the GATE, not of the runtime: a strategy that "
                        + "promises no validation must not demand a signal to skip");
        assertEquals(2, delegate.restarts());
    }

    // ---- a gate failure between replicas ----------------------------------------------------------

    @Test
    void aGateFailureBetweenReplicasTerminatesConfigDiverged() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        Recording adapter = new Recording(delegate);
        adapter.neverHealthyAfterRestart.add("replica-2");

        ApplyOutcome outcome = executor(adapter).execute(rolling(Strategy.ROLLING_HEALTH_GATED));

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, outcome);
        assertEquals(RequestState.CONFIG_DIVERGED, landed.state(),
                "SM-D15 names divergence for this case. FAILED_ROLLED_BACK would be a claim that "
                        + "replica 1 was put back, and rolling back a replica already serving the "
                        + "new generation is a second outage");
        assertEquals(new ErrorClass("gate.unhealthy"), landed.errorClass());

        // Per-replica evidence, in rows: replica 1 passed, replica 2 did not.
        List<StepIntentRow> lifecycle = intentsFor(landed.applyId(), Step.LIFECYCLE);
        assertEquals(2, lifecycle.size());
        for (StepIntentRow intent : lifecycle) {
            AttemptRow attempt = latestAttempt(intent);
            if (intent.replica().value().equals("replica-1")) {
                assertEquals(AttemptOutcome.SUCCEEDED, attempt.outcome());
            } else {
                assertEquals(AttemptOutcome.FAILED, attempt.outcome());
                assertEquals(new ErrorClass("gate.unhealthy"), attempt.errorClass());
            }
            assertNotEquals(null, attempt.preBaseline(),
                    "LIFECYCLE is the one step baseline_only_for_lifecycle admits a baseline on, "
                            + "and the one step that needs it");
        }
    }

    // ---- restart-all -------------------------------------------------------------------------------

    @Test
    void restartAllRequiresAnOutageConfirmation() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.RESTART_ALL, null,
                        "operator-1"));
        assertTrue(refused.getMessage().contains("full_outage_confirmed"),
                "the model refuses it before the CHECK of the same name does. Was: "
                        + refused.getMessage());

        // ...and with the confirmation the executor's path to it is open and lands APPLIED.
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        ApplyOutcome outcome = executor(delegate).execute(rolling(Strategy.RESTART_ALL));

        assertEquals(RequestState.APPLIED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state());
        assertEquals(2, delegate.restarts());
    }

    @Test
    void aRollingNoValidationApplyRestartsWithoutGating() {
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        Recording adapter = new Recording(delegate);
        // Both replicas would need polling to pass a gate. Under no-validation nothing polls.
        adapter.neverHealthyAfterRestart.add("replica-1");
        adapter.neverHealthyAfterRestart.add("replica-2");

        ApplyOutcome outcome = executor(adapter).execute(rolling(Strategy.ROLLING_NO_VALIDATION));

        assertEquals(RequestState.APPLIED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state(),
                "a runtime that never reports healthy fails a GATE; under no-validation there is "
                        + "no gate to fail, and the apply completes");
        assertEquals(2, adapter.calls.stream().filter(c -> c.startsWith("startOrUpdate:")).count());
        for (String replica : List.of("replica-1", "replica-2")) {
            List<String> afterTheRestart = adapter.calls.subList(
                    adapter.calls.indexOf("startOrUpdate:" + replica) + 1, adapter.calls.size());
            assertEquals(2, afterTheRestart.stream().filter(c -> c.equals("status:" + replica))
                            .count(),
                    "exactly two observations of " + replica + " after its restart — the read-back "
                            + "that gives its LIFECYCLE attempt an observed_hash, and the post-restart "
                            + "VERIFY's (T6.9, OR-53) — and not one poll more. A gate against this "
                            + "runtime, which never reports healthy, would have spent the whole "
                            + "deadline here. Calls were: " + adapter.calls);
        }
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * Records the order of the seam calls, and can starve a named replica of its health signal
     * after it restarts — a per-replica fault {@link InMemoryRuntimeAdapter}'s global
     * {@code NEVER_HEALTHY} cannot express, and the one a between-replicas gate failure needs.
     */
    private static final class Recording implements RuntimeAdapter {

        private final InMemoryRuntimeAdapter delegate;
        final List<String> calls = new ArrayList<>();
        final Set<String> neverHealthyAfterRestart = new LinkedHashSet<>();
        private final Set<String> restarted = new LinkedHashSet<>();
        Runnable onEachCall = () -> {
        };

        Recording(InMemoryRuntimeAdapter delegate) {
            this.delegate = delegate;
        }

        private void record(String op, String replica) {
            calls.add(op + ":" + replica);
            onEachCall.run();
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            record("replicas", "@placement");
            return delegate.replicas(p);
        }

        @Override
        public PreflightResult prepare(PlacementRef p, long generation) {
            record("prepare", "@placement");
            return delegate.prepare(p, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
            record("materializeConfig", r.ref().value());
            return delegate.materializeConfig(r, g, c);
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica r, long g, RenderedConfig c,
                String expectedCurrentHash) {
            return delegate.rematerializeIfCurrent(r, g, c, expectedCurrentHash);
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            record("startOrUpdate", r.ref().value());
            restarted.add(r.ref().value());
            return delegate.startOrUpdate(r, generation);
        }

        @Override
        public LifecycleResult stop(TargetReplica r) {
            record("stop", r.ref().value());
            return delegate.stop(r);
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            record("status", r.ref().value());
            RuntimeStatus s = delegate.status(r);
            if (neverHealthyAfterRestart.contains(r.ref().value())
                    && restarted.contains(r.ref().value())) {
                return new RuntimeStatus(s.liveness(), HealthSignal.STARTING, s.observedHash(),
                        s.baseline(), s.runtimeRef());
            }
            return s;
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef p, long g, String intendedHash) {
            record("assessRollback", "@placement");
            return delegate.assessRollback(p, g, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef p, long g, String targetHash) {
            record("restore", "@placement");
            return delegate.restore(p, g, targetHash);
        }
    }
}
