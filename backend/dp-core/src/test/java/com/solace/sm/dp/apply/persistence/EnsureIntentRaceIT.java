package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 004's carried-forward watch item, settled by measurement — plan 005 Task 8 step 6.
 *
 * <p>{@code StepIntentService.ensureIntent} reads {@code apply_request.state}, finds it non-terminal,
 * and inserts, in its own {@code REQUIRES_NEW} transaction at READ COMMITTED, taking <b>no</b>
 * {@code apply_scope} row lock — while {@code OutcomeService.land} takes that lock for every state
 * change. Plan 004 recorded the window as real but unreachable: no step loop existed. The step loop
 * exists now, so the question is decided here rather than preferred.
 *
 * <p><b>How the window is held open.</b> Nothing is hooked into production code. The intent
 * repository is built over a {@link NamedParameterJdbcTemplate} that pauses the one
 * {@code INSERT INTO sm_data.apply_step_intent} statement — which is exactly the second half of the
 * check-then-act pair, the read having already happened. The pausing template wraps the <b>same</b>
 * {@link DataSource} instance the transaction manager uses, or the insert would run outside the
 * transaction and the test would prove nothing about it.
 */
@Testcontainers(disabledWithoutDocker = true)
class EnsureIntentRaceIT {

    private TenantId tenant;
    private ScopeKey scope;
    private ApplyId applyId;
    private OutcomeService outcomes;

    /** Pauses the intent INSERT once, holding {@code ensureIntent} inside its check-then-act window. */
    private static final class PausingIntentInsert extends NamedParameterJdbcTemplate {

        private final CountDownLatch reachedTheWindow = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(true);

        PausingIntentInsert(DataSource ds) {
            super(ds);
        }

        @Override
        public int update(String sql, SqlParameterSource params) {
            if (sql.contains("INSERT INTO sm_data.apply_step_intent")
                    && armed.compareAndSet(true, false)) {
                reachedTheWindow.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "the window was never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.update(sql, params);
        }
    }

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        scope = new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
        outcomes = ApplyEngineHarness.outcomes();

        AdmissionOutcome admitted = ApplyEngineHarness.admission().admit(new ApplyCommand(scope,
                Operation.APPLY, 11L, Strategy.MANUAL, null, "operator-1"));
        applyId = ((AdmissionOutcome.Admitted) admitted).applyId();
        outcomes.start(tenant, applyId);
    }

