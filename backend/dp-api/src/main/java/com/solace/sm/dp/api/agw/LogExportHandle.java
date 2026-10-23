package com.solace.sm.dp.api.agw;

import java.time.Duration;
import java.util.Optional;

/**
 * A pull-based source of export chunks. Pulling is the backpressure: the implementation grants the agent more credit only as
 * chunks are taken, so a slow consumer slows the agent and nothing is buffered without bound.
 *
 * <p>Read it with {@link #next}: a chunk comes back while one is available; an empty answer with {@link #state()} still
 * {@link State#OPEN} means "nothing yet, ask again"; an empty answer with any other state means the export has ended and
 * why. An export ends without a final chunk when it is revoked, cancelled, or its session or {@code Logs} stream ends (the
 * agent sends none), so a consumer must treat anything but {@link State#COMPLETE} as an incomplete download and may resume
 * from the last chunk's {@code resumeSeq}.
 */
public interface LogExportHandle extends AutoCloseable {
    enum State {
        /** Still running. */
        OPEN,
        /** The agent's final chunk was received. */
        COMPLETE,
        /** The agent refused the export; see {@link #unavailableReason()}. */
        UNAVAILABLE,
        /** The deadline passed before the final chunk. */
        TIMED_OUT,
        /** The total-size bound was reached; the export was stopped. */
        SIZE_LIMIT,
        /** {@link #cancel()} was called. */
        CANCELLED,
        /** The agent's session or log stream ended first. */
        SESSION_LOST
    }

    /** The next chunk, waiting up to {@code wait}; empty when none arrived in time or the export has ended (check {@link #state()}). */
    Optional<AgentLogChunk> next(Duration wait) throws InterruptedException;

    /** Why the stream ended, or {@link State#OPEN}. Chunks received before the end may still be waiting for {@link #next}. */
    State state();

    /** The fixed reason code when {@link #state()} is {@link State#UNAVAILABLE} (same vocabulary as {@link AgentLogPage}). */
    Optional<String> unavailableReason();

    /** Stops the export and tells the agent. Idempotent. */
    void cancel();

    @Override
    default void close() {
        cancel();
    }
}
