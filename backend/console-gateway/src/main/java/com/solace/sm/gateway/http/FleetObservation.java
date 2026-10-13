package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code FleetObservation}. {@code stateVersion} is the {@code fleet_action} row's OWN stamp
 * - an entity version, never the maximum of the members' ({@code OR-55}). {@code availability} is required by the contract
 * (unlike the other {@code Versioned} bodies): the fleet is read from the journal, so it is {@code live}.
 */
public record FleetObservation(long stateVersion, Availability availability, UUID fleetId, UUID solutionId,
        String state, String strategy, int batchSize, String onFailure, int currentBatch, String requestedAt,
        @WireOptional String terminalAt, List<FleetMember> members) {

    public FleetObservation {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(fleetId, "fleetId");
        Objects.requireNonNull(solutionId, "solutionId");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
