package com.solace.sm.dp.api.inventory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The data plane's instance inventory (OR-27). Every call is scoped to {@code organizationId} (I5). */
public interface InstanceInventoryService {

    /**
     * Phase 1 of declare (OR-27): the DP mints the id; the row is {@code pending} until {@link #activate}. Nothing is
     * written unless the instance and its placement both are.
     *
     * @throws PlacementCollisionException      the placement reuses a container name, config directory or health port
     * @throws PlacementOutsideEnvelopeException a placement field lies outside the target's envelope (B2)
     */
    InstanceView reserve(UUID organizationId, DeclareInstanceCommand command);

    /** {@code pending -> active}. Idempotent: an already active instance is returned without a write. */
    InstanceView activate(UUID organizationId, UUID instanceId);

    /**
     * {@code pending -> abandoned} AND the instance's placements retired, in one transaction (M7(c)): an abandoned
     * declare must not hold a container name, directory or health port forever. Idempotent.
     */
    void abandon(UUID organizationId, UUID instanceId);

    /** Only {@code active} instances are findable. */
    Optional<InstanceView> findActive(UUID organizationId, UUID instanceId);

    List<InstanceView> listActive(UUID organizationId, UUID catalogSolutionId);

    /** Pending instances created longer ago than {@code age}, for the declare sweep (T4.2). */
    List<UUID> pendingOlderThan(UUID organizationId, Duration age);

    /**
     * The declare sweep's first half (BE-D32): this tenant's {@code pending} instance ids, oldest first, at most
     * {@code limit}. Any age: the caller asks the Control Plane which of them it registered and passes the answer to
     * {@link #settlePending}. Tenant-bound; no tenant enumeration (plan 010 B4).
     *
     * @throws IllegalArgumentException {@code limit} is below 1
     */
    List<UUID> pending(UUID organizationId, int limit);

    /**
     * The declare sweep's second half (BE-D32), one transaction, idempotent. Activates every {@code pending} id in
     * {@code registered} at any age (the Control Plane already holds its node, so activation is always safe); then
     * abandons, and retires the placements of, every other {@code pending} instance created longer ago than
     * {@code abandonAfter} (a fresh one may be a declare still in flight and is left alone). An id in
     * {@code registered} that is not {@code pending} here is ignored.
     *
     * @throws IllegalArgumentException {@code abandonAfter} is not positive
     */
    void settlePending(UUID organizationId, Set<UUID> registered, Duration abandonAfter);

    /**
     * Plan 008 S3 ({@code BE-D47}): adds a second (or later) live placement to an {@code active} instance, in ONE
     * transaction that locks the instance row and re-checks {@code active} (the lock is the guard: no database rule ties a
     * placement insert to an active instance; the instance's {@code apply_scope} row is taken first, as retire does, so an add
     * cannot slip in under an instance-wide request in flight), inserts the placement and its metrics endpoints in {@code reserve}'s lock
     * order, stamps the instance and re-settles the configuration ladder ({@code OQ-8.10 (a)}: the new placement has no
     * evidence, so the instance rung reads {@code rendered} until a fleet rolls the current generation to it). Writes no
     * host and deploys nothing ({@code I1}, {@code OR-29}). It does not count against the instance quota ({@code OQ-8.8}).
     *
     * <p>This and {@link #retirePlacement} are {@code default} methods only so the test fakes of this port, which never add
     * or retire a placement, keep compiling; {@code InventoryService} is the one implementation, and a fake that inherits
     * the refusal fails loudly instead of answering.
     *
     * @throws PushMetricsPlacementRefusedException the command's metric definitions include a {@code solace_event} source;
     *                                              raised before any row is read or written
     * @throws PlacementOutsideEnvelopeException     a placement field lies outside the target's envelope (B2)
     * @throws PlacementCollisionException           the placement reuses a container name, config directory or health port
     * @throws InstanceNotActiveException            the instance is not an {@code active} instance of this tenant
     * @throws InstanceRequestInFlightException      an instance-wide request is in flight (W2 review W2A-2): a stop reads
     *                                               the live placement set once, when it starts running
     * @throws java.util.NoSuchElementException      the target has no live connection record
     */
    default InstanceView addPlacement(UUID organizationId, UUID instanceId, AddPlacementCommand command) {
        throw new UnsupportedOperationException("this inventory does not add placements");
    }

    /**
     * Plan 008 S3 ({@code BE-D53}): retires ONE live placement. It touches no host: the container keeps running, now
     * unmanaged. One DP transaction that takes the instance's {@code apply_scope} row (so no admission races the
     * in-flight check) and then the instance row, the same lock {@link #addPlacement} takes. The row is kept forever.
     *
     * <p>{@code requestedBy} is required and recorded as {@code placement.retired_by}, with {@code retired_at}
     * (OR-125 (1), sm_data V026; W2 review W2A-1).
     *
     * <p>Plan 012 T5 ({@code OPS-Q12} R-1, {@code BE-D53}): the same transaction writes the placement's
     * {@code instance_retirement} record, already closed {@code retired}, carrying {@code placement_id}, {@code requestedBy},
     * {@code correlationId} and the placement's last persisted health. A refused retirement writes none.
     *
     * @throws InstanceNotActiveException   the instance is not an {@code active} instance of this tenant
     * @throws PlacementNotLiveException    no such live placement of this instance
     * @throws LastLivePlacementException   it is the instance's last live placement
     * @throws PlacementInFlightException   work in flight names it
     * @throws PlacementSchedulePendingException a {@code PENDING} scheduled apply names it (Fable F-3)
     */
    default InstanceView retirePlacement(UUID organizationId, UUID instanceId, UUID placementId, UUID requestedBy,
            UUID correlationId) {
        throw new UnsupportedOperationException("this inventory does not retire placements");
    }
}
