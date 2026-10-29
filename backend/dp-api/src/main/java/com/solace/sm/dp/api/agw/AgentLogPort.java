package com.solace.sm.dp.api.agw;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reads of a {@code host-local} agent's managed-integration logs. The agent keeps no copy: it reads the MI's own
 * {@code /actuator/logfile} on demand (plan 016, spec 4; plan 018, which removed the agent's log store and the per-placement
 * retention cap with it). The gateway implements it; the data plane calls it and never sees a gRPC type (AGW-D2). The tenant
 * is resolved by the caller from its own view of the placement (I5): the {@code organizationId} here only scopes the lookup
 * and is never echoed to the agent.
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
     * Starts an export from {@code fromSeq}, a byte offset in the MI's current log file (0 is its start), bounded by
     * {@code deadline} and by {@code maxBytes} of line text in total. An export is a snapshot of the current file: it ends at
     * the size the agent's first read reports, however the file grows after. Each chunk's resume point is the byte offset just
     * past its last line; a {@code fromSeq} beyond the file's size means the file was replaced, and the export starts again
     * at 0 behind a {@code DROPPED_OLDEST} gap (plan 018, spec 4.4). A refusal shows as the handle's state, not as an
     * exception.
     */
    LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes);
}
