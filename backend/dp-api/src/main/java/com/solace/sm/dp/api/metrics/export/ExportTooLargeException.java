package com.solace.sm.dp.api.metrics.export;

/**
 * The export would write more rows than the limit (plan 011 M-Q22 (4)): the typed {@code 422 EXPORT_TOO_LARGE}
 * "narrow the range", carrying {@code {projectedRows, limit}}. Both are counts: no metric, instance or tenant is named.
 *
 * <p>Raised by {@code plan} before any byte is written. It is also the one thing that can end a stream early for size: the
 * projection is an upper bound, and the stream counts what it actually writes, so a series whose rows outran its declared
 * interval cannot push the export past the limit.
 */
public final class ExportTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long projectedRows;
    private final long limit;

    public ExportTooLargeException(long projectedRows, long limit) {
        super("the export exceeds the row limit; narrow the range");
        this.projectedRows = projectedRows;
        this.limit = limit;
    }

    public long projectedRows() {
        return projectedRows;
    }

    public long limit() {
        return limit;
    }
}
