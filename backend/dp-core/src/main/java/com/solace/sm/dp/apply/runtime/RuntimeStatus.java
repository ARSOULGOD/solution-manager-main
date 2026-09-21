package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.util.Objects;
import java.util.Optional;

/**
 * One replica, observed.
 *
 * @param observedHash the hash read back at the declared path, or {@code null} when nothing is there
 * @param baseline     the process/container start baseline, or {@code null} when it cannot be read —
 *                     which §2.2 rule 3 makes the {@code APPLY_INDETERMINATE} trigger, never a
 *                     reason to re-issue a restart the operator may not have authorized twice
 */
public record RuntimeStatus(Liveness liveness, HealthSignal health, String observedHash,
        PreBaseline baseline, RuntimeRef runtimeRef) {

    public RuntimeStatus {
        Objects.requireNonNull(liveness, "liveness");
        Objects.requireNonNull(health, "health signal");
        Objects.requireNonNull(runtimeRef, "runtime ref");
    }

    public Optional<String> hash() {
        return Optional.ofNullable(observedHash);
    }

    public Optional<PreBaseline> startBaseline() {
        return Optional.ofNullable(baseline);
    }
}
