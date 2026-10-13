package com.solace.sm.dp.api.agw;

/**
 * The outcome of checking one live session's authority against the control plane (revocation and
 * permission changes). {@code lease} is present only for {@link Status#REISSUED}.
 */
public record IdentityReconciliation(AgentSessionRef session, Status status, LeaseGrant lease,
        long agentRevocationEpoch, long fleetRevocationEpoch) {
    public enum Status {
        /** Nothing changed. */
        UNCHANGED,
        /**
         * Permissions changed; a fresh lease reflecting them was issued and persisted. Reserved: the implementation built in plan
         * 015 T2.1b answers {@link #RENEWAL_REQUIRED} instead, because it does not hold the previous nonce.
         */
        REISSUED,
        /** The agent is revoked (or unknown to the CP); the lease is gone and the session must end. */
        REVOKED,
        /** The session is no longer the current owner; the gateway must tell the agent it was superseded. */
        STALE,
        /**
         * The agent's lease has lapsed (or is missing) while its identity is in good standing, or no longer names the live placement
         * set of the agent's target (a placement added or retired since it was minted; plan 015 W4 lane D): it must be prompted to renew.
         */
        RENEWAL_REQUIRED
    }

    public static IdentityReconciliation unchanged(AgentSessionRef session) {
        return new IdentityReconciliation(session, Status.UNCHANGED, null, 0, 0);
    }
}
