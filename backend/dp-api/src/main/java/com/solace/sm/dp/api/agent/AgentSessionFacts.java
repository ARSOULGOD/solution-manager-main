package com.solace.sm.dp.api.agent;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the data plane knows about each agent's connection, for the operator surface (plan 015 T4.3, {@code getAgent} and
 * {@code listAgents}). A fact the data plane holds, never a promise: it reads the session row and the active lease, nothing is
 * asked of the agent. Bound by the tenant it is given (I5); an agent that never connected has no entry. Carries no lease nonce,
 * thumbprint or certificate (I6).
 */
public interface AgentSessionFacts {

    /**
     * @param connected      the session row is open and was heard from within the live window
     * @param lastSeenAt     the data plane's clock at the last message
     * @param agentVersion   as the agent declared at {@code SessionOpen}
     * @param leaseExpiresAt the active lease's end; null when none is active
     */
    record Session(boolean connected, Instant lastSeenAt, String agentVersion, Instant leaseExpiresAt) {
        public Session {
            Objects.requireNonNull(lastSeenAt, "lastSeenAt");
            Objects.requireNonNull(agentVersion, "agentVersion");
        }
    }

    /** The sessions of those of {@code agentIds} that belong to {@code tenantId} and have connected at least once. */
    Map<UUID, Session> sessionsOf(UUID tenantId, Collection<UUID> agentIds);
}
