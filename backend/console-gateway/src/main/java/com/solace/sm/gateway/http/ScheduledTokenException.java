package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * {@code strategy: "scheduled"} was sent, and it is not a strategy (plan 012 T7; {@code OR-121} names {@code OR-53} and
 * {@code OR-60}): a schedule is a window on {@code submitApply} / {@code rollbackApply}, not a fifth way to restart. The
 * token stays in the contract's {@code Strategy} enum so that an old client's request is answered with this {@code 422}
 * naming the {@code schedule} field, rather than a generic {@code 400}. {@link Where} picks the fixed detail, so a caller
 * of an operation that has no schedule (restore, fleet) is not told to use a field it does not have.
 */
public final class ScheduledTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The operation the token arrived on. */
    public enum Where {
        APPLY("'scheduled' is not a strategy: give the window as the 'schedule' field and the strategy to run when it opens"),
        ROLLBACK("'scheduled' is not a strategy: give the window as the 'schedule' field and the strategy to run when it opens"),
        RESTORE("'scheduled' is not a strategy: restore-runtime cannot be scheduled"),
        FLEET("'scheduled' is not a strategy: a fleet apply cannot be scheduled");

        private final String detail;

        Where(String detail) {
            this.detail = detail;
        }

        String detail() {
            return detail;
        }
    }

    private final transient Where where;

    public ScheduledTokenException(Where where) {
        super(Objects.requireNonNull(where, "where").name());
        this.where = where;
    }

    Where where() {
        return where;
    }
}
