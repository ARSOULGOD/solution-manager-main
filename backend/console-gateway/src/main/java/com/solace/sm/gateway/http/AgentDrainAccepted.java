package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/** Wire shape of console.yaml's {@code AgentDrainAccepted}: the drain was sent to the live session; it is not complete. */
public record AgentDrainAccepted(UUID agentId, String requestedAt) {

    public AgentDrainAccepted {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
