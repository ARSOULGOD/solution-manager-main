package com.solace.sm.agw.archfixture;

import com.solace.sm.cp.api.CpMarker;

/** Planted violation: the gateway importing the control plane, even its api (rule {@code agentGatewayUsesDpApiOnly}). */
public final class PlantedAgwImportsCp {
    public Class<?> forbidden() {
        return CpMarker.class;
    }
}
