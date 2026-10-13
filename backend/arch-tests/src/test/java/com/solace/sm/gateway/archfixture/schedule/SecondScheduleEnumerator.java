package com.solace.sm.gateway.archfixture.schedule;

import com.solace.sm.dp.api.apply.ApplySchedulePort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 012 T7, {@code OR-122} (2)): a SECOND caller of
 * {@code ApplySchedulePort#tenantsWithDueSchedules}, the one cross-organization enumeration the scheduled-apply feature adds.
 * Its one named caller is the gateway's {@code ScheduleFirer}; anything else that wants a tenant list is a new tenant
 * enumeration and needs an owner ruling. {@code ApplyScheduleBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class SecondScheduleEnumerator {

    private SecondScheduleEnumerator() {
    }

    public static List<UUID> secondCaller(ApplySchedulePort port) {
        return port.tenantsWithDueSchedules(Instant.now());
    }
}
