package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the bytes of an {@code APPLY}/{@code REMATERIALIZE} command come from at {@code pull} (plan 015 T2.1b declares the
 * seam, T3.1 implements it; Global constraint 1, {@code OR-126} A5).
 *
 * <p>SM persists NO rendered byte. The implementation hands over the {@code RenderedConfig} the apply engine already rendered
 * for the step, in memory, keyed by command id; only after an SM restart does it re-render, and then it returns the bytes only if
 * the re-render's keyed hash equals {@link Command#keyedConfigHash()} (else it refuses with
 * {@code agent.payload.render.changed}). The repository calling this never stores what it gets back.
 */
public interface AgentCommandPayloadSource {

    /** The durable facts of a command (no bytes, no plain digest): what a source needs to find or re-render its payload. */
    record Command(CommandId commandId, UUID agentId, AgentPlacementRef placement, AgentOperation operation,
            String intentKey, String keyedConfigHash, long generation) {
    }

    /** The in-memory payload for one pull. */
    CommandPayload payloadFor(Command command);

    /**
     * The plain digest a {@code CommandNotice} carries, if the in-memory hand-off still holds it. The notice's digest is a wire
     * value only (it is the agent's own comparand): it is never stored, so a notice for a command whose hand-off is gone carries none.
     */
    default Optional<String> plainDigestFor(Command command) {
        return Optional.empty();
    }
}
