package com.solace.sm.dp.api.inventory;

import java.util.UUID;

/** Gateway-to-DP seam for a target's runtime-specific, non-secret settings. */
public interface DeploymentTargetRuntimeAdministration {
    void upsertDeploymentTarget(UUID organizationId, DeploymentTargetRuntimeSpec spec, UUID actor);
    void retireDeploymentTarget(UUID organizationId, UUID targetId, UUID actor);
}
