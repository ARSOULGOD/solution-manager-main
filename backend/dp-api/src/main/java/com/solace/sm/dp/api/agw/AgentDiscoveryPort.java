package com.solace.sm.dp.api.agw;

import java.util.List;

/**
 * A complete snapshot of the host's containers (never a delta). The tenant is resolved by the DP from the session's
 * identity. {@code requestId} is the wire message id ({@code agent_event.request_id}, NOT NULL and UNIQUE: a replay
 * of the same report is idempotent). T2.1b's stub answers every item REFUSED with
 * {@link GatewayCode#AGENT_DISCOVERY_UNAVAILABLE}; T4.1 implements. Returns one result per item, in the report's order.
 */
public interface AgentDiscoveryPort {
    List<DiscoveryResult> report(AgentSessionRef ref, String requestId, List<DiscoveredContainer> items,
            AgentRequestOrigin origin);
}
