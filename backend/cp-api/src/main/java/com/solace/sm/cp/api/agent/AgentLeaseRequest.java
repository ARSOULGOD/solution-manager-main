package com.solace.sm.cp.api.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A lease request. {@code placementIds} is the set the DATA PLANE derived and passes in (AGW-D24): the control plane
 * holds no placement and signs exactly this set, never one it chose. {@code organizationId} is the organization the DP
 * resolved from the agent's certificate (I5); the registration read binds it. {@code certificateThumbprint} is the
 * lowercase hex SHA-256 of the leaf the agent presented and must be the one issued. {@code toString} omits the
 * single-use {@code consumedNonce}.
 */
public record AgentLeaseRequest(UUID organizationId, UUID agentId, UUID targetId, String certificateThumbprint,
        long agentRevocationEpoch, long fleetRevocationEpoch, Instant agentClock,
        String requestId, String consumedNonce, List<UUID> placementIds) {
    public AgentLeaseRequest {
        if (organizationId == null || agentId == null || targetId == null || requestId == null || requestId.isBlank()
                || agentClock == null || certificateThumbprint == null || certificateThumbprint.isBlank()) {
            throw new IllegalArgumentException("lease identity and request are required");
        }
        placementIds = List.copyOf(placementIds == null ? List.of() : placementIds);
    }

    @Override
    public String toString() {
        return "AgentLeaseRequest[organizationId=" + organizationId + ", agentId=" + agentId + ", targetId=" + targetId
                + ", agentRevocationEpoch=" + agentRevocationEpoch + ", fleetRevocationEpoch=" + fleetRevocationEpoch
                + ", requestId=" + requestId + ", placementIds=" + placementIds + ", consumedNonce=<redacted>]";
    }
}
