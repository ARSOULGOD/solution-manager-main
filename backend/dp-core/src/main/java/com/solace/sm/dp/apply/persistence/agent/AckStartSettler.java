package com.solace.sm.dp.apply.persistence.agent;

import java.util.UUID;

/**
 * What runs, inside the acknowledgement's transaction, after an acknowledged container start MOVED a placement's start
 * observation forward ({@code AGW-D28} item 2): it settles the instance's config ladder, so {@code reached_rung} and
 * {@code instance.config-state} move with the observation, exactly as the report intake and the dp-remote start observer do. The
 * persistence package cannot reach the ladder writer, so the data plane's agent configuration supplies this.
 */
@FunctionalInterface
public interface AckStartSettler {

    /** Nothing to settle: a repository built without a ladder (kits, tests of the repository alone). */
    AckStartSettler NONE = (tenantId, instanceId) -> { };

    void settle(UUID tenantId, UUID instanceId);
}
