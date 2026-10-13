package com.solace.sm.dp.api.apply;

import java.time.Instant;
import java.util.UUID;

/**
 * One schedule as {@code listApplySchedules} reports it. Deliberately without the submitter's or the canceller's
 * principal id and without the outage confirmation: those are the record's audit (kept in the database), not something
 * every {@code config.view} holder of the instance is shown.
 *
 * @param operation          {@code apply} or {@code rollback}
 * @param generation         an apply's generation; null for a rollback
 * @param rollbackApplyId    a rollback's target by apply, or null
 * @param rollbackGeneration a rollback's target by generation, or null
 * @param strategy           the INNER strategy: one of the engine's four
 * @param state              {@code PENDING}, {@code ADMITTED}, {@code CANCELLED}, {@code MISSED} or {@code REFUSED}
 * @param applyId            the ordinary apply admitted at fire; only for {@code ADMITTED}
 * @param targetGeneration   a rollback's target as resolved AT FIRE; null before it fired, and for an apply
 * @param admittedGeneration the generation the admitted apply carries (for a rollback, the copy-forward's new number)
 * @param reason             the closed reason a schedule was {@code MISSED} or {@code REFUSED}, else null
 * @param resolvedAt         when it left {@code PENDING}; null while pending
 */
public record ScheduleView(UUID scheduleId, UUID placementId, String operation, Long generation, UUID rollbackApplyId,
        Long rollbackGeneration, String strategy, Instant notBefore, Instant notAfter, Instant requestedAt,
        String state, UUID applyId, Long targetGeneration, Long admittedGeneration, String reason, Instant resolvedAt,
        long stateVersion) {
}
