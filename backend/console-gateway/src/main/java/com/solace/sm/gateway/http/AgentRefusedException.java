package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * An agent operation refused for a reason the caller can act on (plan 015 T4.3). One reason, one {@link ErrorCode}, one FIXED
 * detail: nothing the request carried (a name, a path, an id) is ever echoed, and no fact about another agent or target is (I6, I5).
 * Every refusal here is decided before anything is written.
 */
public final class AgentRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** A live target or another pending token of the organization already holds the name. */
        NAME_TAKEN(ErrorCode.CONFLICT, "a target or a pending enrolment already holds this target name"),
        /** The solution or the package version is not one the organization has loaded. */
        SOLUTION_NOT_LOADED(ErrorCode.VALIDATION_FAILED, "the solution or its package version is not loaded in this organization"),
        /** An envelope value outside its grammar (the same {@code 422} as {@code registerTarget}'s). The field is a fixed identifier. */
        CONFIG_ROOT_INVALID(ErrorCode.VALIDATION_FAILED, "field 'allowedConfigRoot' failed validation"),
        CONTAINER_PREFIX_INVALID(ErrorCode.VALIDATION_FAILED, "field 'allowedContainerPrefix' failed validation"),
        /** {@code drainAgent}: no accepted session on this gateway. Nothing was queued. */
        NOT_CONNECTED(ErrorCode.AGENT_NOT_CONNECTED, "the agent has no live session; nothing was sent");

        private final ErrorCode code;
        private final String detail;

        Reason(ErrorCode code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public ErrorCode code() {
            return code;
        }
    }

    private final Reason reason;

    public AgentRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** The fixed detail text of the response body. */
    public String detail() {
        return reason.detail;
    }
}
