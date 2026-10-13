package com.solace.sm.dp.archfixture;

import com.solace.sm.agw.archfixture.PlantedAgwMarker;

/** Planted violation: a data-plane class importing the gateway (rule {@code dpNeverImportsAgentGateway}). */
public final class PlantedDpImportsAgw {
    public Class<?> forbidden() {
        return PlantedAgwMarker.class;
    }
}
