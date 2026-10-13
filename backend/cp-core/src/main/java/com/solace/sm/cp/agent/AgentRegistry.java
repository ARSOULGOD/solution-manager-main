package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentRegistration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence-neutral CP registry port over {@code sm_control.agent_registration}. Secrets arrive as digests only
 * (I6). Every method that writes runs in the caller's transaction, after the audit context is set (OR-25).
 */
public interface AgentRegistry {

    /** What the issuing principal granted: the row {@link #insert} writes. {@code tokenDigest} is the unkeyed SHA-256 (OR-128 (9)). */
    record NewRegistration(UUID organizationId, UUID organizationUnitId, UUID environmentId, UUID agentId,
            UUID catalogSolutionId, String packageVersion, String targetName, String allowedConfigRoot,
            String allowedContainerPrefix, byte[] tokenDigest, Instant expiresAt, UUID createdBy, Instant createdAt) { }

    /** A registration as enrolment reads it: the scope and envelope the token carries, and the consumption state. */
    record Enrollment(AgentRegistration registration, UUID organizationId, UUID organizationUnitId,
            UUID environmentId, String targetName, String allowedConfigRoot, String allowedContainerPrefix,
            UUID createdBy, Instant expiresAt, byte[] csrDigest,
            AgentCertificateAuthority.IssuedCertificate certificate, UUID catalogSolutionId, String solution,
            String packageVersion) {

        public boolean consumed() {
            return csrDigest != null;
        }
    }

    void insert(NewRegistration registration);

    /**
     * The ACTIVE registration holding this token digest, row-locked until the transaction ends (the token is the credential
     * and names its organization: this is the enrolment arbiter, not a tenant-scoped read).
     */
    Optional<Enrollment> lockByTokenDigest(byte[] tokenDigest);

    /** The single consumption: used-at, CSR digest, issued material and target id together (the table's CHECK). */
    void consume(UUID agentId, byte[] csrDigest, UUID targetId,
            AgentCertificateAuthority.IssuedCertificate issued, Instant usedAt);

    /** An agent's registration and the leaf certificate issued to it. {@code leafDer} and {@code validTo} are null until consumption. */
    record Registered(AgentRegistration registration, byte[] leafDer, Instant validTo) { }

    /**
     * The agent's registration in this organization, revoked ones included ({@code registration().active() == false}), bound
     * by organization (I5); empty when the agent is unknown in it.
     */
    Optional<Registered> find(UUID organizationId, UUID agentId);

    /**
     * Every registration for the given agents, active or revoked, keyed by agent id. The ONE read not bound by organization:
     * the DP's reconcile (AgentRegistrationSource.current) spans sessions and names none. Agent ids are globally unique
     * (V021) and only the registration's own state is returned; AgentRegistryBoundaryTest names this read as the sole caller.
     */
    Map<UUID, AgentRegistration> findAll(Collection<UUID> agentIds);

    /** An agent target a sweep may abandon (plan 015 T3.5): its consumed registration, still holding its target. */
    record Abandonable(UUID agentId, UUID targetId, Instant tokenExpiresAt) { }

    /**
     * The organizations that have at least one consumed registration whose target is live and whose token expired before the
     * cutoff (OR-140): the sweep job's organization list, and its ONLY caller ({@code TenantEnumerationBoundaryTest}). The default
     * throws: a store that cannot answer must not sweep.
     */
    default java.util.List<UUID> organizationsWithAbandonable(Instant expiredBefore) {
        throw new UnsupportedOperationException("this registry cannot list organizations with abandonable enrolments");
    }

    /**
     * This organization's consumed registrations whose target is live (not retired) and whose TOKEN expired before the cutoff,
     * oldest token first, KEYSET-paged after {@code (afterExpiresAt, afterAgentId)} (both null for the first page), at most
     * {@code limit}. Bound by organization (I5). The default throws: a store that cannot answer must not sweep.
     */
    default java.util.List<Abandonable> abandonable(UUID organizationId, Instant expiredBefore, Instant afterExpiresAt,
            UUID afterAgentId, int limit) {
        throw new UnsupportedOperationException("this registry cannot list abandonable enrolments");
    }

    /**
     * Revokes an ACTIVE registration of this organization (status {@code revoked}, {@code revoked_at}, the agent epoch raised);
     * a registration already revoked changes nothing. Runs in the caller's transaction, after the audit context is set (OR-25).
     * Returns whether a row changed.
     */
    default boolean revokeIfActive(UUID organizationId, UUID agentId, Instant at) {
        throw new UnsupportedOperationException("this registry cannot revoke");
    }

    /**
     * One registration as the operator surface reads it (plan 015 T4.3): the raw lifecycle columns, no credential material. The
     * status text is the table's ({@code active} or {@code revoked}); {@code enrolledAt} (the token's use) and {@code expiresAt}
     * let the service say pending, expired or active against its own clock.
     */
    record Detail(UUID agentId, UUID organizationUnitId, UUID environmentId, UUID targetId, String targetName, String status,
            String allowedConfigRoot, String allowedContainerPrefix, UUID catalogSolutionId, String packageVersion,
            java.util.List<String> capabilities, Instant createdAt, Instant expiresAt, Instant enrolledAt, Instant revokedAt) { }

    /** Every registration of this organization (revoked ones included), oldest first. Bound by organization (I5). */
    default java.util.List<Detail> list(UUID organizationId) {
        throw new UnsupportedOperationException("this registry cannot list");
    }

    /** One registration of this organization, or empty. Bound by organization (I5). */
    default Optional<Detail> detail(UUID organizationId, UUID agentId) {
        throw new UnsupportedOperationException("this registry cannot read a registration");
    }
}
