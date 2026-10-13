package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Set;

/**
 * The facts of one {@code SessionOpen} the DP needs at claim time. All agent-supplied; none selects a tenant (I5).
 *
 * @param protocolVersion the wire protocol version ({@code agent_session.protocol_version}, NOT NULL)
 * @param agentVersion    the agent build ({@code agent_session.agent_version}, NOT NULL)
 * @param features        what the agent understands; decides, e.g., that REMATERIALIZE is unsupported
 * @param agentClock      the agent's clock at open: OR-134 F-6 measures the clock offset here, because on a reconnect
 *                        with a valid lease {@code issue} is never called
 */
public record SessionOpening(int protocolVersion, String agentVersion, Set<AgentFeature> features,
        Instant agentClock) {
    public SessionOpening {
        if (agentVersion == null) {
            agentVersion = "";
        }
        features = features == null ? Set.of() : Set.copyOf(features);
        if (agentClock == null) {
            throw new IllegalArgumentException("agentClock is required");
        }
    }
}
