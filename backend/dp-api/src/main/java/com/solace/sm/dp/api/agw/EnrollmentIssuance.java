package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The issuer's (control plane's) answer to an enrolment: the certificate material <em>and</em> everything the DP
 * persists on {@code agent_identity} and the agent target. It is internal to the DP/CP seam
 * ({@link EnrollmentCertificateIssuer}); the gateway only ever sees the {@link EnrollmentResult} {@code dp-core}
 * derives from it, which carries no tenant. {@code issuingPrincipalId} is the principal that minted the enrolment
 * token (T3.5).
 */
public record EnrollmentIssuance(UUID tenantId, UUID catalogSolutionId, String solution, String declaredPackageVersion,
        UUID environmentId, UUID targetId, UUID agentId, List<byte[]> certificateChainDer,
        List<byte[]> trustBundlePem, Instant validFrom, Instant validTo, String connectEndpoint,
        EnrollmentEnvelope envelope, UUID issuingPrincipalId, List<AgentCapability> capabilities,
        long agentRevocationEpoch, long fleetRevocationEpoch) {
    public EnrollmentIssuance {
        if (tenantId == null || catalogSolutionId == null || solution == null || solution.isBlank()
                || declaredPackageVersion == null || declaredPackageVersion.isBlank() || environmentId == null
                || targetId == null || agentId == null || envelope == null || issuingPrincipalId == null
                || validFrom == null || validTo == null || connectEndpoint == null || connectEndpoint.isBlank()) {
            throw new IllegalArgumentException("enrollment issuance is incomplete");
        }
        if (agentRevocationEpoch < 0 || fleetRevocationEpoch < 0) {
            throw new IllegalArgumentException("revocation epochs must not be negative");
        }
        certificateChainDer = copy(certificateChainDer);
        trustBundlePem = copy(trustBundlePem);
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
    }

    private static List<byte[]> copy(List<byte[]> source) {
        return (source == null ? List.<byte[]>of() : source).stream().map(x -> Arrays.copyOf(x, x.length)).toList();
    }

    @Override public List<byte[]> certificateChainDer() { return copy(certificateChainDer); }
    @Override public List<byte[]> trustBundlePem() { return copy(trustBundlePem); }

    @Override
    public String toString() {
        return "EnrollmentIssuance[tenantId=" + tenantId + ", solution=" + solution + ", environmentId=" + environmentId
                + ", targetId=" + targetId + ", agentId=" + agentId + ", issuingPrincipalId=" + issuingPrincipalId
                + ", certificateChainDer=<" + certificateChainDer.size() + " certificates>]";
    }
}
