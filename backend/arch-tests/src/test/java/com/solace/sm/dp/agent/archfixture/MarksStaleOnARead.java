package com.solace.sm.dp.agent.archfixture;

import com.solace.sm.dp.health.AgentReportStaleness;
import java.util.UUID;

/**
 * Planted violation (plan 015 T4.6, {@code OR-140}, plan section 10 Q-C3): the staleness verdict written from a read path, which
 * is exactly what {@code HealthServiceImpl#health} used to do and may not do again. {@code AgentReportStaleness#markStale} has one
 * caller, the scheduled {@code AgentStalenessJob}. {@code TenantEnumerationBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class MarksStaleOnARead {

    private MarksStaleOnARead() {
    }

    public static void health(AgentReportStaleness staleness, UUID organization, UUID instance) {
        staleness.markStale(organization, instance);
    }
}
