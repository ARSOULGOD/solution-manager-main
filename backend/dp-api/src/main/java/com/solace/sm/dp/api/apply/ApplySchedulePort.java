package com.solace.sm.dp.api.apply;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The data plane's scheduled-apply surface (plan 012 T7; {@code OPS-Q9} Sch-2, {@code OPS-Q10} (c), {@code BE-D52}).
 *
 * <p>A scheduled change is a {@code sm_data.apply_schedule} row until its window opens; the gateway's
 * {@code ScheduleFirer} then re-requires the submitter's verbs and calls {@link #admit}, which admits an ORDINARY apply
 * through {@link ApplyService} (the same admission, fences, generation monotonicity, journal and events). Nothing here
 * holds a fence while a schedule waits, and a scheduled rollback is copied forward AT FIRE, not at submit.
 *
 * <p>Every method is scoped to {@code organizationId} ({@code I5}) except {@link #tenantsWithDueSchedules}, which is the
 * ONE cross-organization read this feature adds ({@code OR-122} (2)): it answers tenant ids and nothing else, and an
 * architecture test holds it to a single caller. It is the second half of {@code OPS-Q10} (c): the data plane cannot call
 * {@code cp-api} to re-authorize ({@code I7}) and the gateway cannot read {@code sm_data}, so the gateway enumerates
 * through this port and the data plane admits through it.
 */
public interface ApplySchedulePort {

    /**
     * Records a schedule. Validated NOW: the instance is active and the placement live, an apply's generation is staged,
     * a rollback's target is eligible (so a mistake is a refusal at submit, not a silent failure at 02:00), and the window
     * is in the future with BOTH ends at most {@code sm.dp.apply.schedule.max-lead} ahead ({@code OR-122} (2): at most 7 days,
     * a ceiling the configuration may only lower). The fence is NOT consulted: nothing is held until it fires.
     *
     * @throws ApplyRefusedException    {@code NOT_FOUND}, {@code GENERATION_NOT_STAGED}, {@code ROLLBACK_TARGET_NOT_ELIGIBLE}
     * @throws ScheduleRefusedException {@code NOT_BEFORE_NOT_IN_THE_FUTURE}, {@code NOT_BEFORE_TOO_FAR_AHEAD} or
     *                                  {@code NOT_AFTER_TOO_FAR_AHEAD}
     */
    ScheduleAcceptedView schedule(UUID organizationId, UUID instanceId, ScheduleCommand command);

    /** The instance's schedules, newest first, at most {@code limit}. An instance with none, or not this organization's, answers empty. */
    List<ScheduleView> list(UUID organizationId, UUID instanceId, int limit);

    /**
     * Cancels a {@code PENDING} schedule of this instance (any {@code config.apply} holder, not only the submitter).
     *
     * @return the schedule as it reads now ({@code CANCELLED})
     * @throws ApplyRefusedException    {@code NOT_FOUND} when no such schedule belongs to this instance of this organization
     * @throws ScheduleRefusedException {@code NOT_PENDING} once it was admitted, missed, refused or cancelled
     */
    ScheduleView cancel(UUID organizationId, UUID instanceId, UUID scheduleId, UUID actor);

    // ---- the firer's four calls -------------------------------------------------------------------------------

    /**
     * The organizations that have a {@code PENDING} schedule whose window has opened at {@code now}. ⛔ The cross-organization
     * read: ONE caller, the gateway's {@code ScheduleFirer} ({@code ApplyScheduleBoundaryTest}); anything else that needs a
     * tenant list is a new tenant enumeration and needs an owner ruling.
     */
    List<UUID> tenantsWithDueSchedules(Instant now);

    /** This organization's {@code PENDING} schedules whose window has opened at {@code now}, oldest window first, at most {@code sm.dp.apply.schedule.due-batch} (default 100). */
    List<DueSchedule> due(UUID organizationId, Instant now);

    /**
     * Admits the schedule as an ordinary apply, under a lock on its row so two firers cannot both do it. Closes it
     * {@code MISSED} when the window has closed; leaves it {@code PENDING} ({@code DEFERRED}) when it has not opened yet or a
     * fence is busy; closes it {@code REFUSED} when the engine refuses it for a reason that will not change.
     *
     * <p>No caller passes a clock reading: the data plane reads its OWN clock AFTER it holds the row's lock, so the window is
     * decided (and {@code resolved_at} recorded) at the moment of admission and never against a reading taken before a
     * round trip or a lock wait (W3 review W3B-1). ⛔ This runs an ordinary apply under the SUBMITTER's name without
     * authorizing it: the gateway's {@code ScheduleFirer} re-requires the submitter's verbs first, and is the one caller
     * ({@code ApplyScheduleBoundaryTest}).
     */
    ScheduleFireResult admit(UUID organizationId, UUID scheduleId);

    /**
     * Closes a due schedule {@code REFUSED} with {@code reason}: the gateway's re-authorization found the submitter no longer
     * holds the verbs ({@link ScheduleReason#AUTHORITY_REVOKED}). A no-op when it is no longer {@code PENDING}.
     * Stamped with the data plane's own clock; one caller, the firer.
     */
    ScheduleFireResult refuse(UUID organizationId, UUID scheduleId, ScheduleReason reason);

    /** Closes a due schedule {@code MISSED}: its window had closed. A no-op when it is no longer {@code PENDING}; one caller, the firer. */
    ScheduleFireResult miss(UUID organizationId, UUID scheduleId);
}
