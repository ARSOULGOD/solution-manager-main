package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "A command is available" — what the gateway tells the agent before it pulls. Carries the plain digest of the
 * bytes the agent will be offered (the wire's {@code config_sha256}); {@code toString} omits it.
 */
public record CommandNotice(CommandId commandId, UUID placementId, long generation, AgentOperation operation,
        String configSha256, Instant deadline, OperationCorrelation correlation) {
    public CommandNotice {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String toString() {
        return "CommandNotice[commandId=" + commandId + ", placementId=" + placementId + ", generation=" + generation
                + ", operation=" + operation + ", deadline=" + deadline + ", configSha256=<redacted>]";
    }
}
