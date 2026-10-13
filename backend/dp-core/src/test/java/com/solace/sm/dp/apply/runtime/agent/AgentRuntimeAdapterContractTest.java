package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.AbstractRuntimeAdapterContractTest;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The runtime-neutral kit, over {@link AgentRuntimeAdapter} (plan 015 T3.1): the real adapter, the real hand-off, bridge and payload
 * source, the in-memory command queue with the JDBC repositories' identity semantics, and a {@link SimulatedAgent} that answers
 * through those ports with the Go journal's dedupe rule. Without this class the kit proves nothing about the agent path.
 *
 * <p>The three declared refusals are the fixture's, and the kit holds each to a coded refusal with nothing moved.
 */
class AgentRuntimeAdapterContractTest extends AbstractRuntimeAdapterContractTest {

    static final ConfigLocation LOCATION = new ConfigLocation("/app/external/spring/config",
            new WriterAccess.RemoteSftp("/srv/cache/config"), "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION);

    /** Everything one wired agent path needs, so the other tests in this package can share the wiring. */
    static final class World {
        final PlacementRef placement;
        final AgentPlacementRef ref;
        final UUID agentId = UUID.randomUUID();
        final ConfigHasher hasher = TestHashers.keyed();
        final AgentPayloadHandoff handoff = new AgentPayloadHandoff(64);
        final CountingRenderer renderer = new CountingRenderer();
        final StubGenerations generations = new StubGenerations();
        final AgentHashBridge bridge = new AgentHashBridge(renderer, hasher, generations);
        final HandoffAgentCommandPayloadSource source = new HandoffAgentCommandPayloadSource(handoff, renderer, hasher,
                generations, bridge);
        final InMemoryAgentPorts ports = new InMemoryAgentPorts(source);
        final SimulatedAgent agent;
        final AgentRuntimeAdapter adapter;

        World() {
            this(Duration.ofMillis(1), Duration.ofSeconds(10));
        }

        World(Duration poll, Duration ceiling) {
            placement = new PlacementRef(new TenantId(UUID.randomUUID()), new InstanceId(UUID.randomUUID()),
                    new PlacementId(UUID.randomUUID()));
            ref = new AgentPlacementRef(placement.tenant().value(), placement.instance().value(), placement.placement().value());
            agent = new SimulatedAgent(ports, agentId, ref);
            ports.afterEnqueue(row -> agent.step());
            AgentPlacementCatalog catalog = new AgentPlacementCatalog() {
                @Override
                public Optional<AgentPlacement> resolve(PlacementRef p) {
                    return p.equals(placement)
                            ? Optional.of(new AgentPlacement(agentId, ref, "cache-solution", LOCATION, "actuator-health"))
                            : Optional.empty();
                }

                /** The data plane records the start the agent acknowledged; in this world that is the agent's own. */
                @Override
                public Optional<Instant> observedStart(AgentPlacement p) {
                    return Optional.ofNullable(agent.startedAt());
                }
            };
            adapter = new AgentRuntimeAdapter(catalog, ports, ports, agent, hasher, handoff, bridge, Clock.systemUTC(),
                    new AgentRuntimeAdapter.Settings(Duration.ofMinutes(5), ceiling, ceiling, ceiling, ceiling,
                            Duration.ofMillis(50), poll));
        }

        TargetReplica replica() {
            return new TargetReplica(placement, new ReplicaRef(placement.placement().value().toString()));
        }
    }

    /** Counts renders: the render-once tests spy on it. */
    static final class CountingRenderer implements com.solace.sm.dp.apply.runtime.RenderedConfigSource {
        volatile int calls;
        volatile java.util.function.BiFunction<PlacementRef, Long, byte[]> source = (p, g) -> {
            throw new IllegalStateException("nothing renders in this test");
        };
        volatile com.solace.sm.dp.apply.runtime.ConfigHasher hasher = TestHashers.keyed();

        @Override
        public synchronized com.solace.sm.dp.apply.runtime.RenderedConfig render(PlacementRef placement, long generation) {
            calls++;
            return com.solace.sm.dp.apply.runtime.RenderedConfig.of(placement.tenant(), source.apply(placement, generation), hasher);
        }
    }

    /** The journal's two answers, programmable. */
    static final class StubGenerations implements AgentConfigGenerations {
        volatile List<Long> candidates = List.of();
        volatile java.util.OptionalLong applyGeneration = java.util.OptionalLong.empty();

        @Override
        public java.util.OptionalLong generationOfApply(TenantId tenant, com.solace.sm.dp.apply.model.ApplyId apply) {
            return applyGeneration;
        }

        @Override
        public List<Long> candidates(PlacementRef placement) {
            return candidates;
        }
    }

    @Override
    protected Fixture newFixture() {
        World w = new World();
        // the probe the package declared, answering healthy while the container runs
        w.agent.healthFacts(new AgentHealthObservation(AgentHealthObservation.State.HEALTHY, Instant.EPOCH,
                AgentHealthObservation.Strength.READINESS, 200, "UP", AgentHealthObservation.Failure.NONE));
        return new Fixture(new ContextInjectingAdapter(w.adapter), w.placement, List.of(w.replica()), LOCATION, true, w.hasher,
                Map.of(Unsupported.ROLLBACK, "agent.rollback.unsupported", Unsupported.READ_CONFIG, "config.read.unsupported",
                        Unsupported.STOP_NOOP_REPORT, ""));
    }
}
