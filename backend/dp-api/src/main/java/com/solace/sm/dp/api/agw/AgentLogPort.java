package com.solace.sm.dp.api.agw;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reads of a {@code host-local} agent's collected managed-integration logs, and the retention cap it keeps (plan 016,
 * spec 4 and 5). The gateway implements it; the data plane calls it and never sees a gRPC type (AGW-D2). The tenant is
 * resolved by the caller from its own view of the placement (I5): the {@code organizationId} here only scopes the lookup and
 * is never echoed to the agent.
 *
 * <p>A request travels only to a session that advertised {@code CAPABILITY_LOGS} and whose lease names the placement, so an
 * agent that cannot answer is never sent one. Requests are held in memory only; they are not commands and are not journalled.
 */
public interface AgentLogPort {
    /** The agent's live session for this placement, if it is connected with log capability and the placement is in its lease. */
    Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId);

    /**
     * Asks for one page: the newest lines in the window ({@code after} and {@code before} are exclusive and optional),
     * oldest first. {@code limit} 0 means no line limit. Bad arguments (a null {@code deadline}, a negative {@code limit})
     * throw synchronously ({@code NullPointerException} or {@code IllegalArgumentException}). The future itself never completes
     * exceptionally: a refusal, a missing session, a busy agent or a missed deadline is a page with a reason. A reply that arrives after the deadline is ignored and counted.
     */
    CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit, Instant after,
            Instant before, Duration deadline);

    /**
     * Starts an export from {@code fromSeq} (0 or older than the ring starts at the oldest retained line), bounded by
     * {@code deadline} and by {@code maxBytes} of line text in total. An export is not a snapshot: it follows the live ring
     * until it catches up. A refusal shows as the handle's state, not as an exception.
     */
    LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes);

    /** Sets the on-disk cap for one placement (0 turns collection off). A session without the capability, or without the placement in its lease, is sent nothing. */
    void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes);
}
