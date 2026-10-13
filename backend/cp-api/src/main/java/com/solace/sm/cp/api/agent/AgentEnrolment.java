package com.solace.sm.cp.api.agent;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The principal-facing half of enrolment: issuing a token (plan 015 T2.2; the HTTP surface is T4.3). The agent's own
 * half is {@link ControlPlaneAgentGateway#enroll}.
 */
public interface AgentEnrolment {

    /**
     * Issues a single-use enrolment token for an agent target. The caller must hold {@code agent.enrol} and
     * {@code target.create} at {@code request.environment()} (OR-128 (4)); both are checked before anything is
     * written. One audited write ({@code AGENT_ENROLMENT_ISSUED}) names the issuing principal, who also becomes the
     * registration's {@code created_by}.
     *
     * @param organizationId the authenticated principal's own organization (I5)
     * @throws com.solace.sm.cp.api.authz.AccessDeniedException a verb is not held at the environment
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException the environment is not in the organization
     * @throws IllegalArgumentException the request is malformed or the envelope is outside the grammars
     * @throws IllegalStateException the target name is already taken or reserved
     */
    IssuedToken issue(PrincipalRef actor, UUID organizationId, Request request, RequestOrigin origin);

    /**
     * @param environment      an environment path (organization, unit, environment)
     * @param packageVersion   one loaded version of the solution as canonical JSON (a number or a string)
     * @param validFor         the token's window; null takes the default
     */
    record Request(ScopePath environment, UUID catalogSolutionId, String packageVersion, String targetName,
            String allowedConfigRoot, String allowedContainerPrefix, Duration validFor) {
        public Request {
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
            Objects.requireNonNull(packageVersion, "packageVersion");
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
            Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
        }
    }

    /** The token is shown exactly once; {@code toString} omits it. */
    record IssuedToken(UUID agentId, String token, Instant expiresAt) {
        public IssuedToken {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public String toString() {
            return "IssuedToken[agentId=" + agentId + ", expiresAt=" + expiresAt + ", token=<redacted>]";
        }
    }
}
