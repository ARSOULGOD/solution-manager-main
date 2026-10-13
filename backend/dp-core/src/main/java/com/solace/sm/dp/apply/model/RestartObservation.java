package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * One replica's line of {@code BE-D17}'s {@code restart} evidence — design §3.4: "two fields, not
 * three: no {@code observedHash} at all, because a restart resolution verifies no generation and has no
 * config hash to read back".
 *
 * <p>Deliberately not a {@link ReplicaObservation} with a null hash: a restart observation is a
 * structurally smaller record, so "this shape has no hash" is a property of the type, not a convention
 * about which field a caller happens to leave null.
 *
 * @param ref       which replica was observed
 * @param restarted whether the operator's restart was observed on it
 */
public record RestartObservation(ReplicaRef ref, boolean restarted) {

    public RestartObservation {
        Objects.requireNonNull(ref, "replica ref");
    }
}
