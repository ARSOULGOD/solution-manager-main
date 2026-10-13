package com.solace.sm.dp.api.agw;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AgentCommandPort {
    /**
     * Durable queue admission, keyed by {@code intent.intentKey()}; returns the live or succeeded command for the
     * same key instead of minting another (P15-3). <b>Byte-free</b> (I6): no rendered byte crosses this call.
     */
    AgentCommandRef enqueue(AgentCommandIntent intent);

    /** Supersedes QUEUED/OFFERED commands of keys other than {@code keepIntentKey}; DELIVERED commands are left alone. */
    void supersedeOutstanding(AgentPlacementRef placement, String keepIntentKey);

    /** An unfinished command last notified more than {@code renotifyAfter} ago is notified again (AGW-D21). */
    List<CommandNotice> pendingFor(AgentSessionRef ref, int limit, Duration renotifyAfter);

    /** One set-based read over the live sessions: the agents with anything to be told about. The implementation binds
     * {@code (tenant, agent)} pairs; it never reads across organizations with a bare {@code IN (agent ids)}. */
    Set<UUID> agentsWithDeliverableWork(List<AgentSessionRef> sessions, Duration renotifyAfter);

    /** The bytes come from the in-memory hand-off ({@code AgentCommandPayloadSource}); they are never stored. */
    CommandOffer pull(AgentSessionRef ref, CommandId id);

    /** {@code requestId} is the wire message id (stored as {@code received_request_id}). */
    void received(AgentSessionRef ref, String requestId, CommandId id, long wireGeneration);

    CommandAckResult ack(AgentSessionRef ref, CommandId id, AgentAck ack);

    /** Scoped by the placement's own tenant (I5): a command id of another organization answers empty. */
    Optional<CommandCompletion> completion(AgentPlacementRef placement, CommandId id);
}
