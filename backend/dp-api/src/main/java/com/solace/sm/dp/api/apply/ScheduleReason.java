package com.solace.sm.dp.api.apply;

/**
 * The closed vocabulary of why a schedule was {@code MISSED} or {@code REFUSED} (plan 012 T7; {@code V025}'s CHECK is the
 * same list). A wire string like {@code schedule.authority.revoked}: it names a class and never an id, a value or a person.
 */
public enum ScheduleReason {
    /** The submitter lost a verb, or is deactivated: the grant that admitted the schedule is gone ({@code W4}). */
    AUTHORITY_REVOKED("schedule.authority.revoked"),
    /** The instance is no longer active, or the placement no longer live. */
    TARGET_GONE("schedule.target.gone"),
    /** The monotonicity fence: a generation at or above this one reached the placement meanwhile ({@code W5}). */
    GENERATION_SUPERSEDED("schedule.generation.superseded"),
    /** The generation no longer exists for this instance. */
    GENERATION_NOT_STAGED("schedule.generation.not-staged"),
    /** The rollback target is no longer a generation written to the placement. */
    ROLLBACK_TARGET_NOT_ELIGIBLE("schedule.rollback.target-not-eligible"),
    /** The window closed before the schedule could run: SM was down, or a fence stayed busy until {@code notAfter}. */
    WINDOW_MISSED("schedule.window.missed");

    private final String wire;

    ScheduleReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static ScheduleReason fromWire(String wire) {
        for (ScheduleReason r : values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }
        throw new IllegalArgumentException("not a schedule reason: " + wire);
    }
}
