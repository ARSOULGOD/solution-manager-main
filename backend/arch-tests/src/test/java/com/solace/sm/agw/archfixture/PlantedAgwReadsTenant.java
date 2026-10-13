package com.solace.sm.agw.archfixture;

import com.solace.sm.dp.api.agw.AgentIdentity;
import java.util.UUID;

/** Planted violation: the gateway reading the organization out of an identity (rule {@code agentGatewayNeverReadsTheTenant}). */
public final class PlantedAgwReadsTenant {
    public UUID forbidden(AgentIdentity identity) {
        return identity.tenantId();
    }
}
