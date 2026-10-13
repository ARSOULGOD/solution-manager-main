package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.ProbeStrength;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One {@code sm_data.placement} row (immutable but for retirement). {@code mechanism} is one of the two
 * {@code ConfigLocation.Mechanism} constant names the table CHECKs; the probe fields are all present or all empty.
 *
 * <p>Exactly one of {@code targetId} and {@code connectionId} is set (V039's {@code placement_one_host_binding}): a
 * legacy Target, or a remote-server Connection resolved through {@code sm_data.connection_runtime} (plan 014).
 * <p>{@code createdBy} is the actor that added the placement (OR-125 (1)); {@code null} on a row that pre-dates V026,
 * which cannot be attributed after the fact. It is an actor id, not a user id: an agent's id fits the same column
 * (OR-128 (5)).
 */
public record PlacementRecord(UUID tenantId, UUID placementId, UUID instanceId, UUID targetId, String containerName,
        String remoteConfigDirectory, String containerConfigPath, String fileName, String mechanism,
        int retainedGenerations, Optional<String> healthKind, Optional<String> healthPath,
        Optional<Integer> healthPort, Optional<ProbeStrength> healthStrength, Instant createdAt, UUID createdBy,
        UUID connectionId) {

    /** A row with no recorded actor and no connection binding: legacy fixtures and pre-V026 rows. */
    public PlacementRecord(UUID tenantId, UUID placementId, UUID instanceId, UUID targetId, String containerName,
            String remoteConfigDirectory, String containerConfigPath, String fileName, String mechanism,
            int retainedGenerations, Optional<String> healthKind, Optional<String> healthPath,
            Optional<Integer> healthPort, Optional<ProbeStrength> healthStrength, Instant createdAt) {
        this(tenantId, placementId, instanceId, targetId, containerName, remoteConfigDirectory, containerConfigPath,
                fileName, mechanism, retainedGenerations, healthKind, healthPath, healthPort, healthStrength, createdAt,
                null, null);
    }

    /**
     * A target-backed row with its recorded actor (plan 015: an agent-discovered placement records the agent as
     * {@code createdBy}, OR-125 (1)) and no connection binding. Added by the OR-147 reconciliation, which combined plan
     * 015's sixteen-component record with plan 014's {@code connectionId}.
     */
    public PlacementRecord(UUID tenantId, UUID placementId, UUID instanceId, UUID targetId, String containerName,
            String remoteConfigDirectory, String containerConfigPath, String fileName, String mechanism,
            int retainedGenerations, Optional<String> healthKind, Optional<String> healthPath,
            Optional<Integer> healthPort, Optional<ProbeStrength> healthStrength, Instant createdAt, UUID createdBy) {
        this(tenantId, placementId, instanceId, targetId, containerName, remoteConfigDirectory, containerConfigPath,
                fileName, mechanism, retainedGenerations, healthKind, healthPath, healthPort, healthStrength, createdAt,
                createdBy, null);
    }

    /** Whether this placement is bound to a remote-server Connection rather than a Target. */
    public boolean connectionBacked() {
        return connectionId != null;
    }
}
