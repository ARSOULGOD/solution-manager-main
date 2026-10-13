package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code AgentEnrolment}: what {@code issueAgentEnrolment} answers, ONCE. {@code enrollmentToken}
 * is {@code readOnly} and appears in no other response, log, audit row or metric ({@code I6}); {@code toString} omits it.
 */
public record AgentEnrolment(UUID agentId, @JsonProperty(access = JsonProperty.Access.READ_ONLY) String enrollmentToken,
        String expiresAt, UUID environmentId, String targetName, String allowedConfigRoot,
        String allowedContainerPrefix, UUID solutionId, Object packageVersion) {

    public AgentEnrolment {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(enrollmentToken, "enrollmentToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
        Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(packageVersion, "packageVersion");
    }

    @Override
    public String toString() {
        return "AgentEnrolment[agentId=" + agentId + ", expiresAt=" + expiresAt + ", enrollmentToken=[REDACTED]]";
    }
}
