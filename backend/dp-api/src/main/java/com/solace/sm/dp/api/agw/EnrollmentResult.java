package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The gateway-facing enrolment answer, derived by {@code dp-core} from the {@link EnrollmentIssuance}. It carries no
 * tenant (AGW-D3) and no principal: the gateway only forwards certificate material and the agent's own target.
 * {@code toString} prints no certificate bytes.
 */
public record EnrollmentResult(UUID agentId, List<byte[]> certificateChainDer,
        List<byte[]> trustBundlePem, Instant validFrom, Instant validTo, String connectEndpoint,
        UUID environmentId, UUID targetId, EnrollmentEnvelope envelope,
        List<AgentCapability> capabilities, long agentRevocationEpoch, long fleetRevocationEpoch) {
    public EnrollmentResult {
        if (agentId == null) throw new IllegalArgumentException("agentId required");
        certificateChainDer = copy(certificateChainDer);
        trustBundlePem = copy(trustBundlePem);
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        if (agentRevocationEpoch < 0 || fleetRevocationEpoch < 0) {
            throw new IllegalArgumentException("revocation epochs must not be negative");
        }
        if (validFrom == null || validTo == null || connectEndpoint == null || connectEndpoint.isBlank()
                || targetId == null || environmentId == null || envelope == null) {
            throw new IllegalArgumentException("enrollment material is incomplete");
        }
    }

    private static List<byte[]> copy(List<byte[]> source) {
        return (source == null ? List.<byte[]>of() : source).stream().map(x -> Arrays.copyOf(x, x.length)).toList();
    }

    @Override public List<byte[]> certificateChainDer() { return copy(certificateChainDer); }
    @Override public List<byte[]> trustBundlePem() { return copy(trustBundlePem); }

    @Override
    public String toString() {
        return "EnrollmentResult[agentId=" + agentId + ", environmentId=" + environmentId + ", targetId=" + targetId
                + ", connectEndpoint=" + connectEndpoint + ", certificateChainDer=<" + certificateChainDer.size()
                + " certificates>]";
    }
}
