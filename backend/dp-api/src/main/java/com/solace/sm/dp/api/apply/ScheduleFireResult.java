package com.solace.sm.dp.api.apply;

import java.util.Objects;
import java.util.UUID;

/**
 * What {@link ApplySchedulePort#admit} did with one due schedule. {@code DEFERRED} is the only result that leaves the
 * schedule {@code PENDING}: a fence held the placement (an apply, a restart or a stop was in flight), and the next tick
 * tries again until {@code notAfter}, when the schedule closes {@code MISSED}.
 *
 * @param applyId the ordinary apply admitted; only for {@code ADMITTED}
 * @param reason  why it was closed; only for {@code REFUSED} and {@code MISSED}
 */
public record ScheduleFireResult(Outcome outcome, UUID applyId, ScheduleReason reason) {

    public enum Outcome {
        /** An ordinary apply was admitted; the schedule is {@code ADMITTED}. */
        ADMITTED,
        /** A fence was busy; the schedule stays {@code PENDING} and is retried on the next tick. */
        DEFERRED,
        /** The engine refused it for a reason that will not change; the schedule is {@code REFUSED}. */
        REFUSED,
        /** The window had closed; the schedule is {@code MISSED}. */
        MISSED,
        /** Another firer, or a cancel, got there first: the schedule is no longer {@code PENDING}. Nothing was done. */
        NOT_PENDING
    }

    public ScheduleFireResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.ADMITTED) != (applyId != null)) {
            throw new IllegalArgumentException("an applyId accompanies ADMITTED and nothing else");
        }
        if ((outcome == Outcome.REFUSED || outcome == Outcome.MISSED) != (reason != null)) {
            throw new IllegalArgumentException("a reason accompanies REFUSED and MISSED and nothing else");
        }
    }

    public static ScheduleFireResult admitted(UUID applyId) {
        return new ScheduleFireResult(Outcome.ADMITTED, applyId, null);
    }

    public static ScheduleFireResult deferred() {
        return new ScheduleFireResult(Outcome.DEFERRED, null, null);
    }

    public static ScheduleFireResult refused(ScheduleReason reason) {
        return new ScheduleFireResult(Outcome.REFUSED, null, reason);
    }

    public static ScheduleFireResult missed() {
        return new ScheduleFireResult(Outcome.MISSED, null, ScheduleReason.WINDOW_MISSED);
    }

    public static ScheduleFireResult notPending() {
        return new ScheduleFireResult(Outcome.NOT_PENDING, null, null);
    }
}
