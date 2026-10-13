package com.solace.sm.cp.agent;

import java.util.UUID;

/**
 * Derives a lease's single-use renewal nonce with a secret the control plane holds (plan 015 T2.2 follow-up from the T2.1b
 * review). The derivation is a pure function of its inputs under that secret, so a lost reply that is retried with the same
 * request id returns the same grant ({@code LeaseIssuer}'s replay contract); without the secret nobody can recompute the
 * chain from the request ids, which the DP stores in {@code agent_event.request_id} and which may reach logs.
 *
 * <p>The control plane never CHECKS a {@code consumedNonce}: it only mixes it in. The single-use check is the data
 * plane's alone, a constant-time digest compare on its side.
 */
public interface LeaseNonceSigner {

    /**
     * @param consumedNonce the nonce the agent is spending, or {@code null} for a first issue
     * @throws IllegalStateException the key is not available: no lease is issued without it (fail closed)
     */
    String nonce(UUID agentId, String requestId, String certificateThumbprint, String consumedNonce);

    /** Fails with {@link IllegalStateException} unless the key is present and long enough. */
    void verifyOperable();
}
