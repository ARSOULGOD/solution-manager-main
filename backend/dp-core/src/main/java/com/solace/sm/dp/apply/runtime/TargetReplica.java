package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ReplicaRef;
import java.util.Objects;

/**
 * One replica, with the placement it belongs to — {@code BE-D12} §2.1's "{@code ReplicaRef} carries
 * its {@code PlacementRef}; adapters never see bare strings".
 *
 * <p>Expressed as a pair rather than as a field on {@link ReplicaRef} deliberately ({@code BE-D21}):
 * {@code ReplicaRef} is grammar-mirrored into {@code resolution_is_evidence} by {@code BE-D19} and
 * serialized as the {@code ref} key that {@code BE-D18}'s element key-set predicate closes over, so
 * a second component would change a wire shape the database now holds rows to.
 */
public record TargetReplica(PlacementRef placement, ReplicaRef ref) {

    public TargetReplica {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(ref, "replica ref");
        if (ref.isPlacement()) {
            throw new IllegalArgumentException("the @placement sentinel names the placement-scoped "
                    + "steps (PREFLIGHT, ROLLBACK, ROLLBACK_ASSESS), not a replica to act on");
        }
    }
}
