package com.solace.sm.gateway.http;

import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import java.util.UUID;

/**
 * The one place the gateway refuses an operation an agent placement cannot serve (plan 015 T3.3, Global constraint 8): log
 * level, read-config / accept-runtime, restore and rollback reach the host over SSH, and an agent target has no SSH binding. The
 * refusal is {@code 501 AGENT_OPERATION_UNSUPPORTED}, decided from the placement's transport on the data plane's own view BEFORE any
 * data-plane service that could reach a host is called, so nothing is read, written or connected. It is deliberately not a
 * {@code 200} with {@code availability: unavailable}, which would read as a host problem.
 */
final class AgentPlacements {

    private AgentPlacements() {
    }

    /** Refuses when the named placement of the instance is on an agent target; the placement is already known to be the instance's. */
    static void refuseIfAgent(InstanceView view, UUID placementId) {
        boolean agent = view.placements().stream()
                .filter(p -> p.placementId().equals(placementId)).anyMatch(PlacementView::isAgent);
        if (agent) {
            throw new GatewayRejectionException(ErrorCode.AGENT_OPERATION_UNSUPPORTED);
        }
    }
}
