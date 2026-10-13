package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code Instance} ({@code Versioned} + the instance's own fields).
 *
 * <p>{@code solutionId} is the CATALOG id ({@code OR-41}). {@code declaredPackageVersion} is a bare {@link Long} or
 * {@link String} Jackson writes as itself ({@code PackageVersionId}, as in {@link Solution}). {@code acquisition} is
 * the mode only ({@code OR-82}); the data plane stores no address per instance.
 */
public record Instance(long stateVersion, @WireOptional Availability availability, UUID instanceId, String name,
        UUID solutionId, UUID environmentId, Object declaredPackageVersion, Acquisition acquisition,
        @WireOptional List<InstancePlacement> placements, @WireOptional String role) {

    public Instance(long stateVersion, Availability availability, UUID instanceId, String name,
            UUID solutionId, UUID environmentId, Object declaredPackageVersion, Acquisition acquisition,
            List<InstancePlacement> placements) {
        this(stateVersion, availability, instanceId, name, solutionId, environmentId, declaredPackageVersion,
                acquisition, placements, null);
    }

    public Instance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(declaredPackageVersion, "declaredPackageVersion");
        Objects.requireNonNull(acquisition, "acquisition");
        placements = placements == null ? null : List.copyOf(placements);
    }
}
