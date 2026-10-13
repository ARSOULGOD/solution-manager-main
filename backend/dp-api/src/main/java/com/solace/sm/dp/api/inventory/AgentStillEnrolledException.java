package com.solace.sm.dp.api.inventory;

/**
 * {@code retireTarget} on an agent target whose agent has not been revoked (plan 015 T3.5). {@code 409 AGENT_STILL_ENROLLED}: revoke the agent first. Nothing was changed. Names no target, host, agent or placement.
 */
public final class AgentStillEnrolledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentStillEnrolledException() {
        super("an agent target cannot be retired while its agent is enrolled");
    }
}
