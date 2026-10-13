package com.solace.sm.cp.api.connection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of Connection governance (plan 013 T2, {@code BE-D66}). Shaped like
 * {@code TargetDirectory.listLive}: no authorization check here — the {@code connection.view}
 * reach-then-filter check ({@code x-sm-scope: reach}, ruling {@code OR-130}) is the caller's, exactly
 * as {@code listTargets} does it for Targets.
 */
public interface ConnectionDirectory {

    /** Every connection of the organization (active and inactive; delete removes the row, there is no retired state). */
    List<ConnectionView> list(UUID organizationId);

    /**
     * The organization's connection with this id, active or not; empty when absent or another organization's
     * ({@code I5}: the organization is the caller's session's). No authorization check, as {@link #list}: plan 014's
     * declare has already asked its verb at the connection's scope. The default scans {@link #list}; the real
     * directory reads the one row.
     */
    default Optional<ConnectionView> find(UUID organizationId, UUID connectionId) {
        return list(organizationId).stream().filter(c -> c.connectionId().equals(connectionId)).findFirst();
    }
}
