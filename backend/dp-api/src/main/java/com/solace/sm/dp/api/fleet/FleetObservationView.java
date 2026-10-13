package com.solace.sm.dp.api.fleet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A fleet as observed. {@code stateVersion} is the {@code fleet_action} row's OWN stamp - an entity version, never the
 * maximum of the members' ({@code OR-55}).
 */
public record FleetObservationView(UUID fleetId, UUID solutionId, String state, String strategy, int batchSize,
        String onFailure, int currentBatch, Instant requestedAt, Instant terminalAt, long stateVersion,
        List<FleetMemberView> members) {

    public FleetObservationView {
        members = List.copyOf(members);
    }
}
