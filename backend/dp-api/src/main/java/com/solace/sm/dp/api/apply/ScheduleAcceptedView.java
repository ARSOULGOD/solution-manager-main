package com.solace.sm.dp.api.apply;

import java.util.UUID;

/** A recorded schedule: its id, and the {@code stateVersion} it was stamped with when it committed. */
public record ScheduleAcceptedView(UUID scheduleId, long stateVersion) {
}
