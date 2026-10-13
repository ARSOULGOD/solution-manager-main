package com.solace.sm.cp.archfixture;

import com.solace.sm.agw.archfixture.PlantedAgwMarker;

/** Planted violation (T1.2 review finding 2): a control-plane class importing the data-plane gateway (rule {@code cpNeverImportsAgentGateway}). */
public final class PlantedCpImportsAgw {
    public Class<?> forbidden() {
        return PlantedAgwMarker.class;
    }
}
