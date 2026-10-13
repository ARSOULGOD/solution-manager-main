package com.solace.sm.launcher.archfixture;

import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deliberate violation: a fourth cross-plane adapter, a second implementation of a port the control plane answers (BE-D69). */
public final class BadFourthCpAdapter implements LeaseIssuer {

    @Override
    public LeaseGrant issue(AgentIdentity identity, AgentSessionRef session, Instant agentClock, String requestId,
            String consumedNonce, List<UUID> placementIds, com.solace.sm.dp.api.agw.AgentRequestOrigin origin) {
        throw new UnsupportedOperationException();
    }
}
