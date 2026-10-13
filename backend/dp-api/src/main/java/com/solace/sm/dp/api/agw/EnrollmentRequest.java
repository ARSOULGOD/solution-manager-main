package com.solace.sm.dp.api.agw;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One enrolment call. {@code token} is the single-use enrolment secret and {@code csrDer} the agent's signing
 * request: {@code toString} prints neither (Global constraint 9, I6). {@code origin} is gateway-minted.
 */
public record EnrollmentRequest(String requestId, String token, byte[] csrDer, String agentVersion,
        List<Integer> protocolVersions, String hostOs, String hostArch, String runtimeKind,
        AgentRequestOrigin origin) {
    public EnrollmentRequest {
        if (requestId == null || requestId.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("requestId and token are required");
        }
        if (csrDer == null || csrDer.length == 0 || csrDer.length > 128 * 1024) {
            throw new IllegalArgumentException("CSR is empty or too large");
        }
        Objects.requireNonNull(origin, "origin");
        csrDer = Arrays.copyOf(csrDer, csrDer.length);
        protocolVersions = List.copyOf(protocolVersions == null ? List.of() : protocolVersions);
    }

    @Override public byte[] csrDer() { return Arrays.copyOf(csrDer, csrDer.length); }

    @Override
    public String toString() {
        return "EnrollmentRequest[requestId=" + requestId + ", agentVersion=" + agentVersion + ", origin=" + origin
                + ", token=<redacted>, csrDer=<redacted>]";
    }
}
