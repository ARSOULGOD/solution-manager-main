package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T2.1b fix-round note (c), decided and tested in T3.1: {@code V029}'s retention deletes final commands older than 30 days EXCEPT
 * the highest generation of each {@code (agent, placement)}. An apply resumed after that gap whose intent key matches a deleted
 * SUCCEEDED command therefore mints a NEW command (a new wire generation) instead of being handed the old success: the host
 * operation runs AGAIN. This pins that edge, so nobody mistakes the idempotency of {@code enqueue} for a promise that outlives the
 * retention window. (An apply that old is long terminal; the engine never resumes it. Only an operator-forced re-drive can reach it.)
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentCommandRetentionEdgeIT {

    private static AgentEngineWorld w;

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_retention_edge");
    }

    @Test
    void aSucceededCommandPurgedByRetentionIsReExecutedAsANewGenerationNotReturnedAsADoneOne() {
        UUID tenantId = UUID.randomUUID();
        TenantId tenant = new TenantId(tenantId);
        AgentEngineWorld.Agent agent = w.enrol(tenantId);
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        AgentEngineWorld.Path path = w.path(renderer, sim);
        PlacementRef ref = AgentEngineWorld.ref(scope);
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(scope.placement().value().toString()));
        RenderedConfig config = RenderedConfig.of(tenant, "retained: config\n".getBytes(StandardCharsets.UTF_8), w.hasher);
        ApplyId apply = w.runningApply(scope, new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.RESTART_ALL, "operator-1",
                "operator-1"));
        RuntimeOperationContext materialize = w.context(tenant, apply, Step.MATERIALIZE, scope, replica, config.hash());
        RuntimeOperationContext restart = w.context(tenant, apply, Step.LIFECYCLE, scope, replica, null);

        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(sim)) {
            path.adapter.materializeConfig(replica, 7, config, materialize);             // wire generation 1
            path.adapter.startOrUpdate(replica, 7, restart);                             // wire generation 2: the highest
            path.adapter.materializeConfig(replica, 7, config, materialize);             // the same key again: the done one
            assertEquals(1, sim.writeCount(), "inside the window the success is handed back and nothing re-runs");
            assertEquals(1, generationsOf(tenantId, materialize.intentKey(), "APPLY").size(), "one command row for the key");

            // 31 days pass: the first command is final and old, and it is not the highest generation of the placement
            ownerJdbc().update("UPDATE sm_data.agent_command SET acked_at = now() - interval '31 days', created_at = now() - interval '31 days'"
                    + " WHERE tenant_id = :t AND intent_key = :k AND operation = 'APPLY'", new MapSqlParameterSource()
                    .addValue("t", tenantId).addValue("k", materialize.intentKey()));
            w.runtime.queryForList("SELECT * FROM sm_data.agent_gateway_maintenance()", new MapSqlParameterSource());
            assertEquals(0, generationsOf(tenantId, materialize.intentKey(), "APPLY").size(), "retention deleted the old success");

            path.adapter.materializeConfig(replica, 7, config, materialize);
        }

        assertEquals(2, sim.writeCount(), "the resumed step RE-EXECUTED: the old success was gone");
        long generation = generationsOf(tenantId, materialize.intentKey(), "APPLY").get(0);
        assertNotEquals(1L, generation, "…under a NEW wire generation, never reusing the purged number");
        assertEquals(3L, generation);
    }

    private NamedParameterJdbcTemplate ownerJdbc() {
        return new NamedParameterJdbcTemplate(w.db.superuser());
    }

    private java.util.List<Long> generationsOf(UUID tenant, String intentKey, String operation) {
        return w.runtime.queryForList("SELECT wire_generation FROM sm_data.agent_command WHERE tenant_id = :t AND intent_key = :k"
                + " AND operation = :o ORDER BY wire_generation", new MapSqlParameterSource().addValue("t", tenant)
                .addValue("k", intentKey).addValue("o", operation), Long.class);
    }
}
