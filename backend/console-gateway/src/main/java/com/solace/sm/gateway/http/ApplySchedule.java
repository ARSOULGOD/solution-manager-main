package com.solace.sm.gateway.http;

import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code ApplySchedule} (plan 012 T7): one scheduled apply or rollback. Optional components
 * are omitted when they do not apply, never sent as JSON null. Carries NO principal id (neither the submitter's nor the
 * canceller's) and no outage confirmation: those are the record's audit, kept in the database, and are not shown to
 * every {@code config.view} holder of the instance.
 *
 * @param operation  {@code apply} or {@code rollback}
 * @param strategy   the strategy that runs when the window opens: one of the engine's four, never {@code scheduled}
 * @param state      {@code PENDING}, {@code ADMITTED}, {@code CANCELLED}, {@code MISSED} or {@code REFUSED}
 * @param reason     the closed reason a schedule was {@code MISSED} or {@code REFUSED} ({@code schedule.window.missed},
 *                   {@code schedule.authority.revoked}, ...); a class, never an id or a value
 */
public record ApplySchedule(UUID scheduleId, UUID placementId, String operation, @WireOptional Long generation,
        @WireOptional UUID rollbackApplyId, @WireOptional Long rollbackGeneration, String strategy, String notBefore,
        String notAfter, String requestedAt, String state, @WireOptional UUID applyId,
        @WireOptional Long targetGeneration, @WireOptional Long admittedGeneration, @WireOptional String reason,
        @WireOptional String resolvedAt, long stateVersion) {
}
