package com.solace.sm.agw.archfixture;

import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import java.util.Optional;

/**
 * Planted violation, gateway-shaped: a second gateway class resolving a certificate to an identity through the PORT, which is
 * the bypass of the call-site rule on {@code resolveByThumbprint} (plan 015 T2.1b fix round, finding 5).
 */
public final class PlantedAgwResolvesIdentity {
    public Optional<AgentIdentity> resolve(AgentIdentityPort identities, String thumbprint) {
        return identities.resolve(thumbprint);
    }
}
