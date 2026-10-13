package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.Liveness;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>Obligation (r): a {@code stop} executed while an apply is mid-flight.</b> Plan 004 recorded it
 * as N/A for want of a {@link RuntimeAdapter}; plan 005 first claimed it with a mechanism that
 * contradicted the design, and this class is what actually proves it.
 *
 * <p>Design §3.3 states it as: <i>"the stop executes immediately at the adapter — it does not queue
 * behind the apply and <b>the apply is not cancelled in place</b>; the running apply's next step
 * <b>observes the stopped replica through {@code status}</b> and its health gate, and terminates
 * through its normal failure paths"</i>, and §3.5 as <i>"(r) a {@code stop} <b>executed</b> while
 * an apply is mid-flight halts the apply with per-replica evidence, never a torn write"</i>.
 *
 * <p>Five of the six clauses are asserted; the sixth is partially proven and says so. An earlier
 * revision of this class claimed all six, which is the same over-claim `L22` names — the claim
 * was checked against the mechanism rather than against what the tests actually run.
 *
 * <ol>
 *   <li><b>the stop executes at the adapter</b> — `BE-D26`'s stop path runs a real
 *       {@code adapter.stop} per replica, and the test asserts the replica's liveness changed;
 *   <li><b>it does not queue behind the apply</b> — it is admitted and executed while the apply
 *       holds the scope, which {@code BE-D13}'s {@code operation <> 'stop'} apply fence permits.
 *       Stated honestly: this engine is single-threaded, so the stop runs <i>inside</i> the
 *       apply's own adapter call rather than beside it. What is proven is that neither admission
 *       nor execution waits for the apply to finish, not that two threads interleave;
 *   <li><b>the apply is not cancelled in place</b> — the executor reads no request row. The engine
 *       has no stop check at all; what it has is {@code status};
 *   <li><b>the next step observes the stopped replica</b> — {@code restartAndGate} sees
 *       {@code Liveness.STOPPED} before issuing the lifecycle verb;
 *   <li><b>terminates through its normal failure paths with per-replica evidence</b> —
 *       {@code CONFIG_DIVERGED}, §3.5's own outcome, with a {@code LIFECYCLE} attempt row carrying
 *       the classifier and the observed hash for the halted replica;
 *   <li><b>never a torn write</b> — <b>partially proven, and the limit is stated.</b> What is
 *       asserted is that every replica holds one whole generation after the halt, which follows
 *       from {@code SM-D15}'s stage-everywhere: the stop lands during the ROLLOUT, after every
 *       write has completed, so the thing interrupted is never a write. What is <b>not</b>
 *       exercised is a stop landing <i>inside</i> {@code materializeConfig} — that needs real
 *       concurrency against a real adapter, and atomicity there is {@code ConfigFileStore}'s
 *       durable atomic-replace, proven separately by {@code ConfigFileStoreTest}.
 * </ol>
 *
 * <p>An earlier revision polled {@code apply_request} for an admitted stop and halted on the row.
 * That cancels the apply in place on the strength of a row rather than an observation — the
 * opposite of clauses 3 and 4 — and it was removed. The difference is not cosmetic: the row-poll
 * halted on a stop that had been <i>admitted</i>, and this halts on a replica that has actually
 * <i>gone</i>, which is the only one of the two an operator can rely on.
 */
@Testcontainers(disabledWithoutDocker = true)
class StopDuringApplyIT {

