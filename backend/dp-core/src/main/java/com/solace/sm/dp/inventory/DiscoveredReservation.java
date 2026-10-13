package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.util.Objects;
import java.util.UUID;

/**
 * What the agent discovery intake hands {@link InventoryService#reserveDiscovered}: one instance and its one placement on the
 * agent's own target (plan 015 T4.1, {@code OR-27}). Every field was DERIVED by the data plane from its own enrolment record and
 * the container's facts ({@code I5}); the instance and placement ids are minted by the caller so it can record the tombstone
 * that names them in the same transaction.
 *
 * <p>This is a type of {@code dp-core}, not of {@code dp-api}: an operator's command can never name an agent target
 * ({@code TargetIsAgentManagedException}), and the only way to reserve on one is this separate entry, which no {@code dp-api}
 * interface offers.
 *
 * @param requestedBy  the principal who issued the agent's enrolment token ({@code instance_state.created_by})
 * @param agentId      the agent, written into {@code placement.created_by} (an agent is told apart by joining {@code agent_identity})
 */
public record DiscoveredReservation(UUID instanceId, UUID placementId, UUID environmentId, UUID catalogSolutionId,
        String solution, String declaredPackageVersion, String name, UUID targetId, PlacementSpec placement,
        UUID requestedBy, UUID agentId, MetricsDeclaration metrics) {

    public DiscoveredReservation {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(declaredPackageVersion, "declaredPackageVersion");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(metrics, "metrics");
    }
}
