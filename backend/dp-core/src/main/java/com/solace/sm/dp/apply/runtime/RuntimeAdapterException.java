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
    private final transient PreImageChange preImageChange;
    private final boolean afterDispatch;

    public RuntimeAdapterException(ErrorClass errorClass) {
        this(errorClass, null);
    }

    /**
     * OR-146 (1): the failure came <b>after</b> the operation was handed to the runtime, so whether it took effect is
     * unknown. An adapter raises this only where it knows the verb left it (the agent adapter, once the command is
     * enqueued and its wait ends without a terminal answer); every other exception means nothing was sent or the runtime
     * answered definitively. The engine reads {@link #afterDispatch()} to land {@code APPLY_INDETERMINATE} for a restart
     * rather than a terminal {@code CONFIG_DIVERGED}.
     */
    public static RuntimeAdapterException afterDispatch(ErrorClass errorClass) {
        return new RuntimeAdapterException(errorClass, null, true);
    }

    /**
     * As above, also carrying what differs in a retained pre-image (OR-102): declared non-secret paths and a flag,
     * never a value. The message stays the bare classifier; the detail is read by the engine, which reports it.
     */
    public RuntimeAdapterException(ErrorClass errorClass, PreImageChange preImageChange) {
        this(errorClass, preImageChange, false);
    }

    private RuntimeAdapterException(ErrorClass errorClass, PreImageChange preImageChange, boolean afterDispatch) {
        super(Objects.requireNonNull(errorClass, "error class").token(), null, true, false);
        this.errorClass = errorClass;
        this.preImageChange = preImageChange;
        this.afterDispatch = afterDispatch;
    }

    /** True when the operation had already been dispatched to the runtime, so its effect is unknown (OR-146 (1)). */
    public boolean afterDispatch() {
        return afterDispatch;
    }

    /** What a refused pre-image differs in, when the refusal was a changed pre-image; empty otherwise. */
    public java.util.Optional<PreImageChange> preImageChange() {
        return java.util.Optional.ofNullable(preImageChange);
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
