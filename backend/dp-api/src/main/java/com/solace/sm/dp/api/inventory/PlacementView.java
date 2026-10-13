package com.solace.sm.dp.api.inventory;

import java.util.Optional;
import java.util.UUID;

/**
 * A live placement. {@code probeStrength} is empty when no HTTP probe is declared (T0.1 point 26: "none").
 *
 * <p>{@code transport} is the placement's TARGET's transport, {@value #SSH_DOCKER} or {@value #AGENT} (plan 015 T3.3): an operation
 * that reaches the host over SSH answers a coded refusal for {@code agent} before any connector is built.
 *
 * <p>Exactly one of {@code targetId} (a legacy Target, SSH or agent) and {@code connectionId} (a remote-server Connection,
 * plan 014) is set; the other is {@code null}. A connection-backed placement reaches its host over SSH, so its
 * {@code transport} is {@value #SSH_DOCKER}. (Both components were added on separate branches and combined by the
 * {@code OR-147} reconciliation.)
 */
public record PlacementView(UUID placementId, UUID targetId, String containerName,
        Optional<ProbeStrength> probeStrength, String transport, UUID connectionId) {

    public static final String SSH_DOCKER = "ssh-docker";
    public static final String AGENT = "agent";

    /** A target-backed SSH-docker placement: what every caller had before the agent transport and plan 014. */
    public PlacementView(UUID placementId, UUID targetId, String containerName, Optional<ProbeStrength> probeStrength) {
        this(placementId, targetId, containerName, probeStrength, SSH_DOCKER, null);
    }

    /** A target-backed placement with its target's transport (plan 015 T3.3). */
    public PlacementView(UUID placementId, UUID targetId, String containerName, Optional<ProbeStrength> probeStrength,
            String transport) {
        this(placementId, targetId, containerName, probeStrength, transport, null);
    }

    /** A placement that is target-backed ({@code connectionId} null) or connection-backed (plan 014). */
    public PlacementView(UUID placementId, UUID targetId, String containerName, Optional<ProbeStrength> probeStrength,
            UUID connectionId) {
        this(placementId, targetId, containerName, probeStrength, SSH_DOCKER, connectionId);
    }

    public PlacementView {
        transport = transport == null ? SSH_DOCKER : transport;
    }

    public boolean isAgent() {
        return AGENT.equals(transport);
    }
}
