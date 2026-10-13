package com.solace.sm.cp.api.agent;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Signed enrolment material, and everything the data plane needs to create its half of the agent's target (BE-D71):
 * the organization and environment the token was issued in, the catalog solution (its id, its key such as {@code cache},
 * and the one declared package version as canonical JSON text), the CP target row's id, the envelope the issuing
 * principal granted and that principal's id.
 */
public record AgentEnrollmentMaterial(UUID agentId, List<byte[]> certificateChainDer,
        List<byte[]> trustBundlePem, Instant validFrom, Instant validTo, String connectEndpoint,
        AgentRegistration registration, UUID organizationId, UUID environmentId, UUID targetId,
        UUID catalogSolutionId, String solution, String packageVersion,
        String allowedConfigRoot, String allowedContainerPrefix, UUID issuingPrincipal) {
    public AgentEnrollmentMaterial {
        if (agentId == null || validFrom == null || validTo == null || connectEndpoint == null
                || connectEndpoint.isBlank() || registration == null || organizationId == null
                || environmentId == null || targetId == null || catalogSolutionId == null || solution == null
                || solution.isBlank() || packageVersion == null || packageVersion.isBlank() || allowedConfigRoot == null
                || allowedContainerPrefix == null || issuingPrincipal == null) {
            throw new IllegalArgumentException("enrollment material is incomplete");
        }
        certificateChainDer = copy(certificateChainDer);
        trustBundlePem = copy(trustBundlePem);
    }

    private static List<byte[]> copy(List<byte[]> values) {
        return (values == null ? List.<byte[]>of() : values).stream()
                .map(value -> Arrays.copyOf(value, value.length)).toList();
    }

    @Override public List<byte[]> certificateChainDer() { return copy(certificateChainDer); }
    @Override public List<byte[]> trustBundlePem() { return copy(trustBundlePem); }
}
