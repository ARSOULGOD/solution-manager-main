package com.solace.sm.cp.hierarchy;

import com.solace.sm.cp.api.hierarchy.ScopeNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the installer/migration-seeded hierarchy tree — {@code sm_control.organization_unit} and
 * {@code sm_control.environment}. No decision or endpoint creates these rows at runtime in v1
 * ({@code D11-AMENDMENT-PROPOSAL.md} §8), so this is read-only.
 */
public interface HierarchyTreeRepository {

    /** True when {@code organizationUnitId} exists directly under {@code organizationId}. */
    boolean subOrganizationExists(UUID organizationId, UUID organizationUnitId);

    /** The organization's id and display name ({@code OR-120}); empty when no such row exists. */
    Optional<ScopeNode> organizationOf(UUID organizationId);

    /** Id and display name of every sub-organization directly under {@code organizationId}, ordered by name (byte-wise) then id. */
    List<ScopeNode> subOrganizationsOf(UUID organizationId);

    /** Id and display name of every environment directly under {@code organizationId}/{@code organizationUnitId}, ordered by name (byte-wise) then id. */
    List<ScopeNode> environmentsOf(UUID organizationId, UUID organizationUnitId);
}
