package com.solace.sm.gateway.archfixture;

import com.solace.sm.agw.archfixture.PlantedAgwMarker;

/** Planted violation (T1.2 review finding 2): console-gateway importing agent-gateway; only the launcher composes it (rule {@code gatewayUsesApisOnly}). */
public final class PlantedGatewayImportsAgw {
    public Class<?> forbidden() {
        return PlantedAgwMarker.class;
    }
}
