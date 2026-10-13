package com.solace.sm.cp.api.agent;

import com.solace.sm.cp.api.audit.RequestOrigin;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One enrolment. The token is never persisted or returned by this API, and neither it nor the CSR is printed.
 * {@code origin} is the gateway-minted correlation id and the gRPC peer address (plan 015 P15-8): the control
 * plane's audit row for the consumption carries both, with the agent as actor.
 */
public record AgentEnrollmentRequest(String token, byte[] csrDer, String agentVersion,
        List<Integer> protocolVersions, String hostOs, String hostArch, String runtimeKind, RequestOrigin origin) {
    public AgentEnrollmentRequest {
        if (token == null || token.isBlank() || csrDer == null || csrDer.length == 0) {
            throw new IllegalArgumentException("token and CSR are required");
        }
        Objects.requireNonNull(origin, "origin");
        csrDer = Arrays.copyOf(csrDer, csrDer.length);
        protocolVersions = List.copyOf(protocolVersions == null ? List.of() : protocolVersions);
    }

    @Override public byte[] csrDer() { return Arrays.copyOf(csrDer, csrDer.length); }

    @Override
    public String toString() {
        return "AgentEnrollmentRequest[agentVersion=" + agentVersion + ", origin=" + origin
                + ", token=<redacted>, csrDer=<redacted>]";
    }
}
