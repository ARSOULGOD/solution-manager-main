package com.solace.sm.dp.metrics;

/**
 * The metrics query and export budget is spent (plan 011 M-Q19, M-Q22 (6)): a caller waited its bound and got no
 * connection. The HTTP layer answers it as "busy, try again" (503 with a retry hint), never as an error in the
 * request. It names no query, tenant or value.
 */
public final class MetricsBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MetricsBusyException() {
        super("the metrics database budget is spent; try again shortly");
    }
}
