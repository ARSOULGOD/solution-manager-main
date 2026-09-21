package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.util.List;
import java.util.Objects;

/** A plan or a refusal. {@code prepare} never mutates, so a refusal leaves nothing behind. */
public sealed interface PreflightResult {

    /**
     * @param healthSignalDeclared whether the runtime publishes a health signal for these replicas.
     *                             An OBSERVATION, not a configuration flag: a health-gated strategy
     *                             is refused at pre-flight when it is false.
     */
    record Ready(List<TargetReplica> replicas, ConfigLocation location,
            boolean healthSignalDeclared, RuntimeRef runtimeRef) implements PreflightResult {

        public Ready {
            Objects.requireNonNull(location, "config location");
            Objects.requireNonNull(runtimeRef, "runtime ref");
            if (replicas == null || replicas.isEmpty()) {
                throw new IllegalArgumentException("a ready pre-flight names at least one replica; "
                        + "an empty set is a refusal, not a plan with nothing in it");
            }
            replicas = List.copyOf(replicas);
        }
    }

    record Refused(ErrorClass reason, RuntimeRef runtimeRef) implements PreflightResult {

        public Refused {
            Objects.requireNonNull(reason, "refusal reason");
        }
    }
}
