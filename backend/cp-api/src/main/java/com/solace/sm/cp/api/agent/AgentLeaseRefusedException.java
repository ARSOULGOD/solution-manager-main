package com.solace.sm.cp.api.agent;

import java.util.Objects;

/**
 * A lease the control plane refuses, by reason, so the launcher can spell each for the gateway: a revocation must close
 * streams, a stale fleet epoch must only ask for a renewal. The message is a fixed sentence per reason (I6).
 */
public final class AgentLeaseRefusedException extends RuntimeException {

    public enum Reason {
        /** The registration is revoked. */
        REVOKED("agent registration is revoked"),
        /** The data plane's copy of the agent's revocation epoch is behind the control plane's. */
        AGENT_EPOCH_STALE("agent revocation epoch is stale"),
        /** The data plane's copy of the fleet revocation epoch is behind the control plane's. */
        FLEET_EPOCH_STALE("fleet revocation epoch is stale"),
        /** The request names a target other than the one the registration was enrolled onto. */
        TARGET_MISMATCH("agent target does not match the registration"),
        /** No consumed registration exists for this agent in this organization. */
        NOT_ENROLLED("agent is not enrolled"),
        /** The presented certificate is not the one the control plane issued to this agent. */
        CERTIFICATE_MISMATCH("certificate is not the issued certificate"),
        /** The issued certificate's validity has ended. */
        CERTIFICATE_EXPIRED("issued certificate has expired");

        private final String sentence;

        Reason(String sentence) {
            this.sentence = sentence;
        }
    }

    private final Reason reason;

    public AgentLeaseRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").sentence);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
