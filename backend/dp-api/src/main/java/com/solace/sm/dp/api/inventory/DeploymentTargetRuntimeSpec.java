package com.solace.sm.dp.api.inventory;

import java.util.Objects;
import java.util.UUID;

/** Non-secret runtime binding for a deployment target. SSH access remains owned by the Connection projection. */
public record DeploymentTargetRuntimeSpec(UUID targetId, UUID connectionId, String runtime, String settingsJson) {
    public DeploymentTargetRuntimeSpec {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(settingsJson, "settingsJson");
        if (!runtime.equals("docker") && !runtime.equals("systemd")) {
            throw new IllegalArgumentException("runtime must be docker or systemd");
        }
    }
}
