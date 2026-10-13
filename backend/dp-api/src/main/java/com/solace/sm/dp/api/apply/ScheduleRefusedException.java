package com.solace.sm.dp.api.apply;

import java.util.Objects;

/**
 * The data plane refused to record, or to cancel, a schedule for a reason the caller can act on (plan 012 T7). Not found
 * is {@link ApplyRefusedException} ({@code NOT_FOUND}); everything about the window and the schedule's state is here. The
 * message is fixed per reason: nothing the request carried, and no id, is ever in it ({@code I6}).
 */
public final class ScheduleRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** {@code notBefore} is not in the future: a schedule that is already due is an apply. */
        NOT_BEFORE_NOT_IN_THE_FUTURE,
        /** {@code notBefore} is further ahead than {@code sm.dp.apply.schedule.max-lead} (default 7 days). */
        NOT_BEFORE_TOO_FAR_AHEAD,
        /** {@code notAfter} is further ahead than {@code sm.dp.apply.schedule.max-lead} (at most 7 days, {@code OR-122} (2)). */
        NOT_AFTER_TOO_FAR_AHEAD,
        /** A cancel found the schedule no longer {@code PENDING}: it was admitted, missed, refused or cancelled already. */
        NOT_PENDING
    }

    private final transient Reason reason;

    public ScheduleRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
