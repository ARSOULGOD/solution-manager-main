package com.solace.sm.dp.api.agw;

import java.util.List;
import java.util.UUID;

/** The control plane's current view of one agent registration. Carries no {@code binding_ids} (AGW-D24). */
public record AgentRegistrationState(UUID agentId, boolean active, UUID targetId,
        List<AgentCapability> capabilities, long agentRevocationEpoch, long fleetRevocationEpoch) {
    public AgentRegistrationState {
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
    }
}