    /**
     * The measurement plan 005 promised. The expected observation — and the one recorded — is that
     * the intent <b>lands on a terminal request</b>: the FK {@code (tenant_id, apply_id) →
     * apply_request} does not fence on state, so obligation (q) holds sequentially and nothing in
     * the database re-checks it once {@code ensureIntent} has read.
     *
     * <p>Had the insert been <i>refused</i> instead, obligation (q) would be stronger than plan 004
     * recorded and that would be a finding, not a silent upgrade. It is not refused.
     */
    @Test
    void anIntentInsertedInsideTheWindowLandsOnAnAlreadyTerminalRequest() throws Exception {
        PausingIntentInsert pausing =
                new PausingIntentInsert(SmDataPostgres.runtimeDataSource());
        StepIntentService racing = new StepIntentService(ApplyEngineHarness.transactions(),
                ApplyEngineHarness.requests(), new StepIntentRepository(pausing), Clock.systemUTC());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<StepIntentRow> inserting = pool.submit(() -> racing.ensureIntent(tenant, applyId,
                    Step.PREFLIGHT, ReplicaRef.PLACEMENT, "race:" + applyId.value(), null));

            assertTrue(pausing.reachedTheWindow.await(30, TimeUnit.SECONDS),
                    "ensureIntent never reached its INSERT — the window was not entered");

            // The concurrent lander. It takes the apply_scope row lock; ensureIntent holds none, so
            // nothing serializes the two.
            outcomes.land(tenant, applyId, RequestState.SUPERSEDED);
            assertEquals(RequestState.SUPERSEDED, stateOf(applyId));

            pausing.release.countDown();
            StepIntentRow landed = inserting.get(30, TimeUnit.SECONDS);

            assertEquals(Step.PREFLIGHT, landed.step());
            List<StepIntentRow> persisted =
                    ApplyEngineHarness.intents().forRequest(tenant, applyId);
            assertEquals(1, persisted.size(),
                    "MEASURED: the intent committed against a SUPERSEDED request. Obligation (q) is "
                            + "engine-enforced and sequential-only; no database artifact re-checks "
                            + "the state between ensureIntent's read and its insert");
            assertEquals(RequestState.SUPERSEDED, stateOf(applyId),
                    "the terminal state is immutable; the late intent does not disturb it");
        } finally {
            pausing.release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * The sequential half, so the measurement above is read as "the window is real", not as
     * "obligation (q) does not work".
     */
    @Test
    void sequentiallyAnIntentOnATerminalRequestIsStillRefused() {
        outcomes.land(tenant, applyId, RequestState.SUPERSEDED);

        assertThrows(StepIntentService.TerminalRequest.class,
                () -> ApplyEngineHarness.stepIntents().ensureIntent(tenant, applyId, Step.PREFLIGHT,
                        ReplicaRef.PLACEMENT, "sequential:" + applyId.value(), null));
        assertTrue(ApplyEngineHarness.intents().forRequest(tenant, applyId).isEmpty());
    }

    /**
     * The engine's half of the resolution, and the reason no lock was added: the executor re-reads
     * the request state before entering {@code BE-D22}'s exhaustion journal, and a request that has
     * gone terminal underneath it is reported as it stands rather than rolled back.
     *
     * <p>Without that re-read the first {@code ensureIntent} of the journal throws
     * {@code TerminalRequest} out of {@code execute}, which is the mutation this test detects.
     */
    @Test
    void aRequestThatWentTerminalMidFlightAbortsTheExhaustionJournalRatherThanRollingBack() {
        ScopeKey own = new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
        PlacementRef placement =
                new PlacementRef(own.tenant(), own.instance(), own.placement());
        InMemoryRuntimeAdapter delegate =
                new InMemoryRuntimeAdapter(placement, true, "replica-1");
        SupersedeOnLastMaterialize adapter =
                new SupersedeOnLastMaterialize(delegate, own, ApplyEngineHarness.maxAttempts());

        ApplyOutcome outcome = ApplyEngineHarness.executor(adapter,
                        (p, g) -> RenderedConfig.of("generation: 5\n".getBytes(StandardCharsets.UTF_8)))
                .execute(new ApplyCommand(own, Operation.APPLY, 5L, Strategy.MANUAL, null,
                        "operator-1"));

        ApplyId id = assertInstanceOf(ApplyOutcome.Landed.class, outcome).applyId();
        assertEquals(RequestState.SUPERSEDED, ((ApplyOutcome.Landed) outcome).state(),
                "the executor reports the state the database holds, and lands nothing of its own");
        assertEquals(0, delegate.assessCalls(),
                "the exhaustion journal must not run against a request that is no longer ours");
        assertTrue(ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                        .noneMatch(i -> i.step() == Step.ROLLBACK_ASSESS
                                || i.step() == Step.ROLLBACK),
                "no rollback intent may be written after the request went terminal");
    }

    /**
     * A {@link RuntimeAdapter} that lands the in-flight request {@code SUPERSEDED} on the attempt
     * that spends the retry cap — the concurrent {@code land} of the race above, made deterministic
     * so the executor's re-read is exercised on every run rather than when timing cooperates.
     */
    private final class SupersedeOnLastMaterialize implements RuntimeAdapter {

        private final InMemoryRuntimeAdapter delegate;
        private final ScopeKey target;
        private final int capAt;
        private int materializeCalls;

        SupersedeOnLastMaterialize(InMemoryRuntimeAdapter delegate, ScopeKey target, int capAt) {
            this.delegate = delegate;
            this.target = target;
            this.capAt = capAt;
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
            if (++materializeCalls >= capAt) {
                ApplyRequestRow inFlight = ApplyEngineHarness.requests()
                        .blockingRequests(target.tenant(), target.instance()).get(0);
                outcomes.land(target.tenant(), inFlight.applyId(), RequestState.SUPERSEDED);
            }
            throw new RuntimeAdapterException(new ErrorClass("fake.materialize.failed"));
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

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }
}
