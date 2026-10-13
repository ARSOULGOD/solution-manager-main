package com.solace.sm.gateway.archfixture.sweep;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import java.util.UUID;

/**
 * Planted violation (plan 015 T4.6, {@code OR-140}): a second route to the system actor's control-plane writes. {@code revoke}
 * and {@code retire} on {@code AgentEnrolmentSweep} are made only by the per-organization sweep that first asks the data plane
 * whether the target holds a connection. {@code TenantEnumerationBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class RevokesAsTheSystemActor {

    private RevokesAsTheSystemActor() {
    }

    public static void revoke(AgentEnrolmentSweep sweep, UUID organization, AgentEnrolmentSweep.Candidate candidate) {
        sweep.revoke(organization, candidate, UUID.randomUUID());
    }
}
