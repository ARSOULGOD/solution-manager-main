package com.solace.sm.dp.schedule;

import com.solace.sm.dp.api.apply.ScheduleReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port of scheduled applies ({@code apply_schedule}, plan 012 T7). The service in this package imports
 * no JDBC type ({@code jdbcIsConfinedToPersistencePackages}); {@code JdbcApplyScheduleRepository} implements it. Every
 * method but {@link #dueTenants} names the tenant ({@code I5}) and binds its parameters.
 *
 * <p>The state moves are CONDITIONAL on the row being {@code PENDING} and return the rows they changed, so a late writer
 * meets zero rows rather than the database's forward-only trigger (which stays the second enforcer).
 */
public interface ScheduleRows {

    /** Inserts a PENDING schedule; @return the {@code state_version} the database stamped on it. */
    long insert(ScheduleRecord row);

    /** The row, locked {@code FOR UPDATE} for the rest of the transaction (what makes two firers, or a firer and a cancel, take turns). */
    Optional<ScheduleRecord> lock(UUID tenantId, UUID scheduleId);

    /** As {@link #lock}, but only when the schedule belongs to THIS instance: another instance's id is not found. */
    Optional<ScheduleRecord> lockOfInstance(UUID tenantId, UUID instanceId, UUID scheduleId);

    /** One schedule of this instance, any state, unlocked. */
    Optional<ScheduleRecord> find(UUID tenantId, UUID instanceId, UUID scheduleId);

    /** The instance's schedules, newest first, at most {@code limit}. */
    List<ScheduleRecord> list(UUID tenantId, UUID instanceId, int limit);

    /**
     * The organizations with a PENDING schedule whose window has opened at {@code now}. ⛔ The ONE cross-organization read of
     * this feature ({@code OR-122} (2)); its only caller is the service's {@code tenantsWithDueSchedules}
     * ({@code ApplyScheduleBoundaryTest}). Named differently from the port's method on purpose, so the two rules read two names.
     */
    List<UUID> dueTenants(Instant now);

    /** This tenant's PENDING schedules whose window has opened at {@code now}, oldest {@code not_before} first. */
    List<ScheduleRecord> due(UUID tenantId, Instant now, int limit);

    /** PENDING {@code ->} ADMITTED; returns rows changed. */
    int admitted(UUID tenantId, UUID scheduleId, UUID applyId, Long targetGeneration, long admittedGeneration, Instant at);

    /** PENDING {@code ->} MISSED or REFUSED with {@code reason}; returns rows changed. */
    int close(UUID tenantId, UUID scheduleId, ScheduleRecord.State state, ScheduleReason reason, Instant at);

    /** PENDING {@code ->} CANCELLED; returns rows changed. */
    int cancel(UUID tenantId, UUID scheduleId, UUID cancelledBy, Instant at);
}
