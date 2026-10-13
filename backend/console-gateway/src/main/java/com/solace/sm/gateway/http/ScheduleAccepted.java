package com.solace.sm.gateway.http;

import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code ScheduleAccepted} (plan 012 T7): the schedule is recorded and durable, and nothing
 * was admitted - there is no {@code applyId} until the window opens and the firer admits it. Observe the schedule at
 * {@code listApplySchedules}; the apply it becomes is then observed at {@code observeApply}.
 */
public record ScheduleAccepted(long stateVersion, UUID scheduleId) implements AcceptedRequest {
}
