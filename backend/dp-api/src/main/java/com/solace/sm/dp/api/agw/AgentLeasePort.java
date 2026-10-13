package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Optional;

public interface AgentLeasePort {
    Optional<LeaseGrant> current(AgentSessionRef session);

    LeaseGrant issue(String requestId, AgentSessionRef session, Instant agentClock, AgentRequestOrigin origin);

    LeaseGrant renew(String requestId, AgentSessionRef session, String consumedNonce, Instant agentClock,
            AgentRequestOrigin origin);
}
