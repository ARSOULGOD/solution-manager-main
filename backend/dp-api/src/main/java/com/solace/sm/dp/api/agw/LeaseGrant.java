package com.solace.sm.dp.api.agw;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A signed lease. {@code placementIds} is the set the DP passed to the issuer — the control plane signs exactly
 * that set and never invents one (AGW-D24); on the wire it travels as {@code binding_ids} (AGW-D25).
 * {@code toString} omits the single-use renewal {@code nonce}.
 */
public record LeaseGrant(UUID agentId, String certificateThumbprint, UUID targetId,
        List<UUID> placementIds, long agentRevocationEpoch, long fleetRevocationEpoch,
        Instant issuedAt, Instant expiresAt, String audience, String nonce,
        List<AgentCapability> capabilities, Map<AgentOperation, Duration> verbCeilings,
        Duration admissionMargin, Instant serverClock) {
    public LeaseGrant {
        placementIds = List.copyOf(placementIds == null ? List.of() : placementIds);
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        verbCeilings = Map.copyOf(verbCeilings == null ? Map.of() : verbCeilings);
        if (agentId == null || targetId == null || certificateThumbprint == null || certificateThumbprint.isBlank()
                || audience == null || audience.isBlank() || serverClock == null
                || nonce == null || nonce.isBlank() || issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("lease identity and lifetime are required");
        }
    }

    @Override
    public String toString() {
        return "LeaseGrant[agentId=" + agentId + ", targetId=" + targetId + ", placementIds=" + placementIds
                + ", agentRevocationEpoch=" + agentRevocationEpoch + ", fleetRevocationEpoch=" + fleetRevocationEpoch
                + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + ", nonce=<redacted>]";
    }
}
