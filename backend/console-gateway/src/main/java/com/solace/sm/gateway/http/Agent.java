package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code Agent}. Carries no token, digest, thumbprint, certificate or key ({@code I6}): the type has no
 * component that could. It has no {@code stateVersion} ({@code OR-38}, as {@link Target}): an agent is not a notify entity, and its
 * session and {@code expired} status change with the connection and the clock, not with a versioned write.
 */
public record Agent(@WireOptional Availability availability, UUID agentId, UUID environmentId,
        @WireOptional UUID targetId, String targetName, Status status, String allowedConfigRoot,
        String allowedContainerPrefix, UUID solutionId, Object packageVersion, List<String> capabilities,
        @WireOptional AgentSession session, String createdAt, @WireOptional String enrolledAt,
        @WireOptional String revokedAt) {

    public enum Status {
        PENDING("pending"), EXPIRED("expired"), ACTIVE("active"), REVOKED("revoked");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public Agent {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
        Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(packageVersion, "packageVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
    }
}
