package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code InstanceRetirement} ({@code Versioned} + the undeclare's own fields): what
 * {@code undeclareInstance} answers (plan 012 T5, {@code BE-D49}, {@code ops-501.md} section 4.5).
 *
 * <p>{@code workload} is the closed word {@code left-running}: undeclare writes no host, so the container keeps running, now
 * unmanaged by SM. It is in the body so the response says so; it is always that word, which is why the request must say
 * {@code ?workload=leave-running} to be understood at all. The record carries ids and a health state token only: no host,
 * no container name, no path and no reference. {@code retiredAt} is an ISO-8601 instant spelled as text, as every timestamp on
 * this wire is.
 */
public record InstanceRetirement(long stateVersion, @WireOptional Availability availability, UUID instanceId,
        String retiredAt, String workload, List<RetiredPlacement> placements) {

    /** The only value of {@link #workload()}. */
    public static final String LEFT_RUNNING = "left-running";

    public InstanceRetirement {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(retiredAt, "retiredAt");
        Objects.requireNonNull(workload, "workload");
        placements = List.copyOf(placements);
    }

    /** One placement that was live and the health state SM had last persisted for it ({@code null}: never probed). */
    public record RetiredPlacement(UUID placementId, String lastHealth) {
        public RetiredPlacement {
            Objects.requireNonNull(placementId, "placementId");
        }
    }
}
