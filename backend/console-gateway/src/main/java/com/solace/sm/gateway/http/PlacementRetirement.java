package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/**
 * Response of {@code retirePlacement} (plan 008 S3, {@code BE-D53}): which placement was retired, what became of its
 * workload, and the instance as it reads now (its remaining live placements, a fresh {@code stateVersion}).
 *
 * <p>{@code workload} is the closed word {@code left-running}: retirement writes no host, so the container keeps running,
 * now unmanaged by SM. It is in the body so the response says so, as the contract promises; it is always that word,
 * which is why the request must say {@code ?workload=leave-running} to be understood at all.
 */
public record PlacementRetirement(UUID placementId, String workload, Instance instance) {

    /** The only value of {@link #workload()}. */
    public static final String LEFT_RUNNING = "left-running";

    public PlacementRetirement {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(instance, "instance");
    }
}
