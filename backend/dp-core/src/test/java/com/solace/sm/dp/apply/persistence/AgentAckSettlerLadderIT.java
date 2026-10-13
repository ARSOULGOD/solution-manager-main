package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.agent.AgentGatewayPortsConfiguration;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.persistence.agent.AckStartSettler;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import com.solace.sm.dp.config.ConfigLadderReader;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.events.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 W4 note (c) (W3 integration re-review 10): {@code AgentGatewayPortsConfiguration}'s {@code AckStartSettler} lambda,
 * built BY THE CONFIGURATION (the real bean method, not a copy), composed with the REAL ladder, and driven by an agent RESTART
 * acknowledgement to {@code restart-observed}. Until now the settler was asserted by count only ({@code AgentAckBaselineIT}); the
 * ladder code it calls is the intake's and was proven there. Nothing here reports: the observed state is the simulated agent, no
 * report intake runs, and the engine's own outcome service carries NO ladder recorder, so the acknowledgement's settler is the
 * only writer that can move {@code instance_state.reached_rung}.
 *
 * <p>The control case runs the same sequence with {@link AckStartSettler#NONE} and the stored rung does not move: that is the
 * mutation this test exists to catch (a settler that is wired and never called, or wired to nothing).
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentAckSettlerLadderIT {

    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final UUID ACTOR = UUID.randomUUID();

    private static AgentEngineWorld w;

    private final List<EventEnvelope> configStates = new ArrayList<>();

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_ack_settler_ladder");
    }

    private ApplyEventEmitter emitter() {
        return new ApplyEventEmitter(event -> {
            if (event.event().equals("instance.config-state")) {
                configStates.add(event);
            }
        }, Clock.systemUTC(), UUID::randomUUID);
    }

    /** The bean method {@code AgentGatewayPortsConfiguration#agentAckStartSettler}, called on the configuration itself. */
    private static AckStartSettler realBean(LadderRecorder ladder, ApplyEventEmitter emitter) throws Exception {
        java.lang.reflect.Method bean = AgentGatewayPortsConfiguration.class.getDeclaredMethod("agentAckStartSettler",
                LadderRecorder.class, ApplyEventEmitter.class);
        bean.setAccessible(true);
        return (AckStartSettler) bean.invoke(new AgentGatewayPortsConfiguration(), ladder, emitter);
    }

    private record Rig(AgentEngineWorld.Agent agent, ScopeKey scope, SimulatedAgent sim, AgentEngineWorld.Renderer renderer,
            ConfigLadderReader reader, long generation) {
    }

    private Rig rig() {
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        JdbcLadderRepository ladderRows = new JdbcLadderRepository(w.runtime);
        ConfigRecordServiceImpl config = new ConfigRecordServiceImpl(w.tx, new JdbcConfigGenerationRepository(w.runtime),
                w.hasher, new Retention(10), Clock.systemUTC(), new JdbcRollbackTargetRepository(w.runtime));
        long base = w.runtime.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(scope), Long.class);
        long generation = config.stage(scope.tenant().value(), scope.instance().value(), new StageCommand(base, "cache", "2.4",
                SELECTION, "{\"cache-1\":{\"marker\":\"one\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"one\"")), GenerationOrigin.EDIT, null, ACTOR))
                .generation().generation();
        renderer.byGeneration.put(generation, ("generation: " + generation + "\n").getBytes(StandardCharsets.UTF_8));
        return new Rig(agent, scope, sim, renderer, new ConfigLadderReader(config, ladderRows), generation);
    }

    private static MapSqlParameterSource key(ScopeKey scope) {
        return new MapSqlParameterSource().addValue("t", scope.tenant().value()).addValue("i", scope.instance().value());
    }

    private String storedRung(Rig rig) {
        return w.runtime.queryForObject("SELECT reached_rung FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                key(rig.scope()), String.class);
    }

    /**
     * One request, driven by hand so the ladder's evidence is exact: the generation is written through the agent adapter (its ack
     * carries the container start S0, recorded as the attempt's baseline), the attempt is closed SUCCEEDED with the read-back hash,
     * a LIFECYCLE intent and attempt are opened while the request is still running, the request lands STAGED (so the ladder sees a
     * persisted generation), and only THEN is the RESTART sent through the real adapter. The agent restarts the container (a later
     * start S1 on its own clock) and acknowledges with it; the acknowledgement's settler is the only writer that can move the stored
     * rung (this world's outcome service carries no ladder recorder). Returns the rung the stored cache holds.
     */
    private String applyThenRestart(Rig rig, AckStartSettler settler) {
        w.ackSettler = settler;
        AgentEngineWorld.Path path = w.path(rig.renderer(), rig.sim());
        AttemptService attemptService = new AttemptService(w.tx, w.attempts, new AttemptPolicy(2), w.clock);
        ApplyId apply = w.runningApply(rig.scope(), new ApplyCommand(rig.scope(), com.solace.sm.dp.apply.model.Operation.APPLY,
                rig.generation(), Strategy.MANUAL, null, "operator-1"));
        PlacementRef ref = AgentEngineWorld.ref(rig.scope());
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(rig.scope().placement().value().toString()));
        com.solace.sm.dp.apply.runtime.RenderedConfig config = com.solace.sm.dp.apply.runtime.RenderedConfig.of(rig.scope().tenant(),
                rig.renderer().byGeneration.get(rig.generation()), w.hasher);
        String writeKey = "ack-settler-write:" + UUID.randomUUID();
        StepIntentRow write = w.stepIntents().ensureIntent(rig.scope().tenant(), apply, rig.scope().placement(), Step.MATERIALIZE,
                replica.ref(), writeKey, config.hash());
        AttemptRow writeAttempt = attemptService.open(rig.scope().tenant(), apply, write, null,
                AgentEngineWorld.engineProperties().stepBudget());
        String restartKey = "ack-settler-restart:" + UUID.randomUUID();
        StepIntentRow restartIntent = w.stepIntents().ensureIntent(rig.scope().tenant(), apply, rig.scope().placement(),
                Step.LIFECYCLE, replica.ref(), restartKey, null);
        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(rig.sim())) {
            path.adapter.materializeConfig(replica, rig.generation(), config, new RuntimeOperationContext(apply, write.intentId(),
                    writeKey, writeAttempt.attemptId(), Step.MATERIALIZE));
            assertEquals(1, rig.sim().writeCount(), "the generation was written once");
            // the ack recorded the baseline on this attempt; closing keeps it (coalesce) and records the read-back hash
            attemptService.close(rig.scope().tenant(), writeAttempt.attemptId(),
                    com.solace.sm.dp.apply.model.AttemptOutcome.SUCCEEDED, config.hash(), null, null);
            AttemptRow restartAttempt = attemptService.open(rig.scope().tenant(), apply, restartIntent, null,
                    AgentEngineWorld.engineProperties().stepBudget());
            w.requests.transition(rig.scope().tenant(), apply, com.solace.sm.dp.apply.model.RequestState.RUNNING,
                    com.solace.sm.dp.apply.model.RequestState.STAGED, java.time.Instant.now());
            path.adapter.startOrUpdate(replica, rig.generation(), new RuntimeOperationContext(apply, restartIntent.intentId(),
                    restartKey, restartAttempt.attemptId(), Step.LIFECYCLE));
            assertEquals(1, rig.sim().restartCount(), "the agent restarted the container");
        }
        return storedRung(rig);
    }

    @Test
    void anAgentRestartAckDrivesTheLadderToRestartObservedThroughTheRealSettlerBean() throws Exception {
        Rig rig = rig();
        LadderRecorder ladder = new LadderRecorder(new JdbcLadderRepository(w.runtime));

        String stored = applyThenRestart(rig, realBean(ladder, emitter()));

        assertEquals("restart-observed", stored, "the acknowledged restart start is later than the write's baseline");
        assertEquals(LadderRung.RESTART_OBSERVED, rig.reader().read(rig.scope().tenant().value(), rig.scope().instance().value())
                .reached());
    }

    @Test
    void theSettlerBeanAnnouncesTheMovementOnce() throws Exception {
        Rig rig = rig();
        LadderRecorder ladder = new LadderRecorder(new JdbcLadderRepository(w.runtime));
        ApplyEventEmitter events = emitter();

        applyThenRestart(rig, realBean(ladder, events));

        List<EventEnvelope> mine = configStates.stream()
                .filter(e -> e.entity().instanceId().equals(rig.scope().instance().value().toString())).toList();
        assertTrue(mine.stream().anyMatch(e -> "restart-observed".equals(e.payload().get("reached"))),
                "instance.config-state announced restart-observed: " + mine);
        assertEquals(1, mine.stream().filter(e -> "restart-observed".equals(e.payload().get("reached"))).count(), "once");
    }

    @Test
    void withoutTheSettlerTheSameSequenceLeavesTheStoredRungWhereItWas() {
        Rig rig = rig();

        String stored = applyThenRestart(rig, AckStartSettler.NONE);

        assertTrue(!"restart-observed".equals(stored), "control: with no settler nothing moved the stored rung, was " + stored);
    }
}
