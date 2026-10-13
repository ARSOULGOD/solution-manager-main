package com.solace.sm.dp.api.fleet;

import java.util.List;
import java.util.UUID;

/** A recorded fleet: its id, the {@code stateVersion} its row was stamped with at the submit commit, and its members. */
public record FleetAcceptedView(UUID fleetId, long stateVersion, String state, List<FleetMemberView> members) {

    public FleetAcceptedView {
        members = List.copyOf(members);
    }
}
