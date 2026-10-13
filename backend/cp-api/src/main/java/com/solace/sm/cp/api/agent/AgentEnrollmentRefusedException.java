package com.solace.sm.cp.api.agent;

import java.util.Objects;

/**
 * An enrolment the control plane refuses, by reason. The message is a fixed sentence per reason: it never echoes the
 * token, a digest, a name or a row (I6).
 */
public final class AgentEnrollmentRefusedException extends RuntimeException {

    public enum Reason {
        /** No active registration holds this token. */
        UNKNOWN_TOKEN("enrollment token is unknown or inactive"),
        /** The token's window passed before it was consumed. */
        EXPIRED("enrollment token has expired"),
        /** The token was consumed by a different signing request. */
        CONSUMED("enrollment token was already consumed"),
        /** A live target already holds the name the token reserves. */
        TARGET_NAME_TAKEN("the target name is already in use"),
        /** The signing request is malformed, its signature does not verify, or its key is not an accepted EC curve. */
        CSR_REFUSED("the signing request is not acceptable");

        private final String sentence;

        Reason(String sentence) {
            this.sentence = sentence;
        }
    }

    private final Reason reason;

    public AgentEnrollmentRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").sentence);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
