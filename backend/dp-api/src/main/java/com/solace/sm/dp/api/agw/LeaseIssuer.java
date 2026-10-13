package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LeaseIssuer {
    /**
     * {@code placementIds} is the DP-supplied live set; the issuer signs exactly it (AGW-D24). {@code origin} is
     * carried for any {@code sm_control} audit write a lease issue makes (OR-25).
     *
     * <p>Identical inputs return an identical grant (in particular an identical nonce): the data plane keeps only the digest of a
     * nonce, so a retry after a lost reply is answered by asking again and comparing digests (plan 015 T2.1b). Documentation only;
     * the signature is unchanged.
     */
    LeaseGrant issue(AgentIdentity identity, AgentSessionRef session, Instant agentClock,
            String requestId, String consumedNonce, List<UUID> placementIds, AgentRequestOrigin origin);
}
