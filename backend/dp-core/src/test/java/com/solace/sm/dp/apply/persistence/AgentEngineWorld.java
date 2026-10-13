package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentObservedStatePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentRegistrationState;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.agent.AgentAuthority;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentCommandRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentIdentityRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentLeaseRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentPlacementCatalog;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentSessionRepository;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RoutingRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.runtime.agent.AgentConfigGenerations;
import com.solace.sm.dp.apply.runtime.agent.AgentHashBridge;
import com.solace.sm.dp.apply.runtime.agent.AgentPayloadHandoff;
import com.solace.sm.dp.apply.runtime.agent.AgentRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.agent.HandoffAgentCommandPayloadSource;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The apply engine and the agent path over one PostgreSQL 14 database of their own (plan 015 T3.1): the REAL journal, the REAL
 * agent repositories and adapter, the real router, with only the agent (a {@link SimulatedAgent}), the control plane's
 * registration and the lease issuer faked. A database of its own because the worker these ITs run visits every tenant: sharing
 * the one the other ITs leave open requests in would let it claim their rows.
 *
 * <p>{@link #path} builds the data plane's in-memory half (hand-off, bridge, payload source, command repository, adapter) afresh,
 * which is exactly what an SM restart does: the database and the agent survive, nothing in memory does.
 */
final class AgentEngineWorld {

    final MetricsDb db;
    final NamedParameterJdbcTemplate runtime;
    final TransactionTemplate tx;
    final Clock clock = Clock.systemUTC();
    final ConfigHasher hasher = TestHashers.keyed();
    final ApplyScopeRepository scopes;
    final ApplyRequestRepository requests;
    final StepIntentRepository intents;
    final AttemptRepository attempts;
    final AgentAuthority authority;
    final JdbcAgentIdentityRepository identities;
    final JdbcAgentSessionRepository sessions;
    final JdbcAgentLeaseRepository leases;
    final JdbcAgentPlacementCatalog catalog;
    private final Map<UUID, AgentRegistrationState> registrations = new ConcurrentHashMap<>();

    AgentEngineWorld(String databaseName) {
        db = MetricsDb.fresh(databaseName);
        DataSource rt = db.runtime();
        runtime = new NamedParameterJdbcTemplate(rt);
        tx = new TransactionTemplate(new DataSourceTransactionManager(rt));
        scopes = new ApplyScopeRepository(runtime);
        requests = new ApplyRequestRepository(runtime);
        intents = new StepIntentRepository(runtime);
        attempts = new AttemptRepository(runtime);
        authority = new AgentAuthority(runtime, clock, tx);
        identities = new JdbcAgentIdentityRepository(runtime, tx, clock, authority, () -> ids -> {
            Map<UUID, AgentRegistrationState> found = new HashMap<>();
            ids.forEach(id -> {
                AgentRegistrationState state = registrations.get(id);
                if (state != null) {
                    found.put(id, state);
                }
            });
            return found;
        });
        sessions = new JdbcAgentSessionRepository(runtime, clock, authority, identities);
        leases = new JdbcAgentLeaseRepository(runtime, tx, clock, authority, identities, () -> issuer());
        catalog = new JdbcAgentPlacementCatalog(runtime);
    }

    /** An adapter that fails the test on any call: the side of the router a test says must not be reached. */
    static RuntimeAdapter unreachable(String why) {
        return (RuntimeAdapter) java.lang.reflect.Proxy.newProxyInstance(RuntimeAdapter.class.getClassLoader(),
                new Class<?>[] {RuntimeAdapter.class}, (proxy, method, args) -> {
                    throw new AssertionError(why + ": " + method.getName());
                });
    }

    // ---- the engine -----------------------------------------------------------------------------------------------------

    static ApplyEngineProperties engineProperties() {
        return new ApplyEngineProperties(Duration.ofMinutes(5), Duration.ofMillis(400), Duration.ofMillis(10));
    }

    AdmissionService admission() {
        return new AdmissionService(tx, scopes, requests, clock);
    }

    OutcomeService outcomes() {
        return new OutcomeService(tx, scopes, requests, clock);
    }

    ApplyExecutor executor(RuntimeAdapter adapter, RenderedConfigSource configs) {
        ApplyEngineProperties props = engineProperties();
        StepIntentService stepIntents = new StepIntentService(tx, requests, intents, clock);
        AttemptService attemptService = new AttemptService(tx, attempts, new AttemptPolicy(2), clock);
        return new ApplyExecutor(admission(), stepIntents, attemptService, outcomes(), adapter, configs,
                new HealthGate(clock, Sleeper.real(), props), new ResumePlanner(), props, ApplyEventEmitter.silent(),
                new com.solace.sm.dp.inventory.RegistryLivePlacements(new JdbcPlacementRepository(runtime)));
    }

    ApplyClaimRepository claims() {
        return new ApplyClaimRepository(tx, runtime, requests);
    }

    WorkerSingletonGuard guard() {
        var pg = SmDataPostgres.container();
        return new WorkerSingletonGuard(new DriverManagerDataSource(db.url(), pg.getUsername(), pg.getPassword()));
    }

    RequestState stateOf(TenantId tenant, ApplyId id) {
        return requests.find(tenant, id).orElseThrow().state();
    }

    /** The error classes the apply's attempts closed with, oldest first. */
    List<String> attemptErrors(TenantId tenant, ApplyId id) {
        return runtime.query("SELECT error_class FROM sm_data.apply_attempt WHERE tenant_id = :t AND apply_id = :a"
                + " AND error_class IS NOT NULL ORDER BY started_at", new MapSqlParameterSource().addValue("t", tenant.value())
                .addValue("a", id.value()), (rs, n) -> rs.getString(1));
    }

    long commandRows(TenantId tenant, String intentKeySuffixLike) {
        Long n = runtime.queryForObject("SELECT count(*) FROM sm_data.agent_command WHERE tenant_id = :t"
                + " AND intent_key LIKE :k", new MapSqlParameterSource().addValue("t", tenant.value())
                .addValue("k", "%" + intentKeySuffixLike), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * The journal identity of one step of {@code apply}, with its intent row made as the engine makes it (so the command's foreign
     * keys hold): {@code hashOrNull} is the intended keyed hash for a MATERIALIZE intent and null for every other step.
     */
    RuntimeOperationContext context(TenantId tenant, ApplyId apply, Step step, ScopeKey scope, TargetReplica replica,
            String hashOrNull) {
        String key = ApplyExecutor.idempotencyKey(apply, step, replica.ref());
        StepIntentRow intent = stepIntents().ensureIntent(tenant, apply, scope.placement(), step, replica.ref(), key, hashOrNull);
        return new RuntimeOperationContext(apply, intent.intentId(), key, UUID.randomUUID(), step);
    }

    /** Admits a request and walks it to RUNNING, as the worker's claim does, so its steps can be issued by hand. */
    ApplyId runningApply(ScopeKey scope, ApplyCommand command) {
        ApplyId apply = ((com.solace.sm.dp.apply.engine.AdmissionOutcome.Admitted) admission().admit(command)).applyId();
        requests.transition(scope.tenant(), apply, RequestState.REQUESTED, RequestState.RUNNING, null);
        return apply;
    }

    // ---- the inventory --------------------------------------------------------------------------------------------------

    /** An enrolled agent, its target and identity, and what the control plane says about it. */
    record Agent(UUID tenant, UUID agentId, String thumbprint, UUID targetId) {
    }

    Agent enrol(UUID tenant) {
        UUID agent = UUID.randomUUID();
        String thumbprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        UUID target = InventoryFixtures.agentTarget(runtime, tenant, agent, thumbprint);
        registrations.put(agent, new AgentRegistrationState(agent, true, target,
                List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), 1, 1));
        return new Agent(tenant, agent, thumbprint, target);
    }

    /** A live placement of a NEW instance on the agent's target, as the scope an apply addresses. */
    ScopeKey agentScope(Agent agent) {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        InventoryFixtures.agentPlacement(runtime, agent.tenant(), instance, agent.targetId(), placement);
        return new ScopeKey(new TenantId(agent.tenant()), new InstanceId(instance), new PlacementId(placement));
    }

    /** A live placement of a new instance on a fresh dp-remote (ssh-docker) target. */
    ScopeKey sshScope(UUID tenant) {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        InventoryFixtures.registered(runtime, tenant, instance, placement);
        return new ScopeKey(new TenantId(tenant), new InstanceId(instance), new PlacementId(placement));
    }

    static PlacementRef ref(ScopeKey scope) {
        return new PlacementRef(scope.tenant(), scope.instance(), scope.placement());
    }

    static AgentPlacementRef agentRef(ScopeKey scope) {
        return new AgentPlacementRef(scope.tenant().value(), scope.instance().value(), scope.placement().value());
    }

    // ---- session and lease ------------------------------------------------------------------------------------------------

    AgentSessionRef connect(Agent agent) {
        AgentSessionRef session = sessions.claim(agent.thumbprint(), new SessionOpening(1, "0.1.0", Set.of(), clock.instant()),
                new AgentRequestOrigin(UUID.randomUUID(), "10.0.0.7"));
        leases.issue("lease-" + UUID.randomUUID(), session, clock.instant(), new AgentRequestOrigin(UUID.randomUUID(), "10.0.0.7"));
        return session;
    }

    /**
     * The agent's connection returns after an SM restart: a new session (a higher epoch) claimed under the lease it already holds.
     * A second {@link #connect} would try to mint a second lease, which the identity may not have.
     */
    AgentSessionRef reconnect(Agent agent) {
        return sessions.claim(agent.thumbprint(), new SessionOpening(1, "0.1.0", Set.of(), clock.instant()),
                new AgentRequestOrigin(UUID.randomUUID(), "10.0.0.7"));
    }

    private LeaseIssuer issuer() {
        return (identity, session, agentClock, requestId, consumedNonce, placementIds, origin) -> {
            Instant now = clock.instant();
            Map<AgentOperation, Duration> ceilings = new EnumMap<>(AgentOperation.class);
            for (AgentOperation op : AgentOperation.values()) {
                ceilings.put(op, Duration.ofSeconds(60));
            }
            String inputs = identity.agentId() + "|" + requestId + "|" + consumedNonce + "|" + placementIds.stream().sorted().toList();
            return new LeaseGrant(identity.agentId(), identity.certificateThumbprint(), identity.targetId(),
                    new ArrayList<>(placementIds), identity.agentRevocationEpoch(), identity.fleetRevocationEpoch(), now,
                    now.plus(Duration.ofMinutes(30)), "dp", "nonce-" + UUID.nameUUIDFromBytes(inputs.getBytes(StandardCharsets.UTF_8)),
                    identity.capabilities(), ceilings, Duration.ofSeconds(30), now);
        };
    }

    /**
     * The agent's side of the command queue: whichever path (SM process) is current. The agent survives an SM restart and simply
     * finds the new process behind the same address.
     */
    static final class AgentSide implements AgentCommandPort {
        volatile AgentCommandPort target;

        @Override
        public com.solace.sm.dp.api.agw.AgentCommandRef enqueue(com.solace.sm.dp.api.agw.AgentCommandIntent intent) {
            throw new UnsupportedOperationException("an agent never enqueues");
        }

        @Override
        public void supersedeOutstanding(AgentPlacementRef placement, String keepIntentKey) {
            throw new UnsupportedOperationException("an agent never supersedes");
        }

        @Override
        public List<com.solace.sm.dp.api.agw.CommandNotice> pendingFor(AgentSessionRef ref, int limit, Duration renotifyAfter) {
            return target.pendingFor(ref, limit, renotifyAfter);
        }

        @Override
        public Set<UUID> agentsWithDeliverableWork(List<AgentSessionRef> sessions, Duration renotifyAfter) {
            return target.agentsWithDeliverableWork(sessions, renotifyAfter);
        }

        @Override
        public com.solace.sm.dp.api.agw.CommandOffer pull(AgentSessionRef ref, com.solace.sm.dp.api.agw.CommandId id) {
            return target.pull(ref, id);
        }

        @Override
        public void received(AgentSessionRef ref, String requestId, com.solace.sm.dp.api.agw.CommandId id, long wireGeneration) {
            target.received(ref, requestId, id, wireGeneration);
        }

        @Override
        public com.solace.sm.dp.api.agw.CommandAckResult ack(AgentSessionRef ref, com.solace.sm.dp.api.agw.CommandId id,
                com.solace.sm.dp.api.agw.AgentAck ack) {
            return target.ack(ref, id, ack);
        }

        @Override
        public java.util.Optional<com.solace.sm.dp.api.agw.CommandCompletion> completion(AgentPlacementRef placement,
                com.solace.sm.dp.api.agw.CommandId id) {
            return target.completion(placement, id);
        }
    }

    final AgentSide agentSide = new AgentSide();

    /** What the next {@link Path}'s command repository runs after an acknowledged start moves the observation. */
    volatile com.solace.sm.dp.apply.persistence.agent.AckStartSettler ackSettler =
            com.solace.sm.dp.apply.persistence.agent.AckStartSettler.NONE;

    // ---- the data plane's in-memory half, rebuilt like an SM restart -------------------------------------------------------

    /** Counts renders and renders what the test says each generation is. */
    static final class Renderer implements RenderedConfigSource {
        volatile int calls;
        final Map<Long, byte[]> byGeneration = new ConcurrentHashMap<>();
        final ConfigHasher hasher;

        Renderer(ConfigHasher hasher) {
            this.hasher = hasher;
        }

        @Override
        public RenderedConfig render(PlacementRef placement, long generation) {
            calls++;
            byte[] bytes = byGeneration.get(generation);
            if (bytes == null) {
                throw new IllegalStateException("no such generation in this test");
            }
            return RenderedConfig.of(placement.tenant(), bytes, hasher);
        }
    }

    /** What an SM process holds in memory: rebuilt, it is a restart. */
    final class Path {
        final AgentPayloadHandoff handoff = new AgentPayloadHandoff(64);
        final AgentHashBridge bridge;
        final HandoffAgentCommandPayloadSource source;
        final JdbcAgentCommandRepository commands;
        final AgentCommandPort port;
        final AgentRuntimeAdapter adapter;

        Path(Renderer renderer, AgentObservedStatePort observed, UnaryOperator<AgentCommandPort> decorate, Duration ceiling) {
            AgentConfigGenerations generations = new AgentConfigGenerations() {
                @Override
                public OptionalLong generationOfApply(TenantId tenant, ApplyId apply) {
                    try {
                        Long g = outcomes().requestOf(tenant, apply).generation();
                        return g == null ? OptionalLong.empty() : OptionalLong.of(g);
                    } catch (RuntimeException e) {
                        return OptionalLong.empty();
                    }
                }

                @Override
                public List<Long> candidates(PlacementRef placement) {
                    return List.copyOf(renderer.byGeneration.keySet().stream().sorted(java.util.Comparator.reverseOrder()).toList());
                }
            };
            bridge = new AgentHashBridge(renderer, hasher, generations);
            source = new HandoffAgentCommandPayloadSource(handoff, renderer, hasher, generations, bridge);
            commands = new JdbcAgentCommandRepository(runtime, tx, clock, authority, source, ackSettler);
            agentSide.target = commands;
            port = decorate.apply(commands);
            adapter = new AgentRuntimeAdapter(catalog, port, sessions, observed, hasher, handoff, bridge, clock,
                    new AgentRuntimeAdapter.Settings(Duration.ofMinutes(5), ceiling, ceiling, ceiling, ceiling,
                            Duration.ofMillis(200), Duration.ofMillis(5)));
        }

        /** The one router the engine holds: dp-remote placements to {@code ssh}, agent placements to this path's adapter. */
        RuntimeAdapter router(RuntimeAdapter ssh) {
            return new RoutingRuntimeAdapter(catalog, ssh, adapter);
        }
    }

    Path path(Renderer renderer, AgentObservedStatePort observed) {
        return new Path(renderer, observed, UnaryOperator.identity(), Duration.ofSeconds(20));
    }

    Path path(Renderer renderer, AgentObservedStatePort observed, UnaryOperator<AgentCommandPort> decorate) {
        return new Path(renderer, observed, decorate, Duration.ofSeconds(20));
    }

    /** A path whose per-operation wait ceiling is short: for a test that wants an unanswered command to time out. */
    Path impatientPath(Renderer renderer, AgentObservedStatePort observed, Duration ceiling) {
        return new Path(renderer, observed, UnaryOperator.identity(), ceiling);
    }

    StepIntentService stepIntents() {
        return new StepIntentService(tx, requests, intents, clock);
    }

    // ---- the agent, running --------------------------------------------------------------------------------------------------

    /** Steps the agent every few milliseconds until closed, as its Connect loop would; survives any single failure. */
    static final class Driver implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        Driver(SimulatedAgent agent) {
            thread = new Thread(() -> {
                while (running.get()) {
                    try {
                        agent.step();
                    } catch (RuntimeException e) {
                        // a refused call during a restart window is the agent's to retry on its next tick
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "simulated-agent");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }
    }
}
