package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Objects;

/**
 * The agent's terminal answer to one command.
 *
 * @param requestId          the wire message id (idempotency of a replayed terminal ack)
 * @param containerStartedAt Docker {@code State.StartedAt}, host clock, no offset; only on an APPLIED APPLY,
 *                           REMATERIALIZE or RESTART, else {@code null}
 */
public record AgentAck(String requestId, long generation, AgentOutcome outcome, String detailCode,
        String detailMessage, Instant containerStartedAt) {
    public AgentAck {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(outcome, "outcome");
        detailCode = detailCode == null ? "" : detailCode;
        detailMessage = detailMessage == null ? "" : detailMessage;
    }

    /** Omits {@code detailMessage}: agent-supplied free text. */
    @Override
    public String toString() {
        return "AgentAck[requestId=" + requestId + ", generation=" + generation + ", outcome=" + outcome
                + ", detailCode=" + detailCode + ", containerStartedAt=" + containerStartedAt + "]";
    }
}
