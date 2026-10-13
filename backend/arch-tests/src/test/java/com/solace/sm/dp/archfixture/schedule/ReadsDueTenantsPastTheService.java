package com.solace.sm.dp.archfixture.schedule;

import com.solace.sm.dp.schedule.ScheduleRows;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 012 T7, {@code OR-122} (2)): a class reaching the cross-organization SELECT directly
 * ({@code ScheduleRows#dueTenants}) instead of through the port method the gateway's firer calls. The persistence row's
 * only caller is {@code ApplyScheduleServiceImpl#tenantsWithDueSchedules}; {@code ApplyScheduleBoundaryTest} must report this
 * class, because a second route to the same SELECT would put the one-caller rule on the wrong name.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class ReadsDueTenantsPastTheService {

    private ReadsDueTenantsPastTheService() {
    }

    public static List<UUID> bypass(ScheduleRows rows) {
        return rows.dueTenants(Instant.now());
    }
}
