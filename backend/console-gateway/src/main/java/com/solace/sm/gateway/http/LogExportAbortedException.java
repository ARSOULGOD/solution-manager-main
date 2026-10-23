package com.solace.sm.gateway.http;

/**
 * A log download that ended abnormally AFTER its first byte (plan 016 task 10, re-review N-1). It is never turned into a
 * response: {@link LogExportAbortAdvice} lets it through, so the container closes the connection without the chunked
 * terminator and the client sees a failed download. The fixed {@code incomplete} marker has already been written where the
 * response was still writable.
 */
final class LogExportAbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LogExportAbortedException(Throwable cause) {
        super("the log export was aborted", cause);
    }
}
