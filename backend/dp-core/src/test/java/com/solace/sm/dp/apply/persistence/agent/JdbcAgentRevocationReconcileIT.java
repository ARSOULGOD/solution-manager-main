package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 Final review MAJOR-2: the agent gateway answers a STALE_EPOCH write by asking {@code reconcile(session)} and sends Revoke
 * only on {@code REVOKED} ({@code AgentGatewayService.supersedeOrRevoke}). Over the REAL identity and session repositories, this pins
 * the invariant that makes that work: once the data plane has revoked an agent that held a claimed session (the revocation closes
 * the session and marks the identity in ONE transaction), the session's next write is refused {@code STALE_EPOCH}, and reconciling
 * that same session afterwards still answers {@code REVOKED}, never {@code STALE}. A session closed WITHOUT a revocation is STALE.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentRevocationReconcileIT {

    private final AgentJdbcWorld world = new AgentJdbcWorld();

    private IdentityReconciliation reconcile(AgentSessionRef session) {
        List<IdentityReconciliation> out = world.identities().reconcile(List.of(session));
        assertEquals(1, out.size());
        return out.get(0);
    }

    private void aWriteMeetsTheClosedSession(AgentSessionRef session) {
        GatewayPortException refused = assertThrows(GatewayPortException.class,
                () -> world.commands().pendingFor(session, 10, Duration.ofSeconds(15)));
        assertEquals(GatewayCode.STALE_EPOCH, refused.code(), "the in-flight write meets the closed session");
    }

    @Test
    void aRevocationFromTheReconcilePassLeavesTheSessionReconcilingAsRevoked() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        world.controlPlane(agent, false, 1, 0, List.<AgentCapability>of());      // the control plane revokes the registration

        assertEquals(IdentityReconciliation.Status.REVOKED, reconcile(session).status(), "the pass that applies it");
        assertTrue(world.identityRevoked(agent));
        aWriteMeetsTheClosedSession(session);
        assertEquals(IdentityReconciliation.Status.REVOKED, reconcile(session).status(),
                "the gateway's follow-up reconcile after a STALE_EPOCH write: REVOKED, so Revoke is sent, not the supersede");
    }

    @Test
    void aRevocationFromTheRetireGateLeavesTheSessionReconcilingAsRevoked() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        world.controlPlane(agent, false, 1, 0, List.<AgentCapability>of());

        assertTrue(((JdbcAgentIdentityRepository) world.identities())
                .revokedApplyingTheControlPlane(agent.tenantId(), agent.agentId()), "the retire gate applies the revocation");
        aWriteMeetsTheClosedSession(session);
        assertEquals(IdentityReconciliation.Status.REVOKED, reconcile(session).status());
    }

    @Test
    void aSessionClosedWithoutARevocationReconcilesAsStale() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        world.ownerJdbc().update("UPDATE sm_data.agent_session SET closed_at = now() WHERE agent_id = :a",
                new MapSqlParameterSource("a", agent.agentId()));

        assertEquals(IdentityReconciliation.Status.STALE, reconcile(session).status(), "closed, identity in good standing: supersede");
    }
}
