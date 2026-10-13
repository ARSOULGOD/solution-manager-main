package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What {@link AgentCommandPort#enqueue} admits: command identity and the <em>keyed</em> hash only — never a rendered
 * byte and never a plain digest (I6; the bytes are handed over in memory at pull time, {@code AGW-D26}). The
 * generation is minted by the port, not supplied. A command's live identity is {@code (intent_key, operation)}.
 *
 * @param intentKey        the durable key; must equal {@code correlation.intentKey()}
 * @param keyedConfigHash  {@code hmacsha256v<n>:<hex>}; {@code null} for operations that carry no config
 * @param supersedesGeneration the generation this one replaces, when the engine knows it
 */
public record AgentCommandIntent(UUID agentId, AgentPlacementRef placement, String intentKey, AgentOperation operation,
        String keyedConfigHash, Instant deadline, OperationCorrelation correlation, Long supersedesGeneration) {
    public AgentCommandIntent {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(correlation, "correlation");
        if (intentKey == null || intentKey.isBlank()) {
            throw new IllegalArgumentException("intentKey is required");
        }
        if (!intentKey.equals(correlation.intentKey())) {
            throw new IllegalArgumentException("intentKey must equal correlation.intentKey");
        }
    }
}
