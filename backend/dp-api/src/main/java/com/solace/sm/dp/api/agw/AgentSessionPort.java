package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface AgentSessionPort {
    /**
     * Refuses a revoked or unknown thumbprint. The ref carries no tenant; {@code dp-core} derives it.
     * <p>The implementation must persist, on the session row: {@code opening.protocolVersion()} and
     * {@code opening.agentVersion()} (NOT NULL columns), {@code opening.features()} (a new {@code features text[]}
     * column) and the clock offset {@code receivedAt - opening.agentClock()} (a new {@code clock_offset_ms bigint}
     * column). Those two columns do not exist in the published V028: they arrive in a new {@code sm_data} migration
     * with T2.1b.
     */
    AgentSessionRef claim(String certificateThumbprint, SessionOpening opening, AgentRequestOrigin origin);

    /**
     * {@code agentClock} is the <em>agent's</em> clock, for the offset measurement only. The DP stamps
     * {@code last_seen_at} with its own clock; a skewed or hostile agent clock never moves it.
     */
    void heartbeat(AgentSessionRef ref, Instant agentClock);

    void close(AgentSessionRef ref, CloseReason reason);

    /** The adapter's fail-fast read (BE-D66): the agent has an open session AND an unexpired lease holding the placement. */
    boolean liveFor(UUID agentId, AgentPlacementRef placement);

    /** The features announced by the live session that holds the placement; empty when there is none. */
    Set<AgentFeature> featuresOf(UUID agentId, AgentPlacementRef placement);
}
