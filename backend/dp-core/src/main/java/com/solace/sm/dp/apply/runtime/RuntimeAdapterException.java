package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import java.util.Objects;

/**
 * An adapter could not complete an operation. Carries a <b>classifier and no cause</b>.
 *
 * <p>{@code I6}, at the adapter boundary. Plan 004 measured the same hole one layer in:
 * {@code PgConstraintViolation} had a sanitized message but still carried the {@code PSQLException}
 * as its cause, and Logback prints cause chains, so {@code log.error(msg, ex)} re-emitted
 * {@code DETAIL: Failing row contains (…)}. A Docker client exception or an HTTP response body
 * wrapped here would reintroduce that leak from the other side — the daemon echoes container names,
 * mount paths and, on some errors, command lines.
 */
public final class RuntimeAdapterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorClass errorClass;

    public RuntimeAdapterException(ErrorClass errorClass) {
        super(Objects.requireNonNull(errorClass, "error class").token(), null, true, false);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
