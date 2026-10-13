package com.solace.sm.dp.health;

import java.time.Instant;
import java.util.UUID;

/**
 * The persistence port for {@code placement_start_observation} (OR-112 (1)): the container start the data plane last
 * observed on a placement. The JDBC lives in {@code dp.apply.persistence}. Every method names the tenant (I5).
 */
public interface StartObservationRows {

    /**
     * Takes the owning {@code instance_state} row {@code FOR UPDATE}, the lock every landing already holds when it writes
     * the ladder's cache, so that the observation's own rung write and a landing's are serial. Must run inside the
     * caller's transaction.
     */
    void lockInstance(UUID tenantId, UUID instanceId);

    /**
     * Records one observed start: inserts the placement's first row, otherwise moves it only when the start or the
     * container differs from the one held, so confirming what is already recorded writes nothing.
     *
     * @param startedAt  the HOST's clock, as {@code docker inspect} reported it
     * @param processRef the container id
     * @param observedAt the data plane's clock
     * @return whether the stored observation changed
     */
    boolean record(UUID tenantId, UUID placementId, Instant startedAt, String processRef, Instant observedAt);
}
