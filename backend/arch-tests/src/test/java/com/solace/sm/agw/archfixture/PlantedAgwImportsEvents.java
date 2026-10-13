package com.solace.sm.agw.archfixture;

import com.solace.sm.events.EventsMarker;

/** Planted violation (T1.2 review finding 10): the gateway importing another {@code com.solace.sm} module (rule {@code agentGatewayUsesDpApiOnly}). */
public final class PlantedAgwImportsEvents {
    public Class<?> forbidden() {
        return EventsMarker.class;
    }
}
