package com.solace.sm.dp.api.metrics.export;

import java.util.Objects;

/**
 * An export was refused before it wrote anything, because a concurrency bound is spent (plan 011 M-Q22 (3) and (6)).
 * Names the CLASS of bound only, never an organization, an install count or a query.
 *
 * <p>{@link Reason#ORGANIZATION} and {@link Reason#INSTALL} are the two export caps (1 per organization, 2 per install), the
 * {@code 429 RATE_LIMITED} reason {@code export-concurrency}. {@link Reason#DATABASE_BUDGET} is the one shared
 * query-and-export connection budget (4) staying spent for the whole wait: a different condition ("the database is busy",
 * not "you already have an export running"), which the route may answer differently.
 */
public final class ExportBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The organization already has its allowed number of exports running. */
        ORGANIZATION,
        /** The install already has its allowed number of exports running. */
        INSTALL,
        /** No connection permit became free within the wait. */
        DATABASE_BUDGET
    }

    private final Reason reason;

    public ExportBusyException(Reason reason) {
        super("the export is refused because a concurrency bound is spent: " + Objects.requireNonNull(reason).name().toLowerCase());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
