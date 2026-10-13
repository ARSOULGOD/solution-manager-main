package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.9 point 3 (OR-53, Q-G2 (a)): after a replica's restart - and, under {@code rolling-health-gated}, after
 * its gate passes - the engine reads the file back and verifies it against the {@code MATERIALIZE} intent's bound
 * keyed hash in a {@code VERIFY} intent. A match continues; a mismatch lands {@code CONFIG_DIVERGED} with classifier
 * {@code verify.hash.mismatch} and no rollback (rolling back a restarted replica is a second outage).
 *
 * <p>Over the real {@code sm_data} journal, like {@code RollingApplyIT}. (The plan names this class
 * {@code PostRestartVerifyTest} under {@code engine}; it needs the journal's PostgreSQL harness, which is
 * package-private to this test package, so it is an integration test here.)
 */
@Testcontainers(disabledWithoutDocker = true)
class PostRestartVerifyIT {

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

    private ApplyOutcome.Landed apply(RuntimeAdapter adapter, Strategy strategy) {
        return assertInstanceOf(ApplyOutcome.Landed.class,
                executor(adapter).execute(ApplyEngineHarness.rollingApply(scope, 7, strategy)));
    }

    private List<StepIntentRow> intentsFor(ApplyId id, Step step) {
        return ApplyEngineHarness.intents().forRequest(tenant, id).stream().filter(i -> i.step() == step).toList();
    }

    private AttemptRow latestAttempt(StepIntentRow intent) {
        return ApplyEngineHarness.attempts().latest(tenant, intent.intentId()).orElseThrow();
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    // ---- the match ----------------------------------------------------------------------------------------------

    @Test
    void afterTheRestartTheFileIsReadBackAndVerifiedWithTheKeyedHash() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        Tamper adapter = new Tamper(delegate);

        ApplyOutcome.Landed landed = apply(adapter, Strategy.ROLLING_HEALTH_GATED);

        assertEquals(RequestState.APPLIED, landed.state());
        List<StepIntentRow> verifies = intentsFor(landed.applyId(), Step.VERIFY);
        assertEquals(2, verifies.size(), "one VERIFY intent per replica");
        String bound = intentsFor(landed.applyId(), Step.MATERIALIZE).get(0).configHash();
        assertEquals(GENERATION_7.hash(), bound);
        for (StepIntentRow verify : verifies) {
            assertEquals(bound, verify.configHash(), "VERIFY is bound to the MATERIALIZE intent's hash");
            AttemptRow attempt = latestAttempt(verify);
            assertEquals(AttemptOutcome.SUCCEEDED, attempt.outcome());
            assertEquals(bound, attempt.observedHash(), "the attempt records the read-back hash as evidence");
        }
    }

    @Test
    void eachReplicaIsVerifiedBeforeTheNextOneIsTouched() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        Tamper adapter = new Tamper(delegate);
        adapter.onRestart = replica -> adapter.calls.add("restart:" + replica);
        apply(adapter, Strategy.ROLLING_HEALTH_GATED);

