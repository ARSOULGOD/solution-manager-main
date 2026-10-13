package com.solace.sm.cp.api.governance;

import com.solace.sm.cp.api.audit.RequestOrigin;
import java.util.UUID;

/**
 * The control plane's half of {@code OR-27} when the registering party is an AGENT, not a principal (plan 015 T4.2,
 * {@code BE-D70}): the same governance node, quota and entitlement as {@link InstanceGovernance#register}, written under
 * {@code AGENT_INSTANCE_REGISTERED} with the agent as actor and NO principal. The database refuses an agent actor on any other
 * operation and scopes this one to INSERTs into the agent's own enrolled environment and catalog solution.
 *
 * <p>A separate interface so that the principal-facing {@link InstanceGovernance} is unchanged and no existing implementation
 * must grow a method. The one implementation is {@code GovernanceService}.
 */
public interface AgentInstanceGovernance {

    /**
     * One CP transaction: the environment's Solution node on its first declare ({@code OR-41}), the instance node and their scope
     * rows, the quota enforced ({@code OR-40}) under {@code FOR UPDATE} of the entitlement row.
     *
     * @param agentId  the registering agent, resolved by the data plane from its identity - never from the report
     * @param origin   the gateway-minted origin of the RPC: its correlation id and client address land in {@code change_audit}
     * @throws com.solace.sm.cp.api.catalog.EntitlementRefusedException the organization holds no entitlement for the solution
     * @throws QuotaExceededException                                   the entitlement's instance limit is reached
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException     the environment or the catalog entry is not the organization's
     */
    GovernanceRegistration registerForAgent(UUID agentId, RequestOrigin origin, UUID organizationId, UUID environmentId,
            UUID catalogSolutionId, UUID instanceId);
}
