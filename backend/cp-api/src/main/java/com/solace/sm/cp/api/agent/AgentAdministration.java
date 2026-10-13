package com.solace.sm.cp.api.agent;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The operator-facing reads and the one write on an agent's control-plane record (plan 015 T4.3, D10 rows 44-47:
 * {@code listAgents}, {@code getAgent}, {@code revokeAgent}). Issuing the token is {@link AgentEnrolment}'s.
 *
 * <p>Every method is bound by the organization it is given (I5). An agent of another organization is {@link Optional#empty()}:
 * absent and out of scope are not distinguished. Nothing here returns a token, a digest, a thumbprint, a certificate or a key
 * (I6): {@link AgentRecord} has no field that could carry one.
 */
public interface AgentAdministration {

    /** The lifecycle the registration is in, as the console names it ({@code pending}, {@code expired}, {@code active}, {@code revoked}). */
    enum Status { PENDING, EXPIRED, ACTIVE, REVOKED }

    /**
     * @param packageVersion the one loaded version the agent's instances are declared at, as canonical JSON (a number or a string)
     * @param enrolledAt     null until the token is consumed
     * @param revokedAt      null unless revoked
     */
    record AgentRecord(UUID agentId, UUID environmentId, UUID targetId, String targetName, Status status,
            String allowedConfigRoot, String allowedContainerPrefix, UUID catalogSolutionId, String packageVersion,
            List<String> capabilities, Instant createdAt, Instant enrolledAt, Instant revokedAt) {
        public AgentRecord {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(environmentId, "environmentId");
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        }
    }

    /**
     * The stored scope path (organization, unit, environment) of an environment of this organization, which {@link AgentEnrolment.Request}
     * carries; empty when the environment is unknown in it. Another organization's environment is empty exactly as an absent one is (I5).
     */
    Optional<ScopePath> environmentPath(UUID organizationId, UUID environmentId);

    /** Every agent of the organization, revoked and expired ones included, oldest first. The caller filters by reach. */
    List<AgentRecord> list(UUID organizationId);

    /** One agent of the organization, or empty when it is unknown in it. */
    Optional<AgentRecord> find(UUID organizationId, UUID agentId);

    /**
     * Revokes the agent: sticky, idempotent (revoking a revoked agent changes and audits nothing), and allowed on a token never used
     * (which releases its target name). One audited write ({@code AGENT_REVOKED}) names the principal. The caller has already
     * required {@code agent.revoke} at the agent's environment.
     *
     * @return the record after the call, or empty when the agent is unknown in the organization
     */
    Optional<AgentRecord> revoke(PrincipalRef actor, UUID organizationId, UUID agentId, RequestOrigin origin);
}
