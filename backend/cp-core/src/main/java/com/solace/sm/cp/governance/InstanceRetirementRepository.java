package com.solace.sm.cp.governance;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Retirement persistence port; its JDBC adapter belongs in {@code cp.persistence}. Every method names its
 * organization in its own {@code WHERE} ({@code I5}).
 */
public interface InstanceRetirementRepository {

    /** Whether {@code instanceId} is a node of this organization, retired or not. */
    boolean exists(UUID organizationId, UUID instanceId);

    /**
     * Sets {@code retired_at} and {@code retired_by} on a node that is not yet retired.
     *
     * @return {@code true} when this call retired it; {@code false} when it was already retired or does not exist
     */
    boolean retire(UUID organizationId, UUID instanceId, UUID retiredBy, Instant at);

    /** The ids among {@code instanceIds} that are this organization's and retired. */
    Set<UUID> retired(UUID organizationId, Collection<UUID> instanceIds);
}
