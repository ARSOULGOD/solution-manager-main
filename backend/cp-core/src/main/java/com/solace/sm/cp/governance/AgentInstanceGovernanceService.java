package com.solace.sm.cp.governance;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.governance.AgentInstanceGovernance;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import java.util.Objects;
import java.util.UUID;

/**
 * The agent-actor form of the governance registration (plan 015 T4.2), as a class of its own so the Spring context holds exactly
 * one bean of {@code InstanceGovernance} and exactly one of {@code AgentInstanceGovernance}: both would otherwise be the same
 * object and every injection of either would be ambiguous. All behaviour is {@link GovernanceService#registerForAgent}.
 */
public final class AgentInstanceGovernanceService implements AgentInstanceGovernance {

    private final GovernanceService delegate;

    public AgentInstanceGovernanceService(GovernanceService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public GovernanceRegistration registerForAgent(UUID agentId, RequestOrigin origin, UUID organizationId, UUID environmentId,
            UUID catalogSolutionId, UUID instanceId) {
        return delegate.registerForAgent(agentId, origin, organizationId, environmentId, catalogSolutionId, instanceId);
    }
}
