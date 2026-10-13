package com.solace.sm.dp.api.agw;

import java.util.Objects;
import java.util.UUID;

/**
 * DP-issued opaque handle. Tenant scope is deliberately absent ({@code AGW-D3}): {@code dp-core} derives the
 * tenant from the agent's identity and never accepts one from the gateway.
 */
public record AgentSessionRef(UUID agentId, long fencingEpoch) {
    public AgentSessionRef {
        Objects.requireNonNull(agentId, "agentId");
        if (fencingEpoch < 1) {
            throw new IllegalArgumentException("fencingEpoch must be positive");
        }
    }
}
