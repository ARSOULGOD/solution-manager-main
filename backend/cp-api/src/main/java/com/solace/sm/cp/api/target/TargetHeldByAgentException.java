package com.solace.sm.cp.api.target;

/**
 * {@code retireTarget} on an agent target while an ACTIVE agent registration still holds it (plan 015 T3.5 fix round): the agent
 * could still replay its enrolment into a target the control plane had retired. Revoke the agent first. {@code 409
 * AGENT_STILL_ENROLLED}. Names no target, agent or organization.
 */
public final class TargetHeldByAgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TargetHeldByAgentException() {
        super("an agent target cannot be retired while its agent registration is active");
    }
}
