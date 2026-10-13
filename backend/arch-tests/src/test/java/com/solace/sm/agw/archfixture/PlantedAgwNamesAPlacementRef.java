package com.solace.sm.agw.archfixture;

import com.solace.sm.dp.api.agw.AgentPlacementRef;

/** Planted violation: the gateway naming the data plane's placement ref (rule {@code agentGatewayNeverNamesDataPlaneInternals}). */
public final class PlantedAgwNamesAPlacementRef {
    public Class<?> forbidden() {
        return AgentPlacementRef.class;
    }
}
