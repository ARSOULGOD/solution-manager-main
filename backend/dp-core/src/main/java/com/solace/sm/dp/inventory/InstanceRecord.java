package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeploymentMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code sm_data.instance_state} row. {@code stateVersion} is read, never written: the database stamps it.
 * {@code deploymentMode} is plan 014's per-instance choice (V039), {@code dp-remote} for every row before it.
 */
public record InstanceRecord(UUID tenantId, UUID instanceId, UUID environmentId, UUID catalogSolutionId,
        String solution, String declaredPackageVersion, String name, InstanceLifecycle lifecycle,
        AcquisitionMode acquisition, UUID createdBy, Instant createdAt, long stateVersion,
        DeploymentMode deploymentMode, String role) {

    /** A {@code dp-remote} instance: the arity every caller before plan 014 uses. */
    public InstanceRecord(UUID tenantId, UUID instanceId, UUID environmentId, UUID catalogSolutionId,
            String solution, String declaredPackageVersion, String name, InstanceLifecycle lifecycle,
            AcquisitionMode acquisition, UUID createdBy, Instant createdAt, long stateVersion) {
        this(tenantId, instanceId, environmentId, catalogSolutionId, solution, declaredPackageVersion, name, lifecycle,
                acquisition, createdBy, createdAt, stateVersion, DeploymentMode.DP_REMOTE);
    }

    public InstanceRecord {
        Objects.requireNonNull(deploymentMode, "deploymentMode");
        if (role != null && role.isBlank()) role = null;
    }

    public InstanceRecord(UUID tenantId, UUID instanceId, UUID environmentId, UUID catalogSolutionId,
            String solution, String declaredPackageVersion, String name, InstanceLifecycle lifecycle,
            AcquisitionMode acquisition, UUID createdBy, Instant createdAt, long stateVersion,
            DeploymentMode deploymentMode) {
        this(tenantId, instanceId, environmentId, catalogSolutionId, solution, declaredPackageVersion, name,
                lifecycle, acquisition, createdBy, createdAt, stateVersion, deploymentMode, null);
    }
}
