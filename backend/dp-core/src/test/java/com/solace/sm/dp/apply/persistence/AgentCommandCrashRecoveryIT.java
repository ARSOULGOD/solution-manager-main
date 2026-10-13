package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandCompletion;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.worker.AgentSessionProbe;
import com.solace.sm.dp.apply.worker.ApplyRecoveryStatus;
import com.solace.sm.dp.apply.worker.ApplyStartupRecovery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T3.1, review focus 2: SM dies between the agent's {@code CommandReceived} and its {@code CommandAck}. The real apply
 * journal, the real agent repositories, the real router and adapter, over PostgreSQL 14; the agent is a {@link SimulatedAgent}
 * with the Go journal's dedupe rule, and the "SM restart" rebuilds everything the data plane holds in memory (hand-off, bridge,
 * payload source, repository, adapter) while the database and the agent survive.
 *
 * <p>Expected: the resumed engine finds the SAME command row by the intent's attempt-free key, the agent's journal re-acks it under
 * the same command id and generation, and the host operation runs exactly once.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentCommandCrashRecoveryIT {

    /** What the simulated crash throws: not a RuntimeAdapterException, so the step loop does not catch it (as a real crash). */
    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final java.util.concurrent.atomic.AtomicInteger WORLDS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A database of its own per test. The startup sweep enumerates EVERY tenant with open work in the database, so a world shared
     * between tests would let one test's sweep resume another test's leftovers.
     */
    private AgentEngineWorld w;

    @BeforeAll
    static void postgres() {
        SmDataPostgres.bootstrap();
    }

    @BeforeEach
    void world() {
        w = new AgentEngineWorld("agent_crash_recovery_" + WORLDS.incrementAndGet());
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (bean != null) {
            factory.registerSingleton("bean", bean);
        }
        return factory.getBeanProvider(type);
    }

    /** The agent's session goes quiet, as it does when SM (or the agent's connection) dies: the row is open but nothing refreshes it. */
    private void sessionGoesQuiet(Rig rig) {
        w.runtime.update("UPDATE sm_data.agent_session SET last_seen_at = now() - interval '10 minutes' WHERE tenant_id = :t"
                + " AND agent_id = :a", new MapSqlParameterSource().addValue("t", rig.scope().tenant().value())
                .addValue("a", rig.agent().agentId()));
    }

    /** The production wiring of the startup sweep: the real probe over the real catalog and session repository. */
    private ApplyStartupRecovery recovery(ApplyExecutor executor, WorkerSingletonGuard guard, Duration grace) {
        return new ApplyStartupRecovery(provider(ApplyExecutor.class, executor), new TenantEnumerationRepository(w.runtime), guard,
                new ApplyRecoveryStatus(), provider(IndeterminateReconciler.class, null),
                AgentSessionProbe.over(w.catalog, w.sessions), grace);
    }

    private void awaitState(Rig rig, ApplyId id, RequestState wanted, Duration within) throws InterruptedException {
        long until = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < until && w.stateOf(rig.scope().tenant(), id) != wanted) {
            Thread.sleep(50);
        }
        assertEquals(wanted, w.stateOf(rig.scope().tenant(), id));
    }

    /** SM dies between the agent's run of the RESTART and the ack reaching it: the common start of the recovery tests. */
    private ApplyId crashAfterTheRestartRan(Rig rig) {
        rig.sim().deferStartReports(true).dropAcksOf(AgentOperation.RESTART);
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(),
                crashWhenRun(AgentOperation.RESTART, () -> rig.sim().restartCount() >= 1));
        ApplyCommand restartAll = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L,
                Strategy.RESTART_ALL, "operator-1", "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            assertThrows(SimulatedCrash.class,
                    () -> w.executor(before.router(noSsh()), rig.renderer()).execute(restartAll));
        }
        ApplyId id = theOnlyOpenApply(rig);
        assertEquals(RequestState.RUNNING, w.stateOf(rig.scope().tenant(), id), "the crash left the request RUNNING");
        assertEquals(1, rig.sim().restartCount(), "the host restarted once before SM died");
        return id;
    }

    /** An adapter for the dp-remote side that must never be reached in these tests. */
    private static RuntimeAdapter noSsh() {
        return AgentEngineWorld.unreachable("an agent placement reached the dp-remote adapter");
    }

    /** Throws the simulated crash the moment {@code operation}'s command has been run by the agent and is polled for. */
    private static UnaryOperator<AgentCommandPort> crashWhenRun(AgentOperation operation, java.util.function.BooleanSupplier ran) {
        return delegate -> new AgentCommandPort() {
            @Override
            public com.solace.sm.dp.api.agw.AgentCommandRef enqueue(com.solace.sm.dp.api.agw.AgentCommandIntent intent) {
                return delegate.enqueue(intent);
            }

            @Override
            public void supersedeOutstanding(AgentPlacementRef placement, String keepIntentKey) {
                delegate.supersedeOutstanding(placement, keepIntentKey);
            }

            @Override
            public List<CommandNotice> pendingFor(AgentSessionRef ref, int limit, Duration renotifyAfter) {
                return delegate.pendingFor(ref, limit, renotifyAfter);
            }

            @Override
            public java.util.Set<UUID> agentsWithDeliverableWork(List<AgentSessionRef> sessions, Duration renotifyAfter) {
                return delegate.agentsWithDeliverableWork(sessions, renotifyAfter);
            }

            @Override
            public com.solace.sm.dp.api.agw.CommandOffer pull(AgentSessionRef ref, CommandId id) {
                return delegate.pull(ref, id);
            }

            @Override
            public void received(AgentSessionRef ref, String requestId, CommandId id, long wireGeneration) {
                delegate.received(ref, requestId, id, wireGeneration);
            }

            @Override
            public com.solace.sm.dp.api.agw.CommandAckResult ack(AgentSessionRef ref, CommandId id,
                    com.solace.sm.dp.api.agw.AgentAck ack) {
                return delegate.ack(ref, id, ack);
            }

            @Override
            public Optional<CommandCompletion> completion(AgentPlacementRef placement, CommandId id) {
                Optional<CommandCompletion> c = delegate.completion(placement, id);
                if (c.isPresent() && c.get().operation() == operation && ran.getAsBoolean()) {
                    throw new SimulatedCrash();
                }
                return c;
            }
        };
    }

    private record Rig(AgentEngineWorld.Agent agent, ScopeKey scope, SimulatedAgent sim, AgentEngineWorld.Renderer renderer,
            AgentSessionRef session) {
    }

    private Rig rig() {
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        renderer.byGeneration.put(7L, "generation: 7\nserver:\n  port: 8081\n".getBytes(StandardCharsets.UTF_8));
        return new Rig(agent, scope, sim, renderer, session);
    }

    private ApplyId theOnlyOpenApply(Rig rig) {
        return w.requests.nonTerminal(rig.scope().tenant()).get(0).applyId();
    }

    // ---- RESTART -----------------------------------------------------------------------------------------------------------

    /**
     * OR-146 (2), the real shape: after an SM crash the agent's session is NOT live (nothing refreshes it) and the agent comes
     * back some seconds after the startup sweep. The sweep defers the request instead of stranding it, the startup thread is not
     * held, and when the agent's new session is live the resume proceeds normally and the apply ends APPLIED with the container
     * restarted exactly once. (This test used to resume with the session live across the restart, which no real crash does:
     * W5 review M3.)
     */
    @Test
    void aRestartTheAgentRanButTheDataPlaneNeverHeardAckedRestartsTheContainerExactlyOnce() throws Exception {
        Rig rig = rig();
        ApplyId id = crashAfterTheRestartRan(rig);
        assertEquals(1, w.commandRows(rig.scope().tenant(), ":LIFECYCLE:" + rig.scope().placement().value()));

        // SM restarts: nothing in memory survives, and the agent's session is not live. The agent is not driven yet.
        sessionGoesQuiet(rig);
        rig.sim().dropAcksOf();
        AgentEngineWorld.Path after = w.path(rig.renderer(), rig.sim());
        ApplyExecutor executor = w.executor(after.router(noSsh()), rig.renderer());
        WorkerSingletonGuard guard = w.guard();
        ApplyStartupRecovery recovery = recovery(executor, guard, Duration.ofSeconds(60));
        try {
            long started = System.nanoTime();
            recovery.start();
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                    "the sweep returned: it did not wait for the agent on the startup thread");
            assertEquals(RequestState.RUNNING, w.stateOf(rig.scope().tenant(), id),
                    "the request waits for its agent; it was not stranded APPLY_INDETERMINATE");

            // the agent reconnects a little after the sweep (measured: 2 to 5 s) and answers on its new session
            Thread.sleep(800);
            rig.sim().rebindSession(w.reconnect(rig.agent()));
            try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
                awaitState(rig, id, RequestState.APPLIED, Duration.ofSeconds(30));
            }
        } finally {
            recovery.stop();
            guard.release();
        }

        assertEquals(1, rig.sim().restartCount(), "the container restarted EXACTLY once: the journal re-acked, never re-ran");
        assertEquals(1, rig.sim().writeCount(), "and the config was written exactly once");
        assertEquals(1, w.commandRows(rig.scope().tenant(), ":LIFECYCLE:" + rig.scope().placement().value()),
                "no second command row for the intent key");
        assertEquals(3, rig.sim().executed(), "PREFLIGHT, APPLY and RESTART ran once each; the resume re-ran nothing");
    }

    /**
     * OR-146 (2), the other half: the agent never comes back inside the grace. The request is resumed at the end of it exactly
     * as it always was and lands APPLY_INDETERMINATE (no new failure mode); when the agent is back later, the reconciler settles
     * it from a read, still with one restart.
     */
    @Test
    void anAgentThatNeverReturnsInsideTheGraceLeavesTheRequestIndeterminateForTheReconciler() throws Exception {
        Rig rig = rig();
        ApplyId id = crashAfterTheRestartRan(rig);
        sessionGoesQuiet(rig);
        rig.sim().dropAcksOf();
        AgentEngineWorld.Path after = w.path(rig.renderer(), rig.sim());
        ApplyExecutor executor = w.executor(after.router(noSsh()), rig.renderer());
        WorkerSingletonGuard guard = w.guard();
        ApplyStartupRecovery recovery = recovery(executor, guard, Duration.ofSeconds(2));
        try {
            long started = System.nanoTime();
            recovery.start();
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0,
                    "the sweep did not wait out the grace on the startup thread");
            assertEquals(RequestState.RUNNING, w.stateOf(rig.scope().tenant(), id), "waiting inside the grace");
            awaitState(rig, id, RequestState.APPLY_INDETERMINATE, Duration.ofSeconds(20));
        } finally {
            recovery.stop();
            guard.release();
        }
        assertEquals(1, rig.sim().restartCount(), "nothing was re-run while the agent was away");

        // the agent returns later: the reconciler reads the host and settles the request
        rig.sim().rebindSession(w.reconnect(rig.agent()));
        IndeterminateReconciler reconciler = new IndeterminateReconciler(executor, w.outcomes(),
                new com.solace.sm.dp.apply.persistence.JdbcReconciliationRepository(w.runtime),
                com.solace.sm.dp.apply.reconcile.ReconcileProperties.defaults(), java.time.Clock.systemUTC());
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            reconciler.enqueueIndeterminate(rig.scope().tenant());
            reconciler.runDue(rig.scope().tenant());
            assertEquals(RequestState.INDETERMINATE_RESOLVED, w.stateOf(rig.scope().tenant(), id));
        }
        assertEquals(1, rig.sim().restartCount(), "the reconciler's read confirmed it; the container restarted exactly once");
    }

    /** The sweep defers ONLY agent-target requests: a dp-remote request resumes inline, before start() returns, as before. */
    @Test
    void aDpRemoteRequestIsStillResumedInlineWithTheGraceConfigured() {
        UUID tenant = UUID.randomUUID();
        ScopeKey ssh = w.sshScope(tenant);
        PlacementRef sshRef = AgentEngineWorld.ref(ssh);
        com.solace.sm.dp.apply.runtime.RenderedConfigSource configs = (p, g) -> com.solace.sm.dp.apply.runtime.RenderedConfig.of(
                p.tenant(), "ssh: config\n".getBytes(StandardCharsets.UTF_8), w.hasher);
        AgentEngineWorld.Path path = w.path(new AgentEngineWorld.Renderer(w.hasher), ref -> Optional.empty());
        ApplyExecutor executor = w.executor(new com.solace.sm.dp.apply.runtime.RoutingRuntimeAdapter(w.catalog,
                new com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter(sshRef, true, "replica-1"), path.adapter), configs);
        ApplyId id = w.runningApply(ssh, new ApplyCommand(ssh, com.solace.sm.dp.apply.model.Operation.APPLY, 1L, Strategy.MANUAL, null,
                "operator-1"));
        WorkerSingletonGuard guard = w.guard();
        ApplyStartupRecovery recovery = recovery(executor, guard, Duration.ofSeconds(60));
        try {
            recovery.start();
            assertEquals(RequestState.STAGED, w.stateOf(new TenantId(tenant), id), "resumed inline: settled before start() returned");
        } finally {
            recovery.stop();
            guard.release();
        }
    }

    // ---- boot recovery against a session that went quiet (review finding 2) -----------------------------------------------

    /**
     * Startup recovery runs before any gateway can be listening, against sessions the previous process left open. A session
     * that has not been heard from within the live window is not live, so each resumed agent step fails fast
     * ({@code agent.session.absent}) instead of waiting out its whole ceiling, serially, inside boot. The command row is left
     * as it was for the agent's later ack.
     */
    @Test
    void resumingAtBootDoesNotBlockOnASessionThatWentQuiet() {
        Rig rig = rig();
        rig.sim().deferStartReports(true).dropAcksOf(AgentOperation.RESTART);
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(),
                crashWhenRun(AgentOperation.RESTART, () -> rig.sim().restartCount() >= 1));
        ApplyCommand restartAll = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L,
                Strategy.RESTART_ALL, "operator-1", "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            assertThrows(SimulatedCrash.class, () -> w.executor(before.router(noSsh()), rig.renderer()).execute(restartAll));
        }
        ApplyId id = theOnlyOpenApply(rig);
        // the process was down longer than the live window: the agent's session row is open but its agent has gone quiet
        w.runtime.update("UPDATE sm_data.agent_session SET last_seen_at = now() - interval '10 minutes' WHERE tenant_id = :t"
                + " AND agent_id = :a", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("t", rig.scope().tenant().value()).addValue("a", rig.agent().agentId()));

        AgentEngineWorld.Path after = w.path(rig.renderer(), rig.sim());          // NO driver: nothing answers during the resume
        long started = System.nanoTime();
        w.executor(after.router(noSsh()), rig.renderer()).resumeRunning(rig.scope().tenant());

        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "boot recovery did not wait on a dead session (the path's ceiling is 20 s)");
        assertTrue(w.attemptErrors(rig.scope().tenant(), id).contains("agent.session.absent"),
                "the steps ended with a coded outcome: " + w.attemptErrors(rig.scope().tenant(), id));
        assertEquals("DELIVERED", w.runtime.queryForObject("SELECT state FROM sm_data.agent_command WHERE tenant_id = :t AND"
                + " intent_key LIKE :k AND operation = 'RESTART'", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("t", rig.scope().tenant().value()).addValue("k", "%:LIFECYCLE:%"), String.class),
                "the command is left for the agent's later ack");
        assertEquals(1, rig.sim().restartCount());
    }

    // ---- APPLY ---------------------------------------------------------------------------------------------------------------

    @Test
    void anApplyTheAgentWroteButTheDataPlaneNeverHeardAckedWritesOnce() {
        Rig rig = rig();
        rig.sim().dropAcksOf(AgentOperation.APPLY);
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(),
                crashWhenRun(AgentOperation.APPLY, () -> rig.sim().writeCount() >= 1));
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");

        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            assertThrows(SimulatedCrash.class, () -> w.executor(before.router(noSsh()), rig.renderer()).execute(manual));
        }
        ApplyId id = theOnlyOpenApply(rig);
        assertEquals(1, rig.sim().writeCount());

        rig.sim().dropAcksOf();
        AgentEngineWorld.Path after = w.path(rig.renderer(), rig.sim());
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            w.executor(after.router(noSsh()), rig.renderer()).resumeRunning(rig.scope().tenant());
        }

        assertEquals(RequestState.STAGED, w.stateOf(rig.scope().tenant(), id));
        assertEquals(1, rig.sim().writeCount(), "one write, however many times SM asked");
        assertEquals(1, w.commandRows(rig.scope().tenant(), ":MATERIALIZE:" + rig.scope().placement().value()));
    }

    /**
     * Final review MINOR-1, the ledger rule: a resume whose preflight fails lands APPLY_INDETERMINATE when a non-PREFLIGHT step ever
     * opened an attempt (the restart test above), but stays FAILED_NO_EFFECT when its intents have no attempt at all: nothing was
     * dispatched for them. The crash window between the intent insert and the attempt open is modelled by removing the attempt rows.
     */
    @Test
    void aResumeWhosePreflightFailsLandsNoEffectWhenNoStepEverOpenedAnAttempt() {
        Rig rig = rig();
        rig.sim().dropAcksOf(AgentOperation.APPLY);
        AgentEngineWorld.Path before = w.path(rig.renderer(), rig.sim(),
                crashWhenRun(AgentOperation.APPLY, () -> rig.sim().writeCount() >= 1));
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            assertThrows(SimulatedCrash.class, () -> w.executor(before.router(noSsh()), rig.renderer()).execute(manual));
        }
        ApplyId id = theOnlyOpenApply(rig);
        w.runtime.update("DELETE FROM sm_data.apply_attempt WHERE tenant_id = :t", new org.springframework.jdbc.core.namedparam
                .MapSqlParameterSource("t", rig.scope().tenant().value()));
        sessionGoesQuiet(rig);

        AgentEngineWorld.Path after = w.path(rig.renderer(), rig.sim());          // NO driver: the preflight cannot be answered
        w.executor(after.router(noSsh()), rig.renderer()).resumeRunning(rig.scope().tenant());

        assertEquals(RequestState.FAILED_NO_EFFECT, w.stateOf(rig.scope().tenant(), id),
                "no attempt was ever opened for a MATERIALIZE or later step: provably no effect");
    }

    // ---- a superseded step ------------------------------------------------------------------------------------------------------

    @Test
    void aQueuedCommandOfASupersededStepIsNeverExecuted() throws Exception {
        Rig rig = rig();
        ApplyCommand manual = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L, Strategy.MANUAL, null,
                "operator-1");

        // first apply: the agent is silent, the wait times out, the apply fails: its PREFLIGHT command is left QUEUED
        AgentEngineWorld.Path impatient = w.impatientPath(rig.renderer(), rig.sim(), Duration.ofMillis(300));
        ApplyOutcome first = w.executor(impatient.router(noSsh()), rig.renderer()).execute(manual);
        assertInstanceOf(ApplyOutcome.Landed.class, first);
        assertEquals(RequestState.FAILED_NO_EFFECT, ((ApplyOutcome.Landed) first).state());
        long queued = w.runtime.queryForObject("SELECT count(*) FROM sm_data.agent_command WHERE tenant_id = :t AND state = 'QUEUED'",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", rig.scope().tenant().value()), Long.class);
        assertEquals(1, queued, "the unanswered PREFLIGHT is still queued");

        // a new apply of the same placement: its first command supersedes the old one, which the agent then never runs
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());
        // the agent only comes back once the new apply's first command has superseded the old one
        CompletableFuture<ApplyOutcome> secondRun = CompletableFuture
                .supplyAsync(() -> w.executor(path.router(noSsh()), rig.renderer()).execute(manual));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until && w.runtime.queryForObject("SELECT count(*) FROM sm_data.agent_command WHERE tenant_id = :t"
                + " AND state = 'SUPERSEDED'", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t",
                rig.scope().tenant().value()), Long.class) == 0) {
            Thread.sleep(5);
        }
        ApplyOutcome second;
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            second = secondRun.get(30, TimeUnit.SECONDS);
        }
        assertEquals(RequestState.STAGED, ((ApplyOutcome.Landed) second).state());
        assertEquals(2, rig.sim().executed(), "only the second apply's PREFLIGHT and APPLY ran; the superseded one never did");
        assertEquals(1L, w.runtime.queryForObject("SELECT count(*) FROM sm_data.agent_command WHERE tenant_id = :t"
                + " AND state = 'SUPERSEDED'", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t",
                rig.scope().tenant().value()), Long.class));
    }

    // ---- T2.3 integrator note: hold or reconcile -----------------------------------------------------------------------------

    /**
     * DECISION (T3.1): the data plane RECONCILES after the fact; it does not hold the placement back from the lease.
     *
     * <p>Why not hold: the data plane cannot know what the agent holds in its spool. All it has is "OFFERED, then superseded",
     * and holding every such placement out of the lease keeps the agent from receiving the very SUPERSEDED answer that would end
     * the command (the agent runs commands only under a lease that names their placement), so the placement would stay held until
     * something unlocked it, and no migration may add the marker that something would write.
     *
     * <p>What reconcile means here: the command is answered {@code SUPERSEDED} (so the agent closes its journal record), the fact
     * that it RAN is recorded on the row ({@code detail_code = late.applied}), and the newer command runs after it on the agent's
     * serial journal, so the newest generation wins. THE WINDOW: from the agent's reconnect until its {@code CommandReceived}
     * reaches the data plane, a superseded command that the agent journalled RECEIVED may execute once, and an in-flight operation
     * cannot be taken back. It is bounded to commands the agent had journalled before the supersession, it is never silent, and the
     * agent's STALE_GENERATION guard stops it from running AFTER a newer generation.
     */
    @Test
    void aSupersededCommandTheAgentResumesFromItsSpoolRunsOnceIsRecordedAsLateAndTheNewerOneWins() throws Exception {
        Rig rig = rig();
        TenantId tenant = rig.scope().tenant();
        PlacementRef ref = AgentEngineWorld.ref(rig.scope());
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(rig.scope().placement().value().toString()));
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());

        // an apply with two intents: the RESTART that will be superseded, and the STOP that supersedes it
        ApplyCommand command = new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY, 7L,
                Strategy.RESTART_ALL, "operator-1", "operator-1");
        ApplyId apply = w.runningApply(rig.scope(), command);
        RuntimeOperationContext restartCtx = w.context(tenant, apply, Step.LIFECYCLE, rig.scope(), replica, null);
        RuntimeOperationContext stopCtx = w.context(tenant, apply, Step.STOP, rig.scope(), replica, null);

        CompletableFuture<Object> restart = CompletableFuture.supplyAsync(() -> {
            try {
                return path.adapter.startOrUpdate(replica, 7, restartCtx);
            } catch (RuntimeAdapterException e) {
                return e;
            }
        });
        // the command is OFFERED to the agent, which journals it RECEIVED but whose CommandReceived never reaches the data plane
        CommandNotice notice = await(() -> w.agentSide.pendingFor(rig.session(), 10, Duration.ZERO).stream().findFirst());
        rig.sim().receiveWithoutAnnouncing(notice);

        // the engine moves on: its next step supersedes what is still queued or offered for the placement
        CompletableFuture<Object> stop = CompletableFuture.supplyAsync(() -> {
            try {
                return path.adapter.stop(replica, stopCtx);
            } catch (RuntimeAdapterException e) {
                return e;
            }
        });
        Object restartResult = restart.get(10, TimeUnit.SECONDS);
        assertEquals("agent.restart.superseded", assertInstanceOf(RuntimeAdapterException.class, restartResult).errorClass().token(),
                "the step waiting on the superseded command fails with its code");

        // the agent reconnects and resumes the RECEIVED command from its spool before the data plane could say SUPERSEDED
        rig.sim().resumeReceivedFromSpool();
        assertEquals(1, rig.sim().restartCount(), "the stray command ran once");

        // …and the newer command is delivered after it
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            Object stopResult = stop.get(20, TimeUnit.SECONDS);
            assertInstanceOf(com.solace.sm.dp.apply.runtime.LifecycleResult.class, stopResult);
        }
        assertEquals(1, rig.sim().stopCount());
        assertTrue(!rig.sim().running(), "the newest command won: the container ends stopped");

        String lateRecorded = w.runtime.queryForObject("SELECT string_agg(detail_code, ',') FROM sm_data.agent_command"
                + " WHERE tenant_id = :t AND state = 'SUPERSEDED'", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                "t", tenant.value()), String.class);
        assertEquals("late.applied", lateRecorded, "the late result is on the row: a stray execution is never silent");
    }

    private static <T> T await(java.util.function.Supplier<Optional<T>> source) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Optional<T> found = source.get();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("nothing turned up within 10 s");
    }
}
