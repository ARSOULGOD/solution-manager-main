package com.solace.sm.cp.api.connection;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Map;
import java.util.UUID;

/**
 * The write side of Connection governance (plan 013 T2, {@code BE-D66}, {@code OR-130}): create, update,
 * deactivate, delete. Shaped like {@code TargetRegistration}: the verb is checked at the resource's
 * scope, before anything is read (a caller who may not act never learns whether the name is taken, or
 * that the record exists).
 *
 * <p>⛔ {@code I6}: {@code attributes} never carries a secret-typed value here. The caller — the layer
 * that knows the connection type's descriptor (Task 3/4) — strips secret-typed values out and reports
 * only presence through {@code secretStatus} before calling into this seam.
 */
public interface ConnectionRegistry {

    /** A new connection config object. {@code descriptorVersion} is the catalog version the attributes were validated against. */
    record CreateRequest(String name, String connectionType, int descriptorVersion, ScopePath ownershipScope,
            Map<String, Object> attributes, Map<String, Boolean> secretStatus) {
    }

    /**
     * The fields {@code updateConnection} may change; {@code null} means "leave unchanged". A non-null
     * {@code attributes}/{@code secretStatus} is merged new-over-existing by the service, not replaced
     * wholesale.
     */
    record UpdateRequest(String name, Map<String, Object> attributes, Map<String, Boolean> secretStatus) {
    }

    /**
     * Creates the connection. The verb is {@code connection.create} at {@code request.ownershipScope()}.
     *
     * @throws ConnectionConflictException {@code NAME_TAKEN} when the name is already used in this scope
     */
    ConnectionView create(PrincipalRef actor, UUID organizationId, CreateRequest request, RequestOrigin origin);

    /**
     * Changes a connection's name and/or attributes. The verb is {@code connection.edit} at the connection's
     * own scope, checked before the row is read.
     *
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException when absent, or another organization's
     * @throws ConnectionConflictException {@code NAME_TAKEN} on a renaming clash, or {@code STALE_WRITE} when
     *         the record changed between the read and the write (the one retry already failed)
     */
    ConnectionView update(PrincipalRef actor, UUID organizationId, UUID connectionId, UpdateRequest request,
            RequestOrigin origin);

    /**
     * Marks the connection inactive. Idempotent: deactivating an already-inactive connection answers the
     * current record and writes nothing. The verb is {@code connection.edit} at the connection's own scope.
     *
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException when absent, or another organization's
     */
    ConnectionView deactivate(PrincipalRef actor, UUID organizationId, UUID connectionId, RequestOrigin origin);

    /**
     * Deletes the connection outright — unlike a Target, a deleted Connection leaves no row. The verb is
     * {@code connection.delete} at the connection's own scope, checked before the row is read.
     *
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException when absent, or another organization's
     * @throws ConnectionConflictException {@code IN_USE} while {@code usedByCount > 0}
     */
    void delete(PrincipalRef actor, UUID organizationId, UUID connectionId, RequestOrigin origin);
}
