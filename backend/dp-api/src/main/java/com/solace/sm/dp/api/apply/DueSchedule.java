package com.solace.sm.dp.api.apply;

import java.time.Instant;
import java.util.UUID;

/**
 * A PENDING schedule whose window has opened: exactly what the gateway's {@code ScheduleFirer} needs to re-authorize its
 * submitter and to decide between admitting and closing it. Nothing in it is a value to render or a secret.
 *
 * @param requestedBy the submitter, whose verbs are asked again at fire ({@code OR-122} (2))
 * @param operation   {@code apply} (needs {@code config.apply}) or {@code rollback} (needs {@code config.rollback})
 * @param strategy    the inner strategy, which decides the secondary verbs ({@code instance.restart},
 *                    {@code instance.restart.overrideSafety})
 */
public record DueSchedule(UUID scheduleId, UUID instanceId, UUID requestedBy, ScheduleCommand.Operation operation,
        String strategy, Instant notBefore, Instant notAfter) {

    /** Whether the window has closed at {@code now}: such a schedule is closed {@code MISSED}, never run. */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(notAfter);
    }
}
