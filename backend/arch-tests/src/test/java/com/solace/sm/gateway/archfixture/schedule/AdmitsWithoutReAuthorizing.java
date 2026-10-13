package com.solace.sm.gateway.archfixture.schedule;

import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.ScheduleFireResult;
import java.util.UUID;

/**
 * Planted violation (plan 012 T7, {@code OR-122} (2); W3 review W3B-2): a "fire now" route that calls
 * {@code ApplySchedulePort#admit} without the firer's re-{@code require} of the submitter's verbs. {@code admit} runs an
 * ordinary apply under the SUBMITTER's name with no authorization of its own, so a second caller would run a deactivated
 * submitter's schedule (W4). {@code ApplyScheduleBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class AdmitsWithoutReAuthorizing {

    private AdmitsWithoutReAuthorizing() {
    }

    public static ScheduleFireResult fireNow(ApplySchedulePort port, UUID organization, UUID schedule) {
        return port.admit(organization, schedule);
    }
}
