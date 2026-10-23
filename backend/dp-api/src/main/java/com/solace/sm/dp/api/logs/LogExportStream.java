package com.solace.sm.dp.api.logs;

/**
 * A pull-based, bounded source of an export's text. Pulling is the backpressure: the next piece is requested of the agent
 * (one credit per chunk) only when {@link #next()} is called, so a slow reader slows the agent and nothing accumulates.
 *
 * <p>Every piece is whole lines, each ending in {@code \n}, in order. Besides log lines (an ISO-8601 instant, a space, the
 * line's text) the stream carries <b>marker lines</b>, which always start with {@value #MARKER_PREFIX} and whose text is
 * fixed by SM, never the agent's or the host's (I6): a gap in the collected log, a size or time bound that stopped the export,
 * or the agent's connection being lost, each with the {@code seq} to resume from where there is one (the {@code fromSeq} of
 * {@link LogExportService#open(java.util.UUID, java.util.UUID, java.util.UUID, long)}). Resuming may repeat up to one
 * chunk of lines already written (the resume point moves a chunk at a time). A stream that ENDS CLEANLY with no marker, or whose
 * last marker is {@code complete, with gaps}, is complete; every {@code truncated} and {@code incomplete} marker means it is not.
 * After the first byte an error or interrupt writes the {@code incomplete} marker where the response is still writable; an error
 * the caller cannot recover from then aborts the connection (no chunked terminator), so a cut-off file is never a clean one. The
 * size, time and lost-session markers end the body normally, after the marker.
 */
public interface LogExportStream extends AutoCloseable {

    /**
     * Why the export was refused before it produced a byte: known when {@link LogExportService#open} returns (the data plane
     * waits, bounded, for the agent's first answer), so the caller can answer with a real status instead of a file. {@code reason}
     * is fixed text that names no host, path or container (I6).
     */
    record Refusal(Kind kind, String reason) {
        public enum Kind {
            /** Too many exports at once; try again later. */
            CAPACITY,
            /** The placement is not this organization's, not live, or not an agent placement. */
            UNRESOLVABLE,
            /** The agent cannot serve it now: not connected, no log capability, not collecting, busy, silent, or its connection ended. */
            UNAVAILABLE
        }
    }

    /** Present when the export was refused; then {@link #next()} returns {@code null} at once and there is nothing to write. */
    default java.util.Optional<Refusal> refusal() {
        return java.util.Optional.empty();
    }

    /**
     * Ends the export for an abnormal reason of the caller's (an interrupt, a crash while writing) and returns the one fixed
     * {@code incomplete} marker line to write if the response is still writable, so that "no marker at the end" keeps meaning
     * "complete". Also does what {@link #close()} does.
     */
    default String abort() {
        close();
        return MARKER_PREFIX + " incomplete: the export ended unexpectedly\n";
    }

    /** What every marker line starts with. */
    String MARKER_PREFIX = "# sm-export:";

    /**
     * The next piece of text, blocking for a bounded time. The empty string is a keep-alive: nothing arrived yet, the export is
     * still open (the caller may write it, which also notices a client that went away). {@code null} means the export has
     * ended. It never blocks longer than the export's own deadline allows.
     */
    String next() throws InterruptedException;

    /** Stops the export, tells the agent and frees what it held. Idempotent. */
    @Override
    void close();
}
