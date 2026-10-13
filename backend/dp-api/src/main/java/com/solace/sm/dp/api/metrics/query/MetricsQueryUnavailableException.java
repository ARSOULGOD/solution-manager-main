package com.solace.sm.dp.api.metrics.query;

import java.util.Objects;

/**
 * The query side cannot answer now (the contract's {@code 503}: the UI degrades, it never blanks the dashboard):
 * the shared database budget (M-Q22 (6)) stayed spent for the whole wait, or the statement hit its timeout (M-Q22 (1)).
 * It names no query, tenant or value.
 */
public final class MetricsQueryUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason { BUSY, TIMEOUT }

    private final Reason reason;

    public MetricsQueryUnavailableException(Reason reason) {
        super("the metrics query cannot be answered now: " + Objects.requireNonNull(reason, "reason").name().toLowerCase());
        this.reason = reason;
    }

    public MetricsQueryUnavailableException(Reason reason, Throwable cause) {
        this(reason);
        initCause(cause);
    }

    public Reason reason() {
        return reason;
    }
}
