package com.solace.sm.dp.api.agw;

import java.time.Instant;

/** {@code toString} omits {@code detailMessage}, which is agent-supplied free text. */
public record CommandCompletion(CommandId commandId, long generation, AgentOperation operation,
        AgentOutcome outcome, String detailCode, String detailMessage, Instant completedAt) {
    public boolean terminal() { return outcome != null && completedAt != null; }

    @Override
    public String toString() {
        return "CommandCompletion[commandId=" + commandId + ", generation=" + generation + ", operation=" + operation
                + ", outcome=" + outcome + ", detailCode=" + detailCode + ", completedAt=" + completedAt + "]";
    }
}
