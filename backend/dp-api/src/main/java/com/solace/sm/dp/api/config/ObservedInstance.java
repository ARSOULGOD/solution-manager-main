package com.solace.sm.dp.api.config;

import java.util.List;
import java.util.Objects;

/**
 * What the data plane can observe about an instance, in the shape the designer projection needs (OR-34), carrying no
 * type from the control plane (I7: the gateway maps it). Every string is a closed token or a keyed digest, never a
 * configuration value.
 *
 * @param versionState    {@code VERSION_UNVERIFIED} for every {@code dp-remote} instance: SM reads no version from the
 *                        MI (I1), so it can neither confirm nor contradict the declared one
 * @param declaredVersion the MI version SM was told, or null (a package version id is not an MI version)
 * @param observedVersion the MI version read back, or null
 * @param config          the five rungs as opaque tokens; null where a rung is not reached
 * @param replicas        empty until health is polled (plan 010 T7.2)
 */
public record ObservedInstance(String versionState, String declaredVersion, String observedVersion, Rungs config,
        List<Replica> replicas) {

    public ObservedInstance {
        Objects.requireNonNull(versionState, "versionState");
        Objects.requireNonNull(config, "config");
        replicas = List.copyOf(Objects.requireNonNull(replicas, "replicas"));
    }

    public record Rungs(String desired, String rendered, String persisted, String restartObserved,
            String effectiveVerified) {
    }

    public record Replica(String id, String health) {
    }
}
