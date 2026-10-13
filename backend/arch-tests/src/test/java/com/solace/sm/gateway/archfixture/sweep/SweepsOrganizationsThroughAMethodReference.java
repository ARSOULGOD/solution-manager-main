package com.solace.sm.gateway.archfixture.sweep;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Planted violation (plan 015 T4.6, review MINOR 3): a second caller of {@code AgentEnrolmentSweep#organizations} through a METHOD
 * REFERENCE, which {@code getMethodCallsFromSelf} does not see. {@code TenantEnumerationBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class SweepsOrganizationsThroughAMethodReference {

    private SweepsOrganizationsThroughAMethodReference() {
    }

    public static Function<Duration, List<UUID>> reference(AgentEnrolmentSweep sweep) {
        return sweep::organizations;
    }
}
