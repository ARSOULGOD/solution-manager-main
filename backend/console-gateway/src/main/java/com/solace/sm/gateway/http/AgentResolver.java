package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.agent.AgentAdministration;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Resolves an operation on one agent to the ENVIRONMENT the agent was enrolled into (plan 015 T4.3, {@code x-sm-scope: named}):
 * {@code getAgent}, {@code revokeAgent} and {@code drainAgent} are authorized at the agent's own environment, which the caller
 * never names. The agent is looked up BY THE SESSION'S ORGANIZATION, so another organization's agent, and an agent that does not
 * exist, are the same {@code 404} (I5).
 *
 * <p>⛔ The id comes from the path: this runs in {@code preHandle}, before any body is read. An agent id that is not a UUID names
 * nothing.
 */
@Component
public final class AgentResolver implements ResourceResolver {

    private final AgentAdministration agents;

    public AgentResolver(AgentAdministration agents) {
        this.agents = Objects.requireNonNull(agents, "agents");
    }

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        java.util.UUID agentId = EnvironmentResolver.pathId(request, "agentId", "agent");
        AgentAdministration.AgentRecord agent = agents.find(session.organizationId(), agentId)
                .orElseThrow(() -> new ResourceNotFoundException("agent"));
        return ResourceRef.of(ResourceKind.ENVIRONMENT, agent.environmentId(), new TenantId(session.organizationId()));
    }
}
