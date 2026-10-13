package com.solace.sm.dp.api.agw;

import java.util.List;
import java.util.Optional;

public interface AgentIdentityPort {
    Optional<AgentIdentity> resolve(String certificateThumbprint);

    /**
     * Re-reads the control plane's registration for every given live session and applies what changed:
     * revocation ends the identity (lease removed, every later write refused {@code IDENTITY_REVOKED}); a
     * change to epochs or capabilities updates the identity, and the lease it no longer matches grants nothing:
     * the result is {@code RENEWAL_REQUIRED} and the agent's own renewal (which carries the nonce only it holds) issues the new
     * one. The data plane keeps only the digest of a nonce, so it does not re-issue a lease by itself; {@code REISSUED} is
     * not produced by the implementation built in plan 015 T2.1b. One batched read, so the cost
     * is per sweep, not per session. Throws {@code INTERNAL_UNAVAILABLE} when the control plane cannot be
     * read; the caller keeps the sessions it has and tries again.
     */
    List<IdentityReconciliation> reconcile(List<AgentSessionRef> sessions);
}
