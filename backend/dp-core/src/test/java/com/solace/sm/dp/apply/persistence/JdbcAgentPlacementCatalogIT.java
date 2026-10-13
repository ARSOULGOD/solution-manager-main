package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the router and the agent adapter ask the registry (plan 015 T3.1): which transport reaches a placement's target, and which
 * agent owns an agent placement. Everything is bound by {@code (tenant, instance, placement)} (I5): a placement id guessed under
 * another tenant or instance resolves nothing, and the agent is the one the TARGET names.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentPlacementCatalogIT {

    private static AgentEngineWorld w;

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_catalog");
    }

    @Test
    void theTransportIsTheTargetsAndAnAgentPlacementResolvesToTheAgentItsTargetNames() {
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ScopeKey agentScope = w.agentScope(agent);
        ScopeKey sshScope = w.sshScope(tenant);

        assertEquals(Optional.of(PlacementTransports.AGENT), w.catalog.transportOf(AgentEngineWorld.ref(agentScope)));
        assertEquals(Optional.of(PlacementTransports.SSH_DOCKER), w.catalog.transportOf(AgentEngineWorld.ref(sshScope)));

        AgentPlacementCatalog.AgentPlacement resolved = w.catalog.resolve(AgentEngineWorld.ref(agentScope)).orElseThrow();
        assertEquals(agent.agentId(), resolved.agentId());
        assertEquals(AgentEngineWorld.agentRef(agentScope), resolved.ref());
        assertEquals("application.yml", resolved.location().fileName());
        assertEquals("/app/external/spring/config", resolved.location().containerPath());
        assertTrue(w.catalog.resolve(AgentEngineWorld.ref(sshScope)).isEmpty(), "a dp-remote placement is not an agent's");
    }

    @Test
    void aPlacementIsNeverResolvedUnderAnotherTenantOrInstance() {
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        ScopeKey scope = w.agentScope(agent);
        PlacementRef real = AgentEngineWorld.ref(scope);

        PlacementRef otherTenant = new PlacementRef(new TenantId(UUID.randomUUID()), real.instance(), real.placement());
        PlacementRef otherInstance = new PlacementRef(real.tenant(), new InstanceId(UUID.randomUUID()), real.placement());
        PlacementRef unknown = new PlacementRef(real.tenant(), real.instance(), new PlacementId(UUID.randomUUID()));

        for (PlacementRef guess : new PlacementRef[] {otherTenant, otherInstance, unknown}) {
            assertTrue(w.catalog.resolve(guess).isEmpty(), "resolved " + guess);
            assertTrue(w.catalog.transportOf(guess).isEmpty(), "transport of " + guess);
        }
        assertTrue(w.catalog.isAgentTarget(agent.tenant(), agent.targetId()));
        assertFalse(w.catalog.isAgentTarget(UUID.randomUUID(), agent.targetId()), "bound by tenant");
    }

    @Test
    void aRetiredPlacementIsNotResolvedForTheAgentButStillHasATransport() {
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        ScopeKey scope = w.agentScope(agent);
        w.runtime.update("UPDATE sm_data.placement SET retired_at = now(), retired_by = :u WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", agent.tenant()).addValue("p", scope.placement().value())
                        .addValue("u", UUID.randomUUID()));

        assertTrue(w.catalog.resolve(AgentEngineWorld.ref(scope)).isEmpty(), "the agent adapter refuses a retired placement");
        assertEquals(Optional.of(PlacementTransports.AGENT), w.catalog.transportOf(AgentEngineWorld.ref(scope)),
                "…but routing it is the table's answer, so it reaches the agent adapter and is refused THERE, never by SSH");
    }
}
