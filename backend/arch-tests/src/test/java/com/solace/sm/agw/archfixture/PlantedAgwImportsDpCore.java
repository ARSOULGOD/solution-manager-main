package com.solace.sm.agw.archfixture;

import com.solace.sm.dp.DpCoreMarker;

/** Planted violation: the gateway importing {@code dp-core} (rule {@code agentGatewayUsesDpApiOnly}). */
public final class PlantedAgwImportsDpCore {
    public Class<?> forbidden() {
        return DpCoreMarker.class;
    }
}
