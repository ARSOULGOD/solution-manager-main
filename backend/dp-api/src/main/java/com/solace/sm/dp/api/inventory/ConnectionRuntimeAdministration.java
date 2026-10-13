package com.solace.sm.dp.api.inventory;

import java.util.UUID;

/**
 * Gateway-to-Data-Plane seam for the runtime projection of a remote-server Connection.
 *
 * <p>The Control Plane owns the Connection row. The Data Plane owns this projection and never reads the
 * {@code sm_control} schema directly. Secret values are never carried here; {@code credentialRef} points at the
 * envelope secret store.
 */
public interface ConnectionRuntimeAdministration {

    /** Create or replace the live runtime projection for one remote-server Connection. */
    void upsert(UUID organizationId, ConnectionRuntimeSpec spec, UUID actor);

    /** Retire the runtime projection after the Control Plane Connection becomes inactive/deleted. */
    void retire(UUID organizationId, UUID connectionId, UUID actor);
}