    private static final RenderedConfig GENERATION_9 =
            TestHashers.rendered("generation: 9\n".getBytes(StandardCharsets.UTF_8));

    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;
    private InMemoryRuntimeAdapter adapter;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        instance = new InstanceId(UUID.randomUUID());
        placementId = new PlacementId(UUID.randomUUID());
        scope = InventoryFixtures.registered(new ScopeKey(tenant, instance, placementId));
        placement = new PlacementRef(tenant, instance, placementId);
        adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
    }

    private ApplyCommand rollingApply() {
        return new ApplyCommand(scope, Operation.APPLY, 9L, Strategy.ROLLING_HEALTH_GATED, null,
                "operator-1");
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    /**
     * The half that holds: {@code BE-D13}'s apply fence carries {@code operation <> 'stop'}, so an
     * emergency stop is admissible by the DATABASE while an apply the executor has actually
     * started is in flight — and a second APPLY on the same scope is not. Asserted with the
     * engine's admission bypassed, through the raw repository, so the asymmetry is the database's
     * and not a branch in {@code AdmissionService}.
     */
    @Test
    void theDatabaseAdmitsAStopBesideARunningApplyAndRefusesASecondApply() {
        AtomicReference<Integer> nonTerminalDuring = new AtomicReference<>();
        AfterFirstRestart adapterUnderTest = new AfterFirstRestart(adapter, () -> {
            Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.APPLY_INFLIGHT_FENCE,
                    () -> ApplyEngineHarness.requests()
                            .insert(ApplyEngineHarness.requestedApply(scope, 10)));
            ApplyEngineHarness.requests().insert(ApplyEngineHarness.requestedRow(scope,
                    Operation.STOP, null, null));
            nonTerminalDuring.set(ApplyEngineHarness.requests().nonTerminal(tenant).size());
        });

        ApplyEngineHarness.executor(adapterUnderTest, (p, g) -> GENERATION_9)
                .execute(rollingApply());

        assertEquals(2, nonTerminalDuring.get(),
                "the running apply and the raw stop coexist: two fences, two predicates");
        assertTrue(ApplyEngineHarness.requests().stopInFlight(tenant, instance, placementId),
                "and the stop really is in flight on this scope while the apply runs");
    }

    /**
     * Obligation (r), end to end, through the <b>health gate</b> — the mechanism §3.3 names first.
     * A rolling apply has just restarted replica 1 and is waiting for it to come back healthy when
     * an operator stops the whole instance. The replica never comes back; the gate spends its
     * deadline and reports, and the apply terminates through that normal failure path.
     *
     * <p>Measured, not assumed: the first draft of this test expected the halt at replica 2's
     * liveness check and got {@code gate.no.restart} instead, because the gate is watching replica
     * 1 at the moment the stop lands. §3.3 names both — <i>"observes the stopped replica through
     * {@code status} <b>and its health gate</b>"</i> — and the next test covers the other route.
     */
    @Test
    void aStopExecutedMidApplyHaltsTheApplyThroughItsHealthGate() {
        AtomicReference<ApplyId> stopId = new AtomicReference<>();
        AfterFirstRestart adapterUnderTest = new AfterFirstRestart(adapter, () -> {
            // The operator's stop, admitted AND EXECUTED while the apply holds the scope. A
            // separate executor over the same adapter: this is a concurrent actor, not a branch
            // of the apply.
            ApplyOutcome stopped = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_9)
                    .execute(new ApplyCommand(scope, Operation.STOP, null, null, null,
                            "operator-1"));
            ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, stopped,
                    "BE-D13's apply fence carries `operation <> 'stop'`: the emergency stop is "
                            + "admissible WHILE an apply runs, and that is the point of (r)");
            assertEquals(RequestState.STOPPED, landed.state());
            stopId.set(landed.applyId());
        });

        ApplyOutcome outcome = ApplyEngineHarness.executor(adapterUnderTest, (p, g) -> GENERATION_9)
                .execute(rollingApply());

        // (1) and (2): the stop really executed, on every replica, while the apply held the scope.
        assertNotNull(stopId.get());
        for (TargetReplica replica : adapter.replicas(placement)) {
            assertEquals(Liveness.STOPPED, adapter.status(replica).liveness());
        }

        // (5): the apply terminated through a normal failure path, with per-replica evidence.
        ApplyOutcome.Landed applyLanded = assertInstanceOf(ApplyOutcome.Landed.class, outcome);
        assertEquals(RequestState.CONFIG_DIVERGED, applyLanded.state(),
                "§3.5's own outcome for (r). APPLY_INDETERMINATE would say SM does not know what "
                        + "happened; SM knows exactly what happened — it looked");
        assertEquals(new ErrorClass("gate.no.restart"), applyLanded.errorClass(),
                "the gate watched a replica that never came back, and says so");

        // (4): it halted on an OBSERVATION, never on a request row. The engine holds no stop
        // check at all -- the gate's own classifier is the proof of which mechanism ran.
        assertEquals(1, ApplyEngineHarness.intents().forRequest(tenant, applyLanded.applyId())
                        .stream().filter(i -> i.step() == Step.LIFECYCLE).count(),
                "replica 1 alone was touched: the rollout stopped where it stood, and replica 2 "
                        + "never got a LIFECYCLE intent");
        StepIntentRow halted = ApplyEngineHarness.intents()
                .forRequest(tenant, applyLanded.applyId()).stream()
                .filter(i -> i.step() == Step.LIFECYCLE).findFirst().orElseThrow();
        AttemptRow evidence =
                ApplyEngineHarness.attempts().latest(tenant, halted.intentId()).orElseThrow();
        assertEquals(AttemptOutcome.FAILED, evidence.outcome(),
                "per-replica evidence, in a row: which replica, and why it did not pass");
        assertEquals(new ErrorClass("gate.no.restart"), evidence.errorClass());
        assertNotNull(evidence.preBaseline(),
                "carrying the baseline captured before the verb — what restart-observed compares "
                        + "against, and what makes 'it never came back' a measurement");

        // (6): never a torn write. Stage-everywhere ran to completion before any lifecycle verb,
        // so every replica holds one whole generation.
        for (TargetReplica replica : adapter.replicas(placement)) {
            assertEquals(GENERATION_9.hash(), adapter.status(replica).observedHash(),
                    "every replica holds generation 9 entire — the stop interrupted the ROLLOUT, "
                            + "never a write");
        }

        // Both requests reach their own terminal state, and the scope is free again.
        assertEquals(RequestState.STOPPED, stateOf(stopId.get()));
        assertEquals(RequestState.CONFIG_DIVERGED, stateOf(applyLanded.applyId()));
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty());
    }

    /**
     * §3.3's other route: the apply's next step observes, through {@code status}, a replica it has
     * not yet touched and finds it not running. It halts <b>before issuing the lifecycle verb</b>,
     * so an apply that happened to be passing never restarts a replica somebody just took down.
     *
     * <p>{@code CONFIG_DIVERGED}, not {@code APPLY_INDETERMINATE}: a stopped replica is a KNOWN
     * state. The engine distinguishes it from an unreadable baseline, which is the genuinely
     * unknown case and lands indeterminate.
     */
    @Test
    void aStopOfTheNextReplicaHaltsBeforeItsLifecycleVerbIsIssued() {
        AfterFirstRestart adapterUnderTest = new AfterFirstRestart(adapter,
                () -> adapter.stop(adapter.replicas(placement).get(1)));

        ApplyOutcome outcome = ApplyEngineHarness.executor(adapterUnderTest, (p, g) -> GENERATION_9)
                .execute(rollingApply());

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, outcome);
        assertEquals(RequestState.CONFIG_DIVERGED, landed.state());
        assertEquals(new ErrorClass("lifecycle.replica.not.running"), landed.errorClass());
        assertEquals(1, adapter.restarts(),
                "replica 2 is never restarted: a replica somebody took down is not restarted back "
                        + "up by an apply that happened to be passing");
        assertEquals(2, ApplyEngineHarness.intents().forRequest(tenant, landed.applyId()).stream()
                        .filter(i -> i.step() == Step.LIFECYCLE).count(),
                "replica 2's halt is journalled too — per-replica evidence covers the replica that "
                        + "was NOT acted on, which is the one an operator will ask about");
    }

    /**
     * §3.3's "<b>the apply is not cancelled in place</b>", asserted rather than read off the code.
     *
     * <p>A stop is <b>admitted</b> against the apply's own scope mid-rollout and deliberately
     * <b>not executed</b>: no replica is touched, only a row exists. The apply must finish. An
     * earlier revision polled {@code apply_request} for exactly that row and halted
     * {@code CONFIG_DIVERGED}; this test is what makes its removal a measured fact rather than a
     * claim about a deleted line, and it goes red if anyone reintroduces the poll.
     */
    @Test
    void anAdmittedButUnexecutedStopDoesNotCancelTheApply() {
        AfterFirstRestart adapterUnderTest = new AfterFirstRestart(adapter, () ->
                assertInstanceOf(AdmissionOutcome.Admitted.class, ApplyEngineHarness.admission()
                        .admit(new ApplyCommand(scope, Operation.STOP, null, null, null,
                                "operator-1"))));

        ApplyOutcome outcome = ApplyEngineHarness.executor(adapterUnderTest, (p, g) -> GENERATION_9)
                .execute(rollingApply());

        assertEquals(RequestState.APPLIED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state(),
                "the engine reads no request row: a stop that exists but has not run changes "
                        + "nothing an apply can observe, and §3.3 says it must not");
        assertEquals(2, adapter.restarts(), "both replicas were restarted");
    }

    /** Runs {@code onFirstRestart} immediately after replica 1's lifecycle verb, and only then. */
    private static final class AfterFirstRestart implements RuntimeAdapter {

        private final InMemoryRuntimeAdapter delegate;
        private final Runnable onFirstRestart;
        private boolean fired;

        AfterFirstRestart(InMemoryRuntimeAdapter delegate, Runnable onFirstRestart) {
            this.delegate = delegate;
            this.onFirstRestart = onFirstRestart;
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            LifecycleResult result = delegate.startOrUpdate(r, generation);
            if (!fired) {
                fired = true;
                onFirstRestart.run();
            }
            return result;
        }

        @Override
        public java.util.List<TargetReplica> replicas(PlacementRef p) {
            return delegate.replicas(p);
        }

        @Override
        public PreflightResult prepare(PlacementRef p, long generation) {
            return delegate.prepare(p, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
            return delegate.materializeConfig(r, g, c);
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica r, long g, RenderedConfig c,
                String expectedCurrentHash) {
            return delegate.rematerializeIfCurrent(r, g, c, expectedCurrentHash);
        }

        @Override
        public LifecycleResult stop(TargetReplica r) {
            return delegate.stop(r);
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            return delegate.status(r);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef p, long g, String intendedHash) {
            return delegate.assessRollback(p, g, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef p, long g, String targetHash) {
            return delegate.restore(p, g, targetHash);
        }
    }
}
