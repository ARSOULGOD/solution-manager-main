package com.solace.sm.dp.api.logs;

/**
 * A pull-based, bounded source of an export's text. Pulling is the backpressure: the next piece is requested of the agent
 * (one credit per chunk) only when {@link #next()} is called, so a slow reader slows the agent and nothing accumulates.
 *
 * <p>Every piece is whole lines, each ending in {@code \n}, in order. Besides log lines (an ISO-8601 instant, a space, the
 * line's text) the stream carries <b>marker lines</b>, which always start with {@value #MARKER_PREFIX} and whose text is
 * fixed by SM, never the agent's or the host's (I6): a gap in the collected log, a size or time bound that stopped the export,
 * a refusal, or the agent's connection being lost, each with the {@code seq} to resume from where there is one. A stream
 * that ends without a marker is complete.
 */
public interface LogExportStream extends AutoCloseable {

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
