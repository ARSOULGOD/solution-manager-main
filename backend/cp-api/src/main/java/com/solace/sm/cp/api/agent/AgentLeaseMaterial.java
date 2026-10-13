package com.solace.sm.cp.api.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A signed lease's claims. {@code nonce} is single-use renewal material and is not printed. */
public record AgentLeaseMaterial(UUID agentId, String certificateThumbprint, UUID targetId,
        List<UUID> placementIds, long agentRevocationEpoch, long fleetRevocationEpoch,
        Instant issuedAt, Instant expiresAt, String audience, String nonce,
        List<String> capabilities, Map<String, Duration> verbCeilings,
        Duration admissionMargin, Instant serverClock) {
    public AgentLeaseMaterial {
        placementIds = List.copyOf(placementIds == null ? List.of() : placementIds);
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        verbCeilings = Map.copyOf(verbCeilings == null ? Map.of() : verbCeilings);
        if (agentId == null || nonce == null || nonce.isBlank() || issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("lease identity and lifetime are required");
        }
    }

    @Override
    public String toString() {
        return "AgentLeaseMaterial[agentId=" + agentId + ", targetId=" + targetId + ", placementIds=" + placementIds
                + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + ", nonce=<redacted>]";
    }
}
