package com.solace.sm.dp.api.inventory;

import java.util.List;
import java.util.UUID;

/** An instance and its live placements. Carries no credential reference: that is internal to the data plane. */
public record InstanceView(UUID instanceId, String name, UUID environmentId, UUID catalogSolutionId,
        String declaredPackageVersion, InstanceLifecycle lifecycle, AcquisitionMode acquisition,
        List<PlacementView> placements, long stateVersion, String role) {

    public InstanceView(UUID instanceId, String name, UUID environmentId, UUID catalogSolutionId,
            String declaredPackageVersion, InstanceLifecycle lifecycle, AcquisitionMode acquisition,
            List<PlacementView> placements, long stateVersion) {
        this(instanceId, name, environmentId, catalogSolutionId, declaredPackageVersion, lifecycle, acquisition,
                placements, stateVersion, null);
    }

    public InstanceView {
        placements = List.copyOf(placements);
    }
}
