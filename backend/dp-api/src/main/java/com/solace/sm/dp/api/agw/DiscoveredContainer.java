package com.solace.sm.dp.api.agw;

import java.util.List;
import java.util.Map;

/**
 * One container the agent found on its host: facts, not conclusions ({@code AGW-D30}) — <b>every field is
 * agent-supplied</b>. {@code labels} (only {@code com.solace.sm.*} keys leave the host) never resolve a tenant,
 * environment or instance; the DP derives every placement field itself (I5).
 */
public record DiscoveredContainer(String containerId, String containerName, String imageRef,
        Map<String, String> labels, List<Mount> mounts, List<PublishedPort> publishedPorts) {
    public DiscoveredContainer {
        labels = Map.copyOf(labels == null ? Map.of() : labels);
        mounts = List.copyOf(mounts == null ? List.of() : mounts);
        publishedPorts = List.copyOf(publishedPorts == null ? List.of() : publishedPorts);
    }

    /** A bind mount. */
    public record Mount(String source, String destination) { }

    /** A published port ({@code hostPort} is the host side). */
    public record PublishedPort(int containerPort, int hostPort) { }
}
