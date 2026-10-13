package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agent.AgentSessionFacts;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CloseReason;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T4.3: what {@code getAgent} and {@code listAgents} say about an agent's connection is read from the session row and the
 * ACTIVE lease, bound by tenant, against the real schema.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentSessionFactsIT {

    private final AgentJdbcWorld world = new AgentJdbcWorld();
    private final AgentSessionFacts facts = new JdbcAgentSessionFacts(world.runtimeJdbc(), world.clock(), Duration.ofMinutes(2));

    @Test
    void anAgentThatNeverConnectedHasNoEntry() {
        AgentKitWorld.Agent agent = world.enrol();
        assertTrue(facts.sessionsOf(agent.tenantId(), List.of(agent.agentId())).isEmpty());
        assertTrue(facts.sessionsOf(agent.tenantId(), List.of()).isEmpty());
    }

    @Test
    void aConnectedAgentCarriesItsVersionLastSeenAndTheActiveLeasesEnd() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.open(agent);

        AgentSessionFacts.Session opened = facts.sessionsOf(agent.tenantId(), List.of(agent.agentId())).get(agent.agentId());
        assertTrue(opened.connected());
        assertEquals("0.1.0", opened.agentVersion());
        assertNull(opened.leaseExpiresAt(), "no lease yet");

        world.lease(agent, session);
        AgentSessionFacts.Session leased = facts.sessionsOf(agent.tenantId(), List.of(agent.agentId())).get(agent.agentId());
        assertNotNull(leased.leaseExpiresAt());
        assertTrue(leased.leaseExpiresAt().isAfter(world.now()));
    }

    @Test
    void aSessionNotHeardFromWithinTheWindowOrClosedIsNotConnectedButIsStillReported() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.open(agent);
        world.advance(Duration.ofMinutes(3));
        AgentSessionFacts.Session quiet = facts.sessionsOf(agent.tenantId(), List.of(agent.agentId())).get(agent.agentId());
        assertFalse(quiet.connected(), "a row that predates the live window is a fact about the past");
        assertNotNull(quiet.lastSeenAt());

        AgentKitWorld.Agent other = world.enrol();
        AgentSessionRef otherSession = world.open(other);
        world.sessions().close(otherSession, CloseReason.STREAM_CLOSED);
        assertFalse(facts.sessionsOf(other.tenantId(), List.of(other.agentId())).get(other.agentId()).connected());
    }

    @Test
    void theReadIsBoundByTenantSoAnotherTenantsAgentIsAbsent() {
        AgentKitWorld.Agent agent = world.enrol();
        world.open(agent);
        Map<UUID, AgentSessionFacts.Session> foreign = facts.sessionsOf(UUID.randomUUID(), List.of(agent.agentId()));
        assertTrue(foreign.isEmpty());
        assertEquals(1, facts.sessionsOf(agent.tenantId(), List.of(agent.agentId(), UUID.randomUUID())).size());
    }
}
