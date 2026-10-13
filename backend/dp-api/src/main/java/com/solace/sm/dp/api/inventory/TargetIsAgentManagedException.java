package com.solace.sm.dp.api.inventory;

/**
 * A placement was added to, or an instance declared on, an agent target (plan 015 T3.5). {@code 409 TARGET_IS_AGENT_MANAGED}: placements on an agent target come only from {@code OR-27} discovery in v1. Nothing was written. Names no target, host, agent or placement.
 */
public final class TargetIsAgentManagedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TargetIsAgentManagedException() {
        super("placements on an agent target come only from discovery");
    }
}
