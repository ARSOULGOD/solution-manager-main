package com.solace.sm.agw.archfixture;

import com.solace.sm.dp.api.agent.AgentSessionFacts;

/** Planted violation: the gateway naming the console's tenant-keyed session read (rule {@code agentGatewayNeverNamesDataPlaneInternals}). */
public final class PlantedAgwNamesSessionFacts {
    public Class<?> forbidden() {
        return AgentSessionFacts.class;
    }
}
