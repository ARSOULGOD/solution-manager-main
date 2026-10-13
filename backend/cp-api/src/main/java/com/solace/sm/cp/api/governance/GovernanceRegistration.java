package com.solace.sm.cp.api.governance;

import java.util.Objects;
import java.util.UUID;

/**
 * What {@link InstanceGovernance#register} stored.
 *
 * @param solutionId          the environment's Solution scope node for the instance's catalog entry
 * @param solutionNodeCreated {@code true} when this call created that node (the first declare of the entry in the
 *                            environment, {@code OR-41}), {@code false} when it reused one
 * @param instanceId          the instance node, equal to the id the data plane minted
 */
public record GovernanceRegistration(UUID solutionId, boolean solutionNodeCreated, UUID instanceId) {

    public GovernanceRegistration {
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
