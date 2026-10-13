package com.solace.sm.dp.archfixture.schedule;

import com.solace.sm.dp.api.apply.ScheduleFireResult;
import com.solace.sm.dp.schedule.ApplyScheduleServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 012 T7, W3 review W3B-2): calls the port's methods through the public final concrete
 * {@code ApplyScheduleServiceImpl}, so the bytecode owner of each call is the implementation and NOT the interface. A rule
 * that matched only the interface (the first revision of {@code ApplyScheduleBoundaryTest}) would miss it.
 * {@code ApplyScheduleBoundaryTest} must report this class for both the tenant enumeration and the admission.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class CallsThePortThroughTheImplementation {

    private CallsThePortThroughTheImplementation() {
    }

    public static List<UUID> enumerate(ApplyScheduleServiceImpl service) {
        return service.tenantsWithDueSchedules(Instant.now());
    }

    public static ScheduleFireResult admit(ApplyScheduleServiceImpl service, UUID organization, UUID schedule) {
        return service.admit(organization, schedule);
    }
}
