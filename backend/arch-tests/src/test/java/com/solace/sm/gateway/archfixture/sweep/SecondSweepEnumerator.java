package com.solace.sm.gateway.archfixture.sweep;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 015 T4.6, {@code OR-140}): a SECOND caller of {@code AgentEnrolmentSweep#organizations}, the control
 * plane's cross-organization read for the enrolment sweep job. Its one named caller is the gateway's
 * {@code AgentEnrolmentSweepJob}; anything else that wants an organization list is a new enumeration and needs an owner ruling.
 * {@code TenantEnumerationBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class SecondSweepEnumerator {

    private SecondSweepEnumerator() {
    }

    public static List<UUID> secondCaller(AgentEnrolmentSweep sweep) {
        return sweep.organizations(Duration.ZERO);
    }
}
