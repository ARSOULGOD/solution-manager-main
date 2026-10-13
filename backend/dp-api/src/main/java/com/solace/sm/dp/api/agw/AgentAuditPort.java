package com.solace.sm.dp.api.agw;

import java.time.Instant;

public interface AgentAuditPort {
    /** {@code origin} supplies {@code agent_event.correlation_id} and {@code client_address} (NOT NULL). */
    void record(String requestId, AgentSessionRef session, AgentRequestOrigin origin, String eventType,
            GatewayCode code, String subject, Instant at);
}
