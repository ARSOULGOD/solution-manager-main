package com.solace.sm.gateway.http;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Non-secret metadata update for a linked deployment target. */
public record UpdateDeploymentTargetRequest(String name, UUID connectionId, String runtime, String configRoot,
        String containerPrefix, String serviceNamePattern) {

    public UpdateDeploymentTargetRequest {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("name is required and must be at most 128 characters");
        }
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (runtime == null || !Set.of("docker", "systemd").contains(runtime)) {
            throw new IllegalArgumentException("runtime must be docker or systemd");
        }
        if (configRoot == null || !configRoot.startsWith("/") || configRoot.contains("\u0000")
                || configRoot.contains("..")) {
            throw new IllegalArgumentException("configRoot must be an absolute path without '..'");
        }
        if (runtime.equals("docker")) {
            if (containerPrefix == null || containerPrefix.isBlank() || !containerPrefix.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("containerPrefix is invalid");
            }
            if (serviceNamePattern != null && !serviceNamePattern.isBlank()) {
                throw new IllegalArgumentException("serviceNamePattern is only valid for systemd");
            }
        } else {
            if (serviceNamePattern == null || serviceNamePattern.isBlank() || serviceNamePattern.length() > 128
                    || serviceNamePattern.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("serviceNamePattern is invalid");
            }
            try {
                Pattern.compile(serviceNamePattern);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("serviceNamePattern is not a valid pattern");
            }
            if (containerPrefix != null && !containerPrefix.isBlank()) {
                throw new IllegalArgumentException("containerPrefix is only valid for Docker");
            }
        }
    }

    @Override
    public String toString() {
        return "UpdateDeploymentTargetRequest[name=" + name + ", connectionId=" + connectionId + ", runtime="
                + runtime + ", configRoot=" + configRoot + ", containerPrefix=" + containerPrefix
                + ", serviceNamePattern=" + serviceNamePattern + "]";
    }
}