        // the VERIFY of replica 1 is the first status() call after replica 1's gate passed; the journal says when
        int restart1 = adapter.calls.indexOf("restart:replica-1");
        int restart2 = adapter.calls.indexOf("restart:replica-2");
        assertTrue(restart1 >= 0 && restart2 > restart1, adapter.calls.toString());
        assertTrue(adapter.verifyReads.contains("replica-1"));
        assertTrue(adapter.indexOfFirstVerifyRead("replica-1") < adapter.restartOrdinal("replica-2"),
                "replica 1's read-back precedes replica 2's restart: " + adapter.calls);
    }

    @Test
    void theUngatedRestartingStrategiesVerifyToo() {
        for (Strategy strategy : List.of(Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL)) {
            UUID t = UUID.randomUUID();
            TenantId otherTenant = new TenantId(t);
            InstanceId otherInstance = new InstanceId(UUID.randomUUID());
            PlacementId otherPlacement = new PlacementId(UUID.randomUUID());
            ScopeKey otherScope = InventoryFixtures.registered(new ScopeKey(otherTenant, otherInstance, otherPlacement));
            PlacementRef ref = new PlacementRef(otherTenant, otherInstance, otherPlacement);
            Tamper adapter = new Tamper(new InMemoryRuntimeAdapter(ref, true, "replica-1"), otherTenant);

            ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, executorFor(adapter)
                    .execute(ApplyEngineHarness.rollingApply(otherScope, 7, strategy)));

            assertEquals(RequestState.APPLIED, landed.state(), strategy.toString());
            assertEquals(1, ApplyEngineHarness.intents().forRequest(otherTenant, landed.applyId()).stream()
                    .filter(i -> i.step() == Step.VERIFY).count(), strategy + " verifies the file after the restart");
        }
    }

    private ApplyExecutor executorFor(RuntimeAdapter adapter) {
        return executor(adapter);
    }

    @Test
    void manualJournalsNoVerifyBecauseNothingRestarted() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1");

        ApplyOutcome.Landed landed = apply(new Tamper(delegate), Strategy.MANUAL);

        assertEquals(RequestState.STAGED, landed.state());
        assertTrue(intentsFor(landed.applyId(), Step.VERIFY).isEmpty());
    }

    // ---- the mismatch --------------------------------------------------------------------------------------------

    @Test
    void aReadBackMismatchAfterTheRestartLandsConfigDivergedWithNoRollback() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        Tamper adapter = new Tamper(delegate);
        adapter.alterAfterRestart.add("replica-1");

        ApplyOutcome.Landed landed = apply(adapter, Strategy.ROLLING_HEALTH_GATED);

        assertEquals(RequestState.CONFIG_DIVERGED, landed.state());
        assertEquals("verify.hash.mismatch", landed.errorClass().token());
        ApplyId id = landed.applyId();
        assertEquals(RequestState.CONFIG_DIVERGED, stateOf(id));

        StepIntentRow verify = intentsFor(id, Step.VERIFY).get(0);
        AttemptRow attempt = latestAttempt(verify);
        assertEquals(AttemptOutcome.FAILED, attempt.outcome());
        assertEquals("verify.hash.mismatch", attempt.errorClass().token());
        assertEquals(Tamper.ALTERED, attempt.observedHash(), "the evidence is what the file held");

        // Q-G2 (a): no automatic rollback over a restarted replica - and replica 2 was never touched
        assertTrue(intentsFor(id, Step.ROLLBACK).isEmpty() && intentsFor(id, Step.ROLLBACK_ASSESS).isEmpty());
        assertEquals(0, adapter.assessCalls);
        assertEquals(0, adapter.restoreCalls);
        assertFalse(adapter.restarted.contains("replica-2"), "the roll stops at the first bad replica");
    }

    @Test
    void anAbsentFileAfterTheRestartIsAMismatchToo() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        Tamper adapter = new Tamper(delegate);
        adapter.absentAfterRestart.add("replica-1");

        ApplyOutcome.Landed landed = apply(adapter, Strategy.ROLLING_HEALTH_GATED);

        assertEquals(RequestState.CONFIG_DIVERGED, landed.state());
        assertEquals("verify.hash.mismatch", landed.errorClass().token());
        assertEquals(AttemptOutcome.FAILED, latestAttempt(intentsFor(landed.applyId(), Step.VERIFY).get(0)).outcome());
    }

    @Test
    void aReadBackThatCannotBeReadAfterTheRestartIsIndeterminateNotDiverged() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        Tamper adapter = new Tamper(delegate);
        adapter.unreadableOnVerify = true;

        ApplyOutcome.Landed landed = apply(adapter, Strategy.ROLLING_NO_VALIDATION);

        assertEquals(RequestState.APPLY_INDETERMINATE, landed.state(),
                "SM restarted the replica and then lost sight of it: that is what the state is for");
        assertEquals("ssh.unreachable", landed.errorClass().token());
        AttemptRow attempt = latestAttempt(intentsFor(landed.applyId(), Step.VERIFY).get(0));
        assertEquals(AttemptOutcome.FAILED, attempt.outcome());
    }

    // ---- resume --------------------------------------------------------------------------------------------------

    /**
     * A forward {@code VERIFY} intent is not BE-D22's exhaustion tail: resume used to route any journalled
     * {@code VERIFY} there, which would assess a rollback over a replica the apply had finished. Routing is by
     * {@code ROLLBACK_ASSESS}/{@code ROLLBACK} - which every exhaustion VERIFY follows - and not by VERIFY alone.
     */
    @Test
    void aCrashInsideTheForwardVerifyIsNotRoutedToTheRollbackTail() {
        InMemoryRuntimeAdapter delegate = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        Tamper crashing = new Tamper(delegate);
        crashing.crashOnVerify = true;
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> executor(crashing).execute(ApplyEngineHarness.rollingApply(scope, 7,
                        Strategy.ROLLING_NO_VALIDATION)));
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        assertEquals(1, intentsFor(id, Step.VERIFY).size(), "precondition: the crash left a forward VERIFY intent");

        Tamper healthy = new Tamper(delegate);
        List<ApplyOutcome> resumed = executor(healthy).resume(tenant);

        assertEquals(RequestState.APPLIED, assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        assertTrue(intentsFor(id, Step.ROLLBACK_ASSESS).isEmpty() && intentsFor(id, Step.ROLLBACK).isEmpty(),
                "no rollback tail was entered");
        assertEquals(0, healthy.assessCalls);
    }

    // ---- the adapter wrapper -------------------------------------------------------------------------------------

    /**
     * An in-memory adapter that can lie about what a replica reads back once it has been restarted, and that
     * records the order of calls. Everything else is the in-memory adapter's.
     */
    private final class Tamper implements RuntimeAdapter {

        static final String ALTERED = "hmacsha256v1:" + "0".repeat(64);

        final InMemoryRuntimeAdapter delegate;
        final List<String> calls = new ArrayList<>();
        final Set<String> restarted = new HashSet<>();
        final Set<String> alterAfterRestart = new HashSet<>();
        final Set<String> absentAfterRestart = new HashSet<>();
        final List<String> verifyReads = new ArrayList<>();
        java.util.function.Consumer<String> onRestart = r -> { };
        boolean unreadableOnVerify;
        boolean crashOnVerify;
        int assessCalls;
        int restoreCalls;

        final TenantId owner;

        Tamper(InMemoryRuntimeAdapter delegate) {
            this(delegate, tenant);
        }

        Tamper(InMemoryRuntimeAdapter delegate, TenantId owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        int restartOrdinal(String replica) {
            return calls.indexOf("restart:" + replica);
        }

        int indexOfFirstVerifyRead(String replica) {
            return calls.indexOf("verify:" + replica);
        }

        /** True while the VERIFY intent exists for this replica and its attempt is still open. */
        private boolean verifying(TargetReplica r) {
            return ApplyEngineHarness.intents().forRequest(owner, openApply()).stream()
                    .anyMatch(i -> i.step() == Step.VERIFY && i.replica().equals(r.ref())
                            && ApplyEngineHarness.attempts().latest(owner, i.intentId())
                                    .map(a -> a.outcome() == null).orElse(false));
        }

        private ApplyId openApply() {
            return ApplyEngineHarness.requests().nonTerminal(owner).get(0).applyId();
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            boolean verifyingNow = !ApplyEngineHarness.requests().nonTerminal(owner).isEmpty() && verifying(r);
            if (verifyingNow) {
                verifyReads.add(r.ref().value());
                calls.add("verify:" + r.ref().value());
                if (crashOnVerify) {
                    throw new RuntimeException("simulated process death inside the forward VERIFY");
                }
                if (unreadableOnVerify) {
                    throw new RuntimeAdapterException(new com.solace.sm.dp.apply.model.ErrorClass("ssh.unreachable"));
                }
            }
            RuntimeStatus s = delegate.status(r);
            if (restarted.contains(r.ref().value()) && alterAfterRestart.contains(r.ref().value())) {
                return new RuntimeStatus(s.liveness(), s.health(), ALTERED, s.baseline(), s.runtimeRef());
            }
            if (restarted.contains(r.ref().value()) && absentAfterRestart.contains(r.ref().value())) {
                return new RuntimeStatus(s.liveness(), s.health(), null, s.baseline(), s.runtimeRef());
            }
            return s;
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            restarted.add(r.ref().value());
            onRestart.accept(r.ref().value());
            return delegate.startOrUpdate(r, generation);
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
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
        public RollbackAssessment assessRollback(PlacementRef p, long g, String intendedHash) {
            assessCalls++;
            return delegate.assessRollback(p, g, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef p, long g, String targetHash) {
            restoreCalls++;
            return delegate.restore(p, g, targetHash);
        }
    }
}
