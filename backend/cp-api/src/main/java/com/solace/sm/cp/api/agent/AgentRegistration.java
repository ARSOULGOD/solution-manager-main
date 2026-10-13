package com.solace.sm.cp.api.agent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The control plane's enrolment and authorization facts for one agent (plan 015 T2.2, {@code AGW-D24}). It carries no
 * placement set: the control plane holds no placement, so a lease's placements are supplied by the data plane per
 * request and signed as given. {@code targetId} is {@code null} until the token is consumed.
 *
 * @param capabilities the closed vocabulary the registration stores ({@code apply-config}, {@code lifecycle},
 *                     {@code report-only}); the launcher adapter maps it to the data plane's enum
 */
public record AgentRegistration(UUID agentId, UUID targetId, List<String> capabilities, long agentRevocationEpoch,
        long fleetRevocationEpoch, Duration leaseTtl, String audience, Duration admissionMargin, boolean active) {
    public AgentRegistration {
        if (agentId == null) {
            throw new IllegalArgumentException("agent identity is required");
        }
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()
                || admissionMargin == null || admissionMargin.isNegative()) {
            throw new IllegalArgumentException("lease timing is invalid");
        }
    }
}
