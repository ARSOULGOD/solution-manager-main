package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The persistence port for {@code instance_state}. Every method names the tenant (I5). */
public interface InstanceRows {

    void insert(InstanceRecord row);

    Optional<InstanceRecord> find(UUID tenantId, UUID instanceId);

    /** Rows in one lifecycle, optionally narrowed to one catalog solution, ordered by name. */
    List<InstanceRecord> list(UUID tenantId, InstanceLifecycle lifecycle, Optional<UUID> catalogSolutionId);

    List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff);

    /** The oldest {@code limit} {@code pending} ids of the tenant, any age, oldest first (the declare sweep). */
    List<UUID> pendingIds(UUID tenantId, int limit);

    /** The oldest {@code limit} {@code pending} ids created before {@code cutoff}, oldest first. */
    List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff, int limit);

    /** {@code pending -> active}; returns rows changed (0 when not pending). */
    int activate(UUID tenantId, UUID instanceId);

    /** {@code pending -> abandoned}; returns rows changed (0 when not pending). */
    int abandon(UUID tenantId, UUID instanceId);

    /**
     * The row, locked {@code FOR NO KEY UPDATE} for the rest of the transaction (plan 008 S3): the one lock every writer
     * of an instance's placement set takes, so two of them (an add and a retire, two adds) run one after the other, and
     * so plan 012's undeclare, which takes the row {@code FOR UPDATE}, excludes both. Chosen over {@code FOR SHARE}
     * because both callers STAMP the row afterwards ({@link #touch}), and two {@code FOR SHARE} holders that each then
     * upgrade deadlock. Does not wait on a {@code FOR KEY SHARE} holder (a placement insert's foreign key takes that).
     * Empty if the tenant has no such instance.
     */
    Optional<InstanceRecord> lockForPlacementChange(UUID tenantId, UUID instanceId);

    /**
     * Stamps an {@code active} instance's {@code state_version} without changing a column (a same-value write of
     * {@code lifecycle}: V007's stamp trigger fires on every UPDATE, its lifecycle trigger only on a change). For a
     * change the row does not hold itself but the instance's observable view does: its placement set. Returns rows
     * changed (0 when the instance is not active).
     */
    int touch(UUID tenantId, UUID instanceId);
}
