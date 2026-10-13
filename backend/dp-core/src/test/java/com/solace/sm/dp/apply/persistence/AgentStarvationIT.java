package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.reconcile.ReconcileProperties;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RoutingRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.worker.ApplyWorker;
import com.solace.sm.dp.apply.worker.ApplyWorkerProperties;
import com.solace.sm.dp.apply.worker.ApplyWorkerStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T3.1, review focus 5: an agent that is OFFLINE while applies are queued must not starve dp-remote. The real durable
 * worker (pool of 2), the real claim query and cap, the real router; the agent path is the real adapter over the real
 * repositories, with no session ever opened, so every agent step fails fast.
 *
 * <p>To make the guard observable, each agent step is first HELD for {@link #HOLD} (a wrapper standing for a worker thread parked
 * in an agent wait, which is exactly what the cap exists to bound) and only then delegated, whereupon the real adapter answers
 * {@code agent.session.absent}. The dp-remote request is admitted LAST, so claim order puts it behind three agent requests.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentStarvationIT {

    private static final Duration HOLD = Duration.ofMillis(1500);

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (bean != null) {
            factory.registerSingleton("bean", bean);
        }
        return factory.getBeanProvider(type);
    }

    /** The real agent adapter, held for {@link #HOLD} on {@code prepare} while counting how many are held at once. */
    private static final class Held implements java.lang.reflect.InvocationHandler {
        private final RuntimeAdapter delegate;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicLong firstFinishedAtNanos = new AtomicLong();

        Held(RuntimeAdapter delegate) {
            this.delegate = delegate;
        }

        RuntimeAdapter adapter() {
            return (RuntimeAdapter) java.lang.reflect.Proxy.newProxyInstance(RuntimeAdapter.class.getClassLoader(),
                    new Class<?>[] {RuntimeAdapter.class}, this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            boolean prepare = (method.getName().equals("prepare") && args.length == 3) || method.getName().equals("status");
            if (prepare) {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(HOLD.toMillis());
                    return invokeDelegate(method, args);
                } finally {
                    firstFinishedAtNanos.compareAndSet(0, System.nanoTime());
                    inFlight.decrementAndGet();
                }
            }
            return invokeDelegate(method, args);
        }

        private Object invokeDelegate(java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private record Outcome(long dpRemoteDoneNanos, long firstAgentFinishedNanos, int maxAgentInFlight,
            List<RequestState> agentStates, List<List<String>> agentErrors, RequestState dpRemoteState) {
    }

    private Outcome run(String database, int agentCap) throws Exception {
        SmDataPostgres.bootstrap();
        AgentEngineWorld w = new AgentEngineWorld(database);
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);                   // enrolled, never connected: offline
        List<ScopeKey> agentScopes = List.of(w.agentScope(agent), w.agentScope(agent), w.agentScope(agent));
        ScopeKey sshScope = w.sshScope(tenant);
        PlacementRef sshRef = AgentEngineWorld.ref(sshScope);

        AgentEngineWorld.Renderer agentRenderer = new AgentEngineWorld.Renderer(w.hasher);
        agentRenderer.byGeneration.put(1L, "agent: config\n".getBytes(StandardCharsets.UTF_8));
        // the engine renders per placement; the dp-remote fixture adapter hashes with the unkeyed test hasher
        com.solace.sm.dp.apply.runtime.RenderedConfigSource configs = (p, g) -> p.equals(sshRef)
                ? RenderedConfig.of(p.tenant(), "ssh: config\n".getBytes(StandardCharsets.UTF_8), TestHashers.SHA256)
                : agentRenderer.render(p, g);

        AgentEngineWorld.Path path = w.path(agentRenderer, ref -> java.util.Optional.empty());
        Held held = new Held(path.adapter);
        RuntimeAdapter router = new RoutingRuntimeAdapter(w.catalog, new InMemoryRuntimeAdapter(sshRef, true, "replica-1"),
                held.adapter());
        ApplyExecutor executor = w.executor(router, configs);

        // three agent applies first (older), the dp-remote apply last (youngest): FIFO claim order puts it behind them
        List<ApplyId> agentApplies = new ArrayList<>();
        for (ScopeKey scope : agentScopes) {
            agentApplies.add(((AdmissionOutcome.Admitted) w.admission()
                    .admit(new ApplyCommand(scope, Operation.APPLY, 1L, Strategy.MANUAL, null, "operator-1"))).applyId());
            Thread.sleep(5);
        }
        ApplyId dpRemote = ((AdmissionOutcome.Admitted) w.admission()
                .admit(new ApplyCommand(sshScope, Operation.APPLY, 1L, Strategy.MANUAL, null, "operator-1"))).applyId();

        ApplyWorker worker = new ApplyWorker(provider(ApplyExecutor.class, executor), w.claims(),
                new TenantEnumerationRepository(w.runtime), new ApplyWorkerProperties(2, Duration.ofMillis(50), Duration.ofSeconds(10)),
                w.guard(), new ApplyWorkerStatus(), provider(IndeterminateReconciler.class, null), agentCap);
        TenantId t = new TenantId(tenant);
        long dpRemoteDone = 0;
        try {
            worker.start();
            Instant deadline = Instant.now().plusSeconds(60);
            while (Instant.now().isBefore(deadline) && !allTerminal(w, t, agentApplies, dpRemote)) {
                if (dpRemoteDone == 0 && w.stateOf(t, dpRemote).isTerminal()) {
                    dpRemoteDone = System.nanoTime();
                }
                Thread.sleep(10);
            }
            if (dpRemoteDone == 0 && w.stateOf(t, dpRemote).isTerminal()) {
                dpRemoteDone = System.nanoTime();
            }
        } finally {
            worker.stop();
        }
        List<RequestState> states = agentApplies.stream().map(a -> w.stateOf(t, a)).toList();
        List<List<String>> errors = agentApplies.stream().map(a -> w.attemptErrors(t, a)).toList();
        return new Outcome(dpRemoteDone, held.firstFinishedAtNanos.get(), held.maxInFlight.get(), states, errors,
                w.stateOf(t, dpRemote));
    }

    private static boolean allTerminal(AgentEngineWorld w, TenantId t, List<ApplyId> agentApplies, ApplyId dpRemote) {
        return w.stateOf(t, dpRemote).isTerminal() && agentApplies.stream().allMatch(a -> w.stateOf(t, a).isTerminal());
    }

    @Test
    void anOfflineAgentNeverStarvesDpRemoteAndAtMostOneAgentRequestIsEverClaimed() throws Exception {
        Outcome o = run("agent_starvation", 1);          // pool 2, cap 1: one thread is always left for dp-remote

        assertEquals(RequestState.STAGED, o.dpRemoteState(), "the dp-remote apply completed");
        assertTrue(o.dpRemoteDoneNanos() != 0 && o.firstAgentFinishedNanos() != 0);
        assertTrue(o.dpRemoteDoneNanos() < o.firstAgentFinishedNanos(),
                "the dp-remote apply finished while the first agent step was still held: it was never queued behind the agents");
        assertEquals(1, o.maxAgentInFlight(), "at most one agent request was ever claimed at once");
        for (int i = 0; i < 3; i++) {
            assertEquals(RequestState.FAILED_NO_EFFECT, o.agentStates().get(i), "each agent step failed without effect");
            assertTrue(o.agentErrors().get(i).contains("agent.session.absent"),
                    "…fast, with the session-absent code: " + o.agentErrors().get(i));
        }
    }

    /** The mutation this guards: no cap. With both threads taken by held agent requests, dp-remote waits behind them. */
    @Test
    void withoutTheCapTheSameLoadStarvesDpRemoteBehindTheAgentRequests() throws Exception {
        Outcome o = run("agent_starvation_uncapped", 2);

        assertTrue(o.dpRemoteDoneNanos() > o.firstAgentFinishedNanos(),
                "(the control) with no cap the dp-remote apply only ran after an agent step let go of a thread");
        assertEquals(2, o.maxAgentInFlight());
    }

    private record ReconOutcome(long dpRemoteDoneNanos, long firstFinishedNanos, int maxInFlight, RequestState dpRemoteState) {
    }

    /**
     * Review finding 3: a due INDETERMINATE reconciliation may re-drive a restart on an agent target, which parks a worker thread
     * for the verb ceiling, so it must count against the same cap as a claimed agent apply. One agent apply is held, one agent
     * reconciliation is due (its status read is held), and a dp-remote apply arrives once both are under way.
     */
    private ReconOutcome runWithAReconciliation(String database, int agentCap) throws Exception {
        SmDataPostgres.bootstrap();
        AgentEngineWorld w = new AgentEngineWorld(database);
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ScopeKey reconcileScope = w.agentScope(agent);
        ScopeKey busyScope = w.agentScope(agent);
        ScopeKey sshScope = w.sshScope(tenant);
        PlacementRef sshRef = AgentEngineWorld.ref(sshScope);
        AgentEngineWorld.Renderer agentRenderer = new AgentEngineWorld.Renderer(w.hasher);
        agentRenderer.byGeneration.put(1L, "agent: config\n".getBytes(StandardCharsets.UTF_8));
        com.solace.sm.dp.apply.runtime.RenderedConfigSource configs = (p, g) -> p.equals(sshRef)
                ? RenderedConfig.of(p.tenant(), "ssh: config\n".getBytes(StandardCharsets.UTF_8), TestHashers.SHA256)
                : agentRenderer.render(p, g);
        AgentEngineWorld.Path path = w.path(agentRenderer, ref -> java.util.Optional.empty());
        Held held = new Held(path.adapter);
        ApplyExecutor executor = w.executor(new RoutingRuntimeAdapter(w.catalog,
                new InMemoryRuntimeAdapter(sshRef, true, "replica-1"), held.adapter()), configs);
        TenantId t = new TenantId(tenant);

        // a request the data plane landed APPLY_INDETERMINATE after writing generation 1 on an agent placement
        com.solace.sm.dp.apply.runtime.TargetReplica replica = new com.solace.sm.dp.apply.runtime.TargetReplica(
                AgentEngineWorld.ref(reconcileScope),
                new com.solace.sm.dp.apply.model.ReplicaRef(reconcileScope.placement().value().toString()));
        ApplyId indeterminate = w.runningApply(reconcileScope, new ApplyCommand(reconcileScope, Operation.APPLY, 1L, Strategy.MANUAL,
                null, "operator-1"));
        w.context(t, indeterminate, com.solace.sm.dp.apply.model.Step.MATERIALIZE, reconcileScope, replica,
                RenderedConfig.of(t, "agent: config\n".getBytes(StandardCharsets.UTF_8), w.hasher).hash());
        w.outcomes().land(t, indeterminate, RequestState.APPLY_INDETERMINATE);
        // and an agent apply waiting to be claimed
        admitApply(w, busyScope);

        IndeterminateReconciler reconciler = new IndeterminateReconciler(executor, w.outcomes(),
                new JdbcReconciliationRepository(w.runtime), ReconcileProperties.defaults(), java.time.Clock.systemUTC());
        ApplyWorker worker = new ApplyWorker(provider(ApplyExecutor.class, executor), w.claims(),
                new TenantEnumerationRepository(w.runtime), new ApplyWorkerProperties(2, Duration.ofMillis(50), Duration.ofSeconds(10)),
                w.guard(), new ApplyWorkerStatus(), provider(IndeterminateReconciler.class, reconciler), agentCap);
        ApplyId dpRemote;
        long done = 0;
        try {
            worker.start();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (held.inFlight.get() < Math.min(agentCap, 2) && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            Thread.sleep(400);                                   // everything that may take a thread has taken it
            dpRemote = admitApply(w, sshScope);
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline) && !w.stateOf(t, dpRemote).isTerminal()) {
                Thread.sleep(10);
            }
            done = System.nanoTime();
        } finally {
            worker.stop();
        }
        return new ReconOutcome(done, held.firstFinishedAtNanos.get(), held.maxInFlight.get(), w.stateOf(t, dpRemote));
    }

    private static ApplyId admitApply(AgentEngineWorld w, ScopeKey scope) {
        return ((AdmissionOutcome.Admitted) w.admission()
                .admit(new ApplyCommand(scope, Operation.APPLY, 1L, Strategy.MANUAL, null, "operator-1"))).applyId();
    }

    @Test
    void aDueAgentReconciliationCountsAgainstTheCapSoDpRemoteStillGetsAThread() throws Exception {
        ReconOutcome o = runWithAReconciliation("agent_starvation_recon", 1);

        assertEquals(RequestState.STAGED, o.dpRemoteState());
        assertTrue(o.firstFinishedNanos() == 0 || o.dpRemoteDoneNanos() < o.firstFinishedNanos(),
                "the dp-remote apply finished while an agent wait was still held");
        assertEquals(1, o.maxInFlight(), "the apply and the reconciliation together never held more than the cap");
    }

    /** The control: with room for both, the reconciliation and the agent apply take the pool and dp-remote waits behind them. */
    @Test
    void withoutTheCapTheReconciliationAndTheAgentApplyTakeBothThreads() throws Exception {
        ReconOutcome o = runWithAReconciliation("agent_starvation_recon_uncapped", 2);

        assertEquals(2, o.maxInFlight());
        assertTrue(o.dpRemoteDoneNanos() > o.firstFinishedNanos(), "dp-remote only ran after a held agent wait let go");
    }

    /** Plan: each agent step fails fast. With no hold at all, every agent request lands without a wait on the agent. */
    @Test
    void anOfflineAgentsRequestsLandWithinAFewPollsWithTheSessionAbsentCode() throws Exception {
        SmDataPostgres.bootstrap();
        AgentEngineWorld w = new AgentEngineWorld("agent_fast_fail");
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        renderer.byGeneration.put(1L, "agent: config\n".getBytes(StandardCharsets.UTF_8));
        ApplyExecutor executor = w.executor(w.path(renderer, ref -> java.util.Optional.empty())
                .router(AgentEngineWorld.unreachable("an agent placement reached ssh")), renderer);
        List<ApplyId> applies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            applies.add(admitApply(w, w.agentScope(agent)));
        }
        ApplyWorker worker = new ApplyWorker(provider(ApplyExecutor.class, executor), w.claims(),
                new TenantEnumerationRepository(w.runtime), new ApplyWorkerProperties(2, Duration.ofMillis(50), Duration.ofSeconds(10)),
                w.guard(), new ApplyWorkerStatus(), provider(IndeterminateReconciler.class, null), 1);
        TenantId t = new TenantId(tenant);
        long started = System.nanoTime();
        try {
            worker.start();
            while (System.nanoTime() - started < Duration.ofSeconds(20).toNanos()
                    && !applies.stream().allMatch(a -> w.stateOf(t, a).isTerminal())) {
                Thread.sleep(10);
            }
        } finally {
            worker.stop();
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "three offline agent requests, one slot: each fails at once, none waits on the agent");
        for (ApplyId a : applies) {
            assertEquals(RequestState.FAILED_NO_EFFECT, w.stateOf(t, a));
            assertTrue(w.attemptErrors(t, a).contains("agent.session.absent"));
        }
    }
}
