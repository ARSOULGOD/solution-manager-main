package com.solace.sm.gateway.archfixture.sweep;

import com.solace.sm.gateway.http.AgentTargetSweep;
import java.util.UUID;

/**
 * Planted violation (plan 015 T4.6, {@code OR-140}, plan section 10 Q-C2): the enrolment sweep run from a request path, which is
 * exactly what {@code listTargets} used to do and may not do again: a reach-level GET must not trigger control-plane
 * revocations. {@code AgentTargetSweep#sweep} has one caller, the scheduled job. {@code TenantEnumerationBoundaryTest} must report
 * this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class SweepsOnAGet {

    private SweepsOnAGet() {
    }

    public static void listTargets(AgentTargetSweep sweep, UUID organization) {
        sweep.sweep(organization);
    }
}
