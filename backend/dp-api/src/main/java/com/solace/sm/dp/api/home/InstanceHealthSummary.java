package com.solace.sm.dp.api.home;

import com.solace.sm.dp.api.health.HealthState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One active instance, as the home summary counts it.
 *
 * @param catalogSolutionId the catalog row the instance was declared from: the summary's grouping key
 * @param rollup            the instance's health in the notify vocabulary, by {@code project.ts}'s
 *                          {@code healthRollupOf} taken literally at placement scope with the four NOTFOUND sub-states
 *                          inserted (see {@code OR-61}); empty while nothing has polled any of its placements, which
 *                          the surface declares instead of inventing a state
 */
public record InstanceHealthSummary(UUID instanceId, UUID catalogSolutionId, Optional<HealthState> rollup) {

    public InstanceHealthSummary {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
        Objects.requireNonNull(rollup, "rollup");
    }
}
