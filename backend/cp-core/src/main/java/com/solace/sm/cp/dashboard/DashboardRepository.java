package com.solace.sm.cp.dashboard;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code sm_control.dashboard} (plan 011 P11-7.1). Its JDBC adapter belongs in
 * {@code cp.persistence}. Every method names the organization, and binds it ({@code I5}).
 *
 * <p>Every write runs in the caller's transaction, after the audit context is set (OR-25).
 */
public interface DashboardRepository {

    /** A stored dashboard: its document text and the revision a write must present. */
    record Stored(String documentJson, long revision) {
    }

    Optional<Stored> find(UUID organizationId, UUID instanceId);

    /**
     * Stores the instance's first dashboard document at revision {@code 1}.
     *
     * @return {@code false} when a dashboard for the instance exists already (a concurrent first write won), which
     *         the service answers as a stale revision
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException when the organization has no such instance
     * @throws com.solace.sm.cp.api.dashboard.InvalidDashboardException when the document is over the database's limit
     */
    boolean insert(UUID organizationId, UUID instanceId, String documentJson, UUID updatedBy, Instant updatedAt);

    /**
     * Rewrites the document at {@code expectedRevision + 1}, only if the stored revision is still
     * {@code expectedRevision}.
     *
     * @return {@code false} when it is not (nothing was written)
     * @throws com.solace.sm.cp.api.dashboard.InvalidDashboardException when the document is over the database's limit
     */
    boolean update(UUID organizationId, UUID instanceId, long expectedRevision, String documentJson, UUID updatedBy,
            Instant updatedAt);
}
