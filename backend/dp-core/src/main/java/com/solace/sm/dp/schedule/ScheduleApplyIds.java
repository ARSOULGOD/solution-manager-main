package com.solace.sm.dp.schedule;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The id of the ordinary apply a schedule's fire admits, pre-minted BEFORE the engine is asked ({@code OR-125} (5)).
 *
 * <p><b>Why derived, not random.</b> A fire is two commits: the engine's admission (its own transaction) and the schedule
 * row's move to {@code ADMITTED}. A crash between them used to leave a schedule {@code PENDING} for an apply that ran; the
 * retry's ordinary admission was then refused by the very fences the first one raised, and the row later closed
 * {@code REFUSED} or {@code MISSED} for work that had happened. With the id a pure function of the schedule's id, every
 * attempt, in this process or the next, admits under the SAME id: the engine already answers an admission under an id that
 * exists as "already admitted" and writes nothing ({@code AdmissionService}), and the schedule reconciles instead of
 * firing a second time. Nothing needs storing, so no migration.
 *
 * <p><b>Why here, and not supplied by the caller.</b> The data plane derives it, so the id a schedule can claim is only ever
 * its own: a caller-supplied id would let a caller attach any existing apply to a schedule. The gateway's
 * {@code ScheduleFirer} stays the one caller of {@code admit}, and the id is minted at the moment that call reaches the data
 * plane, before the engine is. The reserved-id overloads are public on the {@code dp-api} port, so "only the schedule uses them"
 * is a build rule rather than a convention: {@code ApplyScheduleBoundaryTest} fails for any caller outside this package (W5 review
 * W5A-3).
 *
 * <p>A name-based UUID (version 3, MD5 over a namespaced string): not a secret and not time-ordered, which an
 * {@code apply_id} is only documented to be for index locality, never relied on for ordering.
 */
public final class ScheduleApplyIds {

    private static final String NAMESPACE = "sm.apply-schedule.fire:";

    private ScheduleApplyIds() {
    }

    /** The apply id the fire of {@code scheduleId} admits under: the same every time, different for every schedule. */
    public static UUID forSchedule(UUID scheduleId) {
        Objects.requireNonNull(scheduleId, "scheduleId");
        return UUID.nameUUIDFromBytes((NAMESPACE + scheduleId).getBytes(StandardCharsets.UTF_8));
    }
}
