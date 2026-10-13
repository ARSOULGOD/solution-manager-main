package com.solace.sm.cp.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Entitlement persistence port; its JDBC adapter belongs in {@code cp.persistence}. */
public interface EntitlementRepository {

    /** One stored entitlement (plan 013 T2.2: the API reads the whole row, the operator command never did). */
    record Stored(String solution, int maxInstances, Instant setAt) {
    }

    /** Every entitlement of the organization, ordered by solution name (byte-wise, so the order is the same everywhere). */
    List<Stored> list(UUID organizationId);

    Optional<Stored> find(UUID organizationId, String solution);

    boolean exists(UUID organizationId, String solution);

    /** Insert, or update {@code max_instances}/{@code set_by}/{@code set_at} of the existing row. */
    void upsert(UUID organizationId, String solution, int maxInstances, UUID setBy, Instant setAt);

    /** Insert only (OR-148); an existing row is left exactly as it is. Returns whether a row was created. */
    boolean insertIfAbsent(UUID organizationId, String solution, int maxInstances, UUID setBy, Instant setAt);
}
