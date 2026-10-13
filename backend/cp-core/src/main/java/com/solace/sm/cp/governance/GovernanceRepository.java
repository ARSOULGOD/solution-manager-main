package com.solace.sm.cp.governance;

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Governance persistence port; its JDBC adapter belongs in {@code cp.persistence}. Every method names its
 * organization in its own {@code WHERE} ({@code I5}).
 */
public interface GovernanceRepository {

    /** The solution name of the organization's own catalog entry, or empty when the id is not this organization's. */
    Optional<String> solutionNameOf(UUID organizationId, UUID catalogSolutionId);

    /** The organization unit holding the organization's own environment, or empty when it is not this organization's. */
    Optional<UUID> organizationUnitOf(UUID organizationId, UUID environmentId);

    /**
     * {@code max_instances} of the entitlement row, read {@code FOR UPDATE}: the lock is held until the surrounding
     * transaction ends, which is what makes {@link #countInstances} binding (OR-40). Empty when not entitled.
     */
    OptionalInt lockEntitlement(UUID organizationId, String solution);

    /** {@code max_instances} without a lock, for the pre-check. Empty when not entitled. */
    OptionalInt maxInstances(UUID organizationId, String solution);

    /** Instances of the solution across every environment of the organization. */
    long countInstances(UUID organizationId, String solution);

    Optional<UUID> findSolutionNode(UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID catalogSolutionId);

    /** {@code true} when this call inserted the node; {@code false} when another transaction's node already exists. */
    boolean insertSolutionNode(UUID organizationId, UUID organizationUnitId, UUID environmentId, UUID solutionId,
            UUID catalogSolutionId);

    void insertInstance(UUID organizationId, UUID organizationUnitId, UUID environmentId, UUID solutionId,
            UUID instanceId);

    /** Idempotent: the canonical scope row of the solution node. */
    void insertSolutionScope(UUID scopeId, UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID solutionId);

    /** Idempotent: the canonical scope row of the instance node. */
    void insertInstanceScope(UUID scopeId, UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID solutionId, UUID instanceId);

    Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds);
}
