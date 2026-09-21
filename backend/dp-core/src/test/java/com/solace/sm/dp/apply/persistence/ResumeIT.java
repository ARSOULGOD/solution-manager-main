package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Design §2.2 rule 3's wiring, on the cells and crash windows that cannot be faked — the table
 * itself is walked exhaustively and cheaply by {@code ResumePlannerTest}.
 *
 * <p><b>How a crash is simulated.</b> Not by stubbing resume's inputs: an adapter throws a plain
 * {@link RuntimeException} rather than a {@code RuntimeAdapterException}, which the step loop does
 * not catch, so it propagates out of {@code execute} exactly as a dying JVM would — leaving the
 * intent committed, the attempt OPEN and the request {@code RUNNING}. The journal state resume then
 * reads is the one a real crash leaves, not one a test built to suit itself.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResumeIT {

    private static final RenderedConfig GENERATION_7 =
            RenderedConfig.of("generation: 7\n".getBytes(StandardCharsets.UTF_8));
    private static final RenderedConfig GENERATION_8 =
            RenderedConfig.of("generation: 8\n".getBytes(StandardCharsets.UTF_8));
    private static final String A_WRITE_SM_DID_NOT_MAKE = "sha256:" + "e".repeat(64);

    private TenantId tenant;
    private ScopeKey scope;
    private PlacementRef placement;
    private InMemoryRuntimeAdapter adapter;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        PlacementId placementId = new PlacementId(UUID.randomUUID());
        scope = new ScopeKey(tenant, instance, placementId);
        placement = new PlacementRef(tenant, instance, placementId);
        adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
    }

    private ApplyCommand manualApply(long generation) {
        return new ApplyCommand(scope, Operation.APPLY, generation, Strategy.MANUAL, null,
                "operator-1");
    }

    private ApplyExecutor executor(RuntimeAdapter a, RenderedConfig config) {
        return ApplyEngineHarness.executor(a, (p, g) -> config);
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    private List<StepIntentRow> intentsFor(ApplyId id, Step step) {
        return ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                .filter(i -> i.step() == step).toList();
    }

    // ---- cell 1: an intent with no attempt row ----------------------------------------------------

    /**
     * The crash window rule 0 exists to make decidable: between {@code ensureIntent} and
     * {@code attempts.open}. The intent is durable, no attempt row exists, so nothing was issued —
     * and re-running must reuse the SAME intent, which is only possible because the idempotency key
     * is a pure function of {@code (applyId, step, replica)}. A non-deterministic key surfaces here
     * as {@code IdempotencyKeyReused}.
     */
    @Test
    void anIntentWithNoAttemptRowIsRerunAndProducesTheSameIdempotencyKey() {
        AdmissionOutcome admitted =
                ApplyEngineHarness.admission().admit(manualApply(7));
        ApplyId id = ((AdmissionOutcome.Admitted) admitted).applyId();
        ApplyEngineHarness.outcomes().start(tenant, id);

        TargetReplica replica1 = adapter.replicas().get(0);
        StepIntentRow orphan = ApplyEngineHarness.stepIntents().ensureIntent(tenant, id,
                Step.MATERIALIZE, replica1.ref(),
                ApplyExecutor.idempotencyKey(id, Step.MATERIALIZE, replica1.ref()),
                GENERATION_7.hash());

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_7).resume(tenant);

        assertEquals(1, resumed.size());
        assertEquals(RequestState.STAGED,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        List<StepIntentRow> materialize = intentsFor(id, Step.MATERIALIZE);
        assertEquals(2, materialize.size(), "one MATERIALIZE intent per replica, and no more");
        assertTrue(materialize.stream().anyMatch(i -> i.intentId().equals(orphan.intentId())),
                "the orphaned intent must be REUSED, not shadowed by a second one carrying the "
                        + "same logical step");
    }

    // ---- cell 2: an unexpected hash under an open MATERIALIZE attempt --------------------------------

    @Test
    void anUnexpectedHashOnResumeIsIndeterminateAndNothingIsOverwritten() {
        CrashingAdapter crashing = new CrashingAdapter(adapter, CrashingAdapter.Where.MATERIALIZE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_7).execute(manualApply(7)),
                "the simulated crash must escape the step loop, or the journal state under test "
                        + "is not the one a crash leaves");

        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        TargetReplica replica1 = adapter.replicas().get(0);
        adapter.competingWrite(replica1, A_WRITE_SM_DID_NOT_MAKE);

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_7).resume(tenant);

        assertEquals(RequestState.APPLY_INDETERMINATE,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(id));
        assertEquals(A_WRITE_SM_DID_NOT_MAKE, adapter.status(replica1).observedHash(),
                "B/D12: never silently auto-revert a human. Asserting the STATE without asserting "
                        + "the bytes would let a revert pass this test");
    }

    // ---- cells 3 and 4: a crash inside BE-D22's rollback window ----------------------------------------

    /**
     * {@code BE-D22}'s proof. The {@code ROLLBACK} intent's own {@code config_hash} — committed
     * before the destructive call — is read back against each replica, and the rollback is finished
     * <b>without a second assessment</b>. Re-assessing after a partial restore would read the
     * restored file as the current state and conclude {@code NothingToRestore}, turning a completed
     * rollback into {@code FAILED_NO_EFFECT}.
     */
    @Test
    void aCrashBetweenTheRollbackIntentAndTheRestoreIsResolvedByTheIntentsOwnHash() {
        String priorHash = stageGenerationSeven();
        adapter.failMaterializeOn("replica-2");

        CrashingAdapter crashing = new CrashingAdapter(adapter, CrashingAdapter.Where.RESTORE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_8).execute(manualApply(8)));

        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        assertEquals(priorHash, intentsFor(id, Step.ROLLBACK).get(0).configHash(),
                "the ROLLBACK intent carries the restore TARGET, written before the destructive "
                        + "call — that single row is what makes this crash decidable");
        assertEquals(1, adapter.assessCalls());

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_8).resume(tenant);

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0));
        assertEquals(RequestState.FAILED_ROLLED_BACK, landed.state());
        assertEquals(1, adapter.assessCalls(),
                "resume must NOT assess again: an assessment taken after a partial restore reads "
                        + "the restored file as the current state and concludes NothingToRestore");
        for (TargetReplica replica : adapter.replicas()) {
            assertEquals(priorHash, adapter.status(replica).observedHash(),
                    "every replica is back on the target the intent named");
        }
        assertEquals(2, intentsFor(id, Step.VERIFY).size(),
                "one VERIFY per replica — the durable per-replica record of the restore");
    }

    /** Correction B's resume proof: a third, unknown value on one replica stops everything. */
    @Test
    void aCrashAfterTheRollbackIntentPlusACompetingWriteIsIndeterminate() {
        stageGenerationSeven();
        adapter.failMaterializeOn("replica-2");

        CrashingAdapter crashing = new CrashingAdapter(adapter, CrashingAdapter.Where.RESTORE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_8).execute(manualApply(8)));

        TargetReplica replica2 = adapter.replicas().get(1);
        adapter.competingWrite(replica2, A_WRITE_SM_DID_NOT_MAKE);

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_8).resume(tenant);

        assertEquals(RequestState.APPLY_INDETERMINATE,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        assertEquals(A_WRITE_SM_DID_NOT_MAKE, adapter.status(replica2).observedHash(),
                "re-issuing the restore would erase a write this apply did not make, which B/D12 "
                        + "forbids. Assert the bytes, not only the state");
        assertEquals(GENERATION_8.hash(), adapter.status(adapter.replicas().get(0)).observedHash(),
                "and the replica that DID take generation 8 is left exactly as it was");
    }

    /** The open attempt a crash left behind is settled, never left to block the next try. */
    @Test
    void resumeClosesTheAttemptTheCrashLeftOpen() {
        CrashingAdapter crashing = new CrashingAdapter(adapter, CrashingAdapter.Where.MATERIALIZE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_7).execute(manualApply(7)));
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        StepIntentRow materialize = intentsFor(id, Step.MATERIALIZE).get(0);
        assertEquals(null, openAttemptOf(materialize).outcome(), "precondition: it is open");

        executor(adapter, GENERATION_7).resume(tenant);

        assertEquals(AttemptOutcome.SUCCEEDED, openAttemptOf(materialize).outcome(),
                "the bytes ARE at the path and they are the intent's own, so the interrupted "
                        + "attempt is closed as what it turned out to be");
    }

    /** Generation 7, applied for real, so generation 8 has a rollback target that is not absence. */
    private String stageGenerationSeven() {
        ApplyOutcome staged = executor(adapter, GENERATION_7).execute(manualApply(7));
        assertEquals(RequestState.STAGED,
                assertInstanceOf(ApplyOutcome.Landed.class, staged).state());
        return GENERATION_7.hash();
    }

    // ---- the three crash windows inside BE-D22's tail that routing-by-frontier got wrong --------

    /**
     * <b>Regression, review finding 2.</b> A crash inside the {@code VERIFY} sweep leaves a
     * {@code VERIFY} intent as the newest one. Routing on the frontier alone sent that to
     * {@code RERUN} and back into the step loop, which re-materialized the generation the apply had
     * already given up on — <b>over a restore that had just completed</b> — and assessed a second
     * time. Resume routes by PHASE now: any journalled {@code ROLLBACK_ASSESS}/{@code ROLLBACK}/
     * {@code VERIFY} means the tail finishes on its own path.
     */
    @Test
    void aCrashInsideTheVerifySweepFinishesTheRollbackAndNeverRematerializes() {
        String priorHash = stageGenerationSeven();
        adapter.failMaterializeOn("replica-2");

        CrashingAdapter crashing =
                new CrashingAdapter(adapter, CrashingAdapter.Where.STATUS_AFTER_RESTORE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_8).execute(manualApply(8)));

        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        assertFalse(intentsFor(id, Step.ROLLBACK).isEmpty(), "precondition: the restore ran");
        int materializesBefore = adapter.materializeCalls();

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_8).resume(tenant);

        assertEquals(RequestState.FAILED_ROLLED_BACK,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        assertEquals(materializesBefore, adapter.materializeCalls(),
                "resume must not write generation 8 again — the rollback had already put every "
                        + "replica back on the target, and re-materializing would undo it");
        assertEquals(1, adapter.assessCalls(), "and it must not assess a second time");
        for (TargetReplica replica : adapter.replicas()) {
            assertEquals(priorHash, adapter.status(replica).observedHash());
        }
    }

    /**
     * <b>Regression, review finding 1, scenario B.</b> A crash after the {@code ROLLBACK} attempt
     * closed but before the first {@code VERIFY} intent. The frontier is then a {@code ROLLBACK}
     * with <b>no open attempt</b>, which the decision table answers {@code RERUN} — and the tail
     * has no "from the top" to return to, so the old code threw and left the request
     * {@code RUNNING} forever with no operator exit.
     */
    @Test
    void aCrashAfterTheRollbackAttemptClosedStillFinishesTheRollback() {
        String priorHash = stageGenerationSeven();
        adapter.failMaterializeOn("replica-2");

        CrashingAdapter crashing =
                new CrashingAdapter(adapter, CrashingAdapter.Where.STATUS_AFTER_RESTORE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_8).execute(manualApply(8)));
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();

        // The crash landed inside VERIFY; wind the journal back one step to the window under test
        // by removing the VERIFY intents the sweep had begun. There is no adapter call between
        // closing the ROLLBACK attempt and the first VERIFY ensureIntent, so no fault injection
        // can reach this window — the journal is edited directly, and only the journal.
        //
        // Through the SUPERUSER connection, because sm_dp_runtime cannot DELETE an intent: §3.1
        // makes apply_step_intent append-only by privilege, and the first attempt at this surgery
        // was refused by that grant. The refusal is the design working; the surgery goes around it
        // rather than through it, so nothing here weakens what the runtime role may do.
        NamedParameterJdbcTemplate asSuperuser =
                new NamedParameterJdbcTemplate(SmDataPostgres.superuser());
        asSuperuser.update(
                "DELETE FROM sm_data.apply_attempt WHERE tenant_id = :t AND intent_id IN "
                        + "(SELECT intent_id FROM sm_data.apply_step_intent WHERE tenant_id = :t "
                        + "AND apply_id = :a AND step = 'VERIFY')",
                java.util.Map.of("t", tenant.value(), "a", id.value()));
        asSuperuser.update(
                "DELETE FROM sm_data.apply_step_intent WHERE tenant_id = :t AND apply_id = :a "
                        + "AND step = 'VERIFY'",
                java.util.Map.of("t", tenant.value(), "a", id.value()));
        assertTrue(intentsFor(id, Step.VERIFY).isEmpty(), "precondition: no VERIFY intent yet");
        assertEquals(AttemptOutcome.SUCCEEDED,
                openAttemptOf(intentsFor(id, Step.ROLLBACK).get(0)).outcome(),
                "precondition: the ROLLBACK attempt is CLOSED");

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_8).resume(tenant);

        assertEquals(RequestState.FAILED_ROLLED_BACK,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        assertEquals(2, intentsFor(id, Step.VERIFY).size(), "the tail completed its VERIFY sweep");
        assertEquals(1, adapter.assessCalls());
        for (TargetReplica replica : adapter.replicas()) {
            assertEquals(priorHash, adapter.status(replica).observedHash());
        }
    }

    /**
     * <b>Regression, review finding 1, scenario A.</b> A crash between the {@code ROLLBACK}
     * intent's commit and its attempt's — rule 0's own window. No attempt row exists, so nothing
     * was issued and the replicas still hold the pre-restore mixture; the tail must re-issue.
     */
    @Test
    void aCrashBetweenTheRollbackIntentAndItsAttemptReissuesTheRestore() {
        String priorHash = stageGenerationSeven();
        adapter.failMaterializeOn("replica-2");

        CrashingAdapter crashing = new CrashingAdapter(adapter, CrashingAdapter.Where.RESTORE);
        assertThrows(RuntimeException.class,
                () -> executor(crashing, GENERATION_8).execute(manualApply(8)));
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();

        // Same reason as above: rule 0's window has no adapter call in it, so the attempt row the
        // crash left open is removed directly to produce the state a crash one statement earlier
        // would have left.
        ApplyEngineHarness.jdbc().update(
                "DELETE FROM sm_data.apply_attempt WHERE tenant_id = :t AND intent_id = :i",
                java.util.Map.of("t", tenant.value(),
                        "i", intentsFor(id, Step.ROLLBACK).get(0).intentId()));   // attempts are deletable; intents are not

        List<ApplyOutcome> resumed = executor(adapter, GENERATION_8).resume(tenant);

        assertEquals(RequestState.FAILED_ROLLED_BACK,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state(),
                "an intent with no attempt provably issued nothing, so the restore is re-issued — "
                        + "not treated as a step with nowhere to return to");
        for (TargetReplica replica : adapter.replicas()) {
            assertEquals(priorHash, adapter.status(replica).observedHash());
        }
        assertEquals(1, adapter.assessCalls());
    }

    /**
     * <b>Regression, review findings 3 and 4.</b> A sweep that stops at the first request it
     * cannot recover recovers nothing: the same request is met again next time, so one poisoned
     * row would block every other in-flight apply of the tenant permanently.
     */
    @Test
    void oneUnrecoverableRequestDoesNotStopTheSweepAndIsLandedIndeterminate() {
        // Request A, on a placement whose pre-flight will die unrecoverably.
        PlacementId poisonedId = new PlacementId(UUID.randomUUID());
        ScopeKey poisonedScope = new ScopeKey(tenant, scope.instance(), poisonedId);
        ApplyId poisoned = ((AdmissionOutcome.Admitted) ApplyEngineHarness.admission()
                .admit(new ApplyCommand(poisonedScope, Operation.APPLY, 3L, Strategy.MANUAL, null,
                        "operator-1"))).applyId();
        ApplyEngineHarness.outcomes().start(tenant, poisoned);

        // Request B, ordinary, on this test's own placement.
        ApplyId healthy = ((AdmissionOutcome.Admitted) ApplyEngineHarness.admission()
                .admit(manualApply(7))).applyId();
        ApplyEngineHarness.outcomes().start(tenant, healthy);

        List<ApplyOutcome> resumed = executor(new PoisonedPlacement(adapter, poisonedId),
                GENERATION_7).resume(tenant);

        assertEquals(2, resumed.size(), "both requests are reported");
        assertEquals(RequestState.APPLY_INDETERMINATE, stateOf(poisoned),
                "the unrecoverable one lands where an operator can resolve it, not RUNNING");
        assertEquals(RequestState.STAGED, stateOf(healthy),
                "and the recoverable one is resumed rather than skipped");
    }

    private AttemptRow openAttemptOf(StepIntentRow intent) {
        return ApplyEngineHarness.attempts().latest(tenant, intent.intentId()).orElseThrow();
    }

    /**
     * Dies where a real DP would: by throwing something the step loop does not catch. A
     * {@code RuntimeAdapterException} is a classified failure the engine handles; a plain
     * {@link RuntimeException} is the JVM going away.
     */
    private static final class CrashingAdapter implements RuntimeAdapter {

        enum Where {
            MATERIALIZE,
            RESTORE,
            /** Let the restore land, then die inside the VERIFY sweep that follows it. */
            STATUS_AFTER_RESTORE
        }

        private final InMemoryRuntimeAdapter delegate;
        private final Where where;
        private boolean restored;

        CrashingAdapter(InMemoryRuntimeAdapter delegate, Where where) {
            this.delegate = delegate;
            this.where = where;
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
            MaterializeResult result = delegate.materializeConfig(r, g, c);
            if (where == Where.MATERIALIZE) {
                throw new RuntimeException("simulated process death after the bytes landed");
            }
            return result;
        }

        @Override
        public RestoreResult restore(PlacementRef p, long g, String targetHash) {
            if (where == Where.RESTORE) {
                throw new RuntimeException("simulated process death before the restore ran");
            }
            RestoreResult result = delegate.restore(p, g, targetHash);
            restored = true;
            return result;
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
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            return delegate.startOrUpdate(r, generation);
        }

        @Override
        public LifecycleResult stop(TargetReplica r) {
            return delegate.stop(r);
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            if (where == Where.STATUS_AFTER_RESTORE && restored) {
                throw new RuntimeException("simulated process death inside the VERIFY sweep");
            }
            return delegate.status(r);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef p, long g, String intendedHash) {
            return delegate.assessRollback(p, g, intendedHash);
        }
    }

    /** An adapter that dies on pre-flight for ONE placement and behaves for every other. */
    private static final class PoisonedPlacement implements RuntimeAdapter {

        private final InMemoryRuntimeAdapter delegate;
        private final PlacementId poisoned;

        PoisonedPlacement(InMemoryRuntimeAdapter delegate, PlacementId poisoned) {
            this.delegate = delegate;
            this.poisoned = poisoned;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            return delegate.replicas(p);
        }

        @Override
        public PreflightResult prepare(PlacementRef p, long generation) {
            if (p.placement().equals(poisoned)) {
                throw new RuntimeException("simulated unrecoverable failure on one placement");
            }
            return delegate.prepare(p, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
            return delegate.materializeConfig(r, g, c);
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            return delegate.startOrUpdate(r, generation);
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
