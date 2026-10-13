package com.solace.sm.cp.connection;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.connection.ConnectionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code sm_control.connection} (plan 013 T2, {@code BE-D66}, {@code OR-130}). Its JDBC
 * adapter belongs in {@code cp.persistence}. Every method names the organization, and binds it ({@code I5}).
 *
 * <p>Every write runs in the caller's transaction, after the audit context is set ({@code OR-25}).
 */
public interface ConnectionRepository {

    /** Every connection of the organization, ordered by name. */
    List<ConnectionView> list(UUID organizationId);

    /** The connection with this id in the organization. */
    Optional<ConnectionView> findById(UUID organizationId, UUID connectionId);

    /** The connection of this name at this exact ownership scope in the organization, if one exists. */
    Optional<ConnectionView> findByName(UUID organizationId, ScopePath ownershipScope, String name);

    /**
     * Inserts the connection at revision 1, creating the canonical scope row for {@code ownershipScope} when none
     * exists.
     *
     * @throws com.solace.sm.cp.api.connection.ConnectionConflictException {@code NAME_TAKEN} on a unique-constraint
     *         loss of a race with another insert of the same name in the same scope
     */
    void insert(UUID organizationId, UUID connectionId, String name, String connectionType, int descriptorVersion,
            ScopePath ownershipScope, Map<String, Object> attributes, Map<String, Boolean> secretStatus,
            UUID createdBy, Instant createdAt);

    /**
     * Rewrites {@code name}/{@code attributes}/{@code secretStatus} at {@code expectedRevision + 1}, only if the
     * stored revision is still {@code expectedRevision}.
     *
     * @return {@code false} when it is not (nothing was written)
     * @throws com.solace.sm.cp.api.connection.ConnectionConflictException {@code NAME_TAKEN} on a unique-constraint
     *         loss (a concurrent rename to the same name)
     */
    boolean update(UUID organizationId, UUID connectionId, long expectedRevision, String name,
            Map<String, Object> attributes, Map<String, Boolean> secretStatus, UUID updatedBy, Instant updatedAt);

    /**
     * Marks the LIVE connection inactive, only if the stored revision is still {@code expectedRevision}.
     *
     * @return {@code false} when it is not (nothing was written) — the caller re-reads to tell "already inactive"
     *         from "raced" apart
     */
    boolean deactivate(UUID organizationId, UUID connectionId, long expectedRevision, UUID updatedBy, Instant updatedAt);

    /**
     * Deletes the connection outright. Must run in the caller's transaction, after {@code usedByCount} is
     * confirmed zero under the same read that is still current when this executes.
     *
     * @return rows changed: 0 when it is absent, or another organization's
     */
    int delete(UUID organizationId, UUID connectionId);
}
