package com.solace.sm.dp.api.agw;

import java.util.UUID;

/**
 * The DP's answer for one discovered container. {@code code} is the DP's closed {@link GatewayCode} vocabulary
 * (opaque to the agent); {@code placementId} is set once REGISTERED (and echoed for UNDECLARED / RETIRED), else
 * {@code null} — on the wire it is {@code binding_id}.
 */
public record DiscoveryResult(String containerId, State state, GatewayCode code, UUID placementId) {
    public enum State { PENDING, REGISTERED, REFUSED, ABANDONED, UNDECLARED, RETIRED }
}
