package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code FleetAccepted} ({@code Versioned} + {@code fleetId}, {@code state},
 * {@code members}): the fleet is recorded and durable. {@code stateVersion} is the {@code fleet_action} row's own stamp.
 * Every authorized member is {@code pending}: the scheduler admits batch 0 on its next pass, and progress is read through
 * {@code observeFleetApply}.
 */
public record FleetAccepted(long stateVersion, @WireOptional Availability availability, UUID fleetId, String state,
        List<FleetMember> members) {

    public FleetAccepted {
        Objects.requireNonNull(fleetId, "fleetId");
        Objects.requireNonNull(state, "state");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
