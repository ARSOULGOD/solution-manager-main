package com.solace.sm.dp.schedule;

import com.solace.sm.dp.api.apply.ScheduleCommand.Operation;
import com.solace.sm.dp.api.apply.ScheduleReason;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code sm_data.apply_schedule} row (plan 012 T7, {@code V025}). A PENDING row carries no resolution column
 * ({@code cancelledBy}, {@code applyId}, {@code targetGeneration}, {@code admittedGeneration}, {@code reason} and
 * {@code resolvedAt} are null); a row that left PENDING never changes again.
 */
public record ScheduleRecord(UUID tenantId, UUID scheduleId, UUID instanceId, UUID placementId, Operation operation,
        Long generation, UUID rollbackApplyId, Long rollbackGeneration, String strategy, UUID outageConfirmedBy,
        Instant notBefore, Instant notAfter, UUID requestedBy, Instant requestedAt, State state, UUID cancelledBy,
        UUID applyId, Long targetGeneration, Long admittedGeneration, ScheduleReason reason, Instant resolvedAt,
        long stateVersion) {

    /** {@code V025}'s {@code apply_schedule_state}, verbatim. */
    public enum State {
        PENDING, ADMITTED, CANCELLED, MISSED, REFUSED
    }

    /** A schedule as it is first recorded: PENDING, unresolved, {@code stateVersion} 0 until the database stamps it. */
    public static ScheduleRecord pending(UUID tenantId, UUID scheduleId, UUID instanceId, UUID placementId,
            Operation operation, Long generation, UUID rollbackApplyId, Long rollbackGeneration, String strategy,
            UUID outageConfirmedBy, Instant notBefore, Instant notAfter, UUID requestedBy, Instant requestedAt) {
        return new ScheduleRecord(tenantId, scheduleId, instanceId, placementId, operation, generation,
                rollbackApplyId, rollbackGeneration, strategy, outageConfirmedBy, notBefore, notAfter, requestedBy,
                requestedAt, State.PENDING, null, null, null, null, null, null, 0L);
    }

    /** The string the admitted apply carries as its outage confirmation: the confirming principal's id, or null. */
    public String outageConfirmation() {
        return outageConfirmedBy == null ? null : outageConfirmedBy.toString();
    }
}
