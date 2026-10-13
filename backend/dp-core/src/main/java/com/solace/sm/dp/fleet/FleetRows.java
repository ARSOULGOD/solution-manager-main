package com.solace.sm.dp.fleet;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port of the fleet ({@code fleet_action}, {@code fleet_member}). The service in this package imports no
 * JDBC type ({@code jdbcIsConfinedToPersistencePackages}); {@code JdbcFleetRepository} implements it. Every method is
 * tenant-scoped ({@code I5}) and binds its parameters. Nothing here reads across tenants: the scheduler's one
 * cross-tenant read is {@code TenantEnumerationRepository#tenantsWithActiveInstances()} ({@code OR-49}), called, never
 * re-spelt.
 *
 * <p>Every update is CONDITIONAL on the row's current disposition and returns the rows it changed, so a late writer meets
 * zero rows rather than the database's forward-only trigger. A transaction that changes a member row first
 * {@linkplain #lock locks} the parent fleet row ({@code OR-35}'s commit-order rule, section 4.3).
 */
public interface FleetRows {

    /** The database refused a second live reservation of a placement ({@code fleet_member_reservation}). */
    final class PlacementReservedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PlacementReservedException() {
            super("the placement is reserved by another running fleet");
        }
    }

    /** Inserts the fleet row; the database stamps it. */
    void insertFleet(FleetRecord fleet);

    /** @throws PlacementReservedException a {@code pending} member's placement is reserved by another running fleet */
    void insertMember(MemberRecord member);

    Optional<FleetRecord> find(UUID tenantId, UUID fleetId);

    /** As {@link #find}, taking the fleet row {@code FOR UPDATE}: the lock every member change starts with. */
    Optional<FleetRecord> lock(UUID tenantId, UUID fleetId);

    /** This tenant's {@code RUNNING} fleets, oldest first. Tenant-scoped. */
    List<UUID> runningFleets(UUID tenantId);

    /** Every member, in ordinal order. */
    List<MemberRecord> members(UUID tenantId, UUID fleetId);

    /** The request rows these ids name, by id; an id that names no request is absent. */
    Map<UUID, MemberRequest> requestsOf(UUID tenantId, Collection<UUID> applyIds);

    /** Whether a request with this id exists. */
    boolean requestExists(UUID tenantId, UUID applyId);

    /** {@code pending -> admitted}. @return rows changed (0 when the member is no longer pending) */
    int markAdmitted(UUID tenantId, UUID fleetId, int ordinal);

    /** {@code pending -> not_admitted}, settled {@code failed}. @return rows changed */
    int markNotAdmitted(UUID tenantId, UUID fleetId, int ordinal, String admissionRefusal);

    /** {@code pending -> skipped}. @return rows changed */
    int markSkipped(UUID tenantId, UUID fleetId, int ordinal);

    /** An {@code admitted}, unsettled member gains its settlement, once. @return rows changed */
    int settle(UUID tenantId, UUID fleetId, int ordinal, String settled);

    /** Moves {@code current_batch} forward. @return rows changed */
    int moveToBatch(UUID tenantId, UUID fleetId, int batch);

    /** {@code RUNNING -> state}, with {@code terminal_at}. @return rows changed */
    int land(UUID tenantId, UUID fleetId, String state, Instant at);
}
