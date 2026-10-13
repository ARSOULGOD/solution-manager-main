package com.solace.sm.dp.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The persistence port for {@code placement}. A uniqueness collision surfaces as a named constraint violation. */
public interface PlacementRows {

    void insert(PlacementRecord row);

    /**
     * The one live (not retired) placement with this id that belongs to THIS instance of THIS tenant (plan 010
     * T6.2). The instance is part of the key on purpose: a placement id that names another instance's placement is
     * not found, so a lookup can never be answered with a host the caller did not ask about.
     */
    Optional<PlacementRecord> findLive(UUID tenantId, UUID instanceId, UUID placementId);

    /**
     * The placement with this id of THIS instance of THIS tenant, retired or not (plan 010 T6.9): an observation of an
     * old request still reports what its gate proved, from the immutable probe columns, after the placement is retired.
     */
    Optional<PlacementRecord> find(UUID tenantId, UUID instanceId, UUID placementId);

    /** The instance's live (not retired) placements. */
    List<PlacementRecord> live(UUID tenantId, UUID instanceId);

    /**
     * Retires every live placement of the instance; returns rows retired. A sweep or an instance retirement, not a
     * principal's act on one placement, so it records no {@code retired_by} (OR-125 (1)).
     */
    int retireAll(UUID tenantId, UUID instanceId, Instant at);

    /**
     * Retires ONE live placement of THIS instance of THIS tenant (plan 008 S3, {@code BE-D53}); returns rows changed: 0
     * when it is unknown, another instance's, another tenant's, or already retired. The row is kept forever.
     * {@code retiredBy} is the acting principal (OR-125 (1)), written with {@code retired_at} and never changed after.
     */
    int retire(UUID tenantId, UUID instanceId, UUID placementId, Instant at, UUID retiredBy);

    /**
     * Writes ONE placement's {@code instance_retirement} record (plan 012 T5, {@code OPS-Q12} R-1, {@code BE-D53}) already
     * closed {@code retired}, in the caller's transaction: {@code placement_id} set, {@code requested_by} and
     * {@code correlation_id} as given, {@code completed_at = at}, and {@code last_health} built by the database from the
     * placement's persisted health in the statement that inserts the row (so the record carries an id and a state and nothing
     * else). Returns rows written: 0 when the placement is not this instance's of this tenant.
     */
    default int recordRetirement(UUID tenantId, UUID retirementId, UUID instanceId, UUID placementId, UUID requestedBy,
            Instant at, UUID correlationId) {
        throw new UnsupportedOperationException("this store cannot record a placement's retirement");
    }

    /**
     * Takes the instance's {@code apply_scope} row {@code FOR UPDATE}, creating it first if the instance has never had an
     * admission. Every admission on the instance serializes on that row ({@code AdmissionService}), so while it is held
     * no request can be admitted: the in-flight check that follows is then not a snapshot of a moving target. First in the
     * lock order the admission path established ({@code apply_scope}, then {@code instance_state}).
     *
     * @return whether the lock is held: {@code false} when the tenant has no such instance (a scope row may exist only
     *         for a declared instance, so none is created and nothing is locked)
     */
    boolean lockApplyScope(UUID tenantId, UUID instanceId);

    /**
     * Whether work in flight names this placement of this instance: a {@code REQUESTED}, {@code RUNNING} or
     * {@code APPLY_INDETERMINATE} request on it OR on the whole instance (an instance-wide stop addresses every live
     * placement), or a fleet member reserving it that has not settled ({@code pending}, or {@code admitted} with no
     * settlement). Read under {@link #lockApplyScope}.
     */
    boolean inFlightOn(UUID tenantId, UUID instanceId, UUID placementId);

    /**
     * Whether a {@code PENDING} {@code apply_schedule} names this placement (Fable F-3, {@code OR-131} Q2 by analogy): a
     * schedule fires onto its placement, so retiring it first would turn the fire into a refusal the submitter never
     * chose. Read under {@link #lockApplyScope}. The default throws: a store that cannot answer must not retire.
     */
    default boolean scheduleReservesPlacement(UUID tenantId, UUID instanceId, UUID placementId) {
        throw new UnsupportedOperationException("this store cannot say whether a schedule names a placement");
    }

    /**
     * Whether an instance-wide request (the nil placement) is in flight on the instance: a {@code REQUESTED},
     * {@code RUNNING} or {@code APPLY_INDETERMINATE} one. {@code addPlacement}'s check (W2 review W2A-2): an instance-wide
     * stop reads the live set once, when it starts running, so a placement added under it would be missed. Read under
     * {@link #lockApplyScope}, for the reason {@link #inFlightOn} is.
     */
    boolean instanceWideInFlight(UUID tenantId, UUID instanceId);

    // ---- plan 013 T1.5: a target's connection changes while work may be running on it -------------------------------

    /**
     * Takes the {@code apply_scope} row {@code FOR UPDATE} of every instance with a LIVE placement on the target, in
     * {@code instance_id} order (a fixed order, so two edits of two targets that share instances cannot deadlock), creating
     * a missing scope row first as {@link #lockApplyScope} does. While they are held no request can be admitted on any of
     * those instances, so {@link #inFlightOnTarget} is not a snapshot of a moving target. First in the lock order admission
     * established ({@code apply_scope}, then {@code instance_state}, then the target row).
     *
     * @return the instance ids locked, in the order they were locked
     */
    default List<UUID> lockApplyScopesOfTarget(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot lock a target's apply scopes");
    }

    /**
     * Whether work in flight names a LIVE placement of the target: a {@code REQUESTED}, {@code RUNNING} or
     * {@code APPLY_INDETERMINATE} request on the placement or on its whole instance (an instance-wide stop addresses every
     * live placement), or a fleet member reserving the placement that has not settled (the same set as
     * {@link #inFlightOn}). Read under {@link #lockApplyScopesOfTarget}.
     */
    default boolean inFlightOnTarget(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot say whether work is in flight on a target");
    }

    /** Whether any LIVE placement names the target. Read-only: the retirement's pre-check, for its error text only. */
    default boolean anyLiveOnTarget(UUID tenantId, UUID targetId) {
        throw new UnsupportedOperationException("this store cannot say whether a target has live placements");
    }
}
