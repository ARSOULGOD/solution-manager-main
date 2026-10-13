package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What {@link AgentCommandPort#pull} returns: the command's identity plus its in-memory {@link CommandPayload}.
 * {@code toString} prints neither bytes nor digest.
 *
 * @param placementId on the agent path the wire's {@code binding_id} carries the placement id ({@code AGW-D25})
 */
public record CommandOffer(CommandId commandId, long generation, String idempotencyKey, UUID placementId,
        AgentOperation operation, Long supersedesGeneration, Instant deadline, OperationCorrelation correlation,
        CommandPayload payload) {
    public CommandOffer {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(payload, "payload");
    }

    @Override
    public String toString() {
        return "CommandOffer[commandId=" + commandId + ", generation=" + generation + ", placementId=" + placementId
                + ", operation=" + operation + ", supersedesGeneration=" + supersedesGeneration
                + ", deadline=" + deadline + ", payload=<redacted>]";
    }
}
