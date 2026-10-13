package com.solace.sm.dp.api.agw;

import java.util.Objects;

/**
 * The command an {@code enqueue} resolved to. {@code minted} is false when the intent key already had a live or
 * succeeded command and that one is returned instead (plan 015 P15-3).
 */
public record AgentCommandRef(CommandId commandId, long generation, boolean minted) {
    public AgentCommandRef {
        Objects.requireNonNull(commandId, "commandId");
    }
}
