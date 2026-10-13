package com.solace.sm.dp.agent.archfixture;

import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 015 T4.6, {@code OR-140}): a second caller of {@code JdbcAgentReportRepository#tenantsWithStaleProbedPlacements}
 * (and of the per-tenant {@code staleProbedInstances} walk), the data plane's cross-tenant read for the staleness job. Its one named
 * caller is {@code AgentStalenessJob}. {@code TenantEnumerationBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class ThirdStalenessEnumerator {

    private ThirdStalenessEnumerator() {
    }

    public static List<UUID> thirdCaller(JdbcAgentReportRepository reports) {
        return reports.tenantsWithStaleProbedPlacements(Instant.now());
    }
}
