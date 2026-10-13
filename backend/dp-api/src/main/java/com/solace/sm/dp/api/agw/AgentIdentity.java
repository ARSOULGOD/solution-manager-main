package com.solace.sm.dp.api.agw;

import java.util.List;
import java.util.UUID;

/**
 * An enrolled agent as the DP knows it. {@code tenantId}, {@code environmentId} and {@code targetId} are
 * <b>resolved by the DP</b> from the certificate thumbprint ({@code resolveByThumbprint}, the one named unbound
 * read, I5) and are never sent by the agent. The placements the agent may serve are not here: the DP derives that
 * set when it issues or renews a lease ({@code AGW-D24}).
 */
public record AgentIdentity(UUID agentId, String certificateThumbprint, UUID tenantId, UUID environmentId,
        UUID targetId, long agentRevocationEpoch, long fleetRevocationEpoch,
        List<AgentCapability> capabilities, boolean revoked) {
    public AgentIdentity {
        if (agentId == null || targetId == null || tenantId == null || environmentId == null) {
            throw new IllegalArgumentException("agent, tenant, environment and target identity are required");
        }
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
    }
}
