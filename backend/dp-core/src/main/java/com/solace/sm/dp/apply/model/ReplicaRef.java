package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * {@code apply_step_intent.replica_ref} — adapter vocabulary (a container id, a unit name, a pod
 * name), or the {@link #PLACEMENT} sentinel for the placement-scoped steps.
 *
 * @param value the adapter's own name for the replica; never blank
 */
public record ReplicaRef(String value) {

    /** The sentinel design §3.1 fixes for placement-scoped steps. */
    public static final ReplicaRef PLACEMENT = new ReplicaRef("@placement");

    public ReplicaRef {
        Objects.requireNonNull(value, "replica ref");
        ValueGrammar.require(ValueGrammar.REPLICA_REF, value, "a replica ref",
                "an infrastructure identifier — Docker/systemd/Kubernetes-shaped (BE-D19), not merely "
                        + "non-blank");
    }

    public boolean isPlacement() {
        return PLACEMENT.value.equals(value);
    }
}
