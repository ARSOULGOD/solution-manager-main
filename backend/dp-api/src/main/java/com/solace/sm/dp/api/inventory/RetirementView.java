package com.solace.sm.dp.api.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An undeclare's data-plane record ({@code instance_retirement}): who asked, when, under which correlation id, each
 * placement's last persisted health at the time, and how it ended. Carries no host, no container name and no reference
 * (the response of undeclare names placements and a health state only). {@code stateVersion} is the instance's own stamp
 * after the write this view answers ({@code OR-35}).
 *
 * @param placements {@code placementId} and the health state the poller had last persisted ({@code empty}: never probed)
 */
public record RetirementView(UUID retirementId, UUID instanceId, UUID requestedBy, Instant requestedAt,
        UUID correlationId, WorkloadDisposition workload, List<PlacementHealth> placements,
        Optional<Instant> completedAt, Optional<RetirementOutcome> outcome, long stateVersion) {

    public RetirementView {
        Objects.requireNonNull(retirementId, "retirementId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(workload, "workload");
        placements = List.copyOf(placements);
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (completedAt.isPresent() != outcome.isPresent()) {
            throw new IllegalArgumentException("completedAt and outcome are set together or not at all");
        }
    }

    /** The same record carrying {@code stateVersion}: the instance's stamp after the write the caller is answering. */
    public RetirementView withStateVersion(long version) {
        return new RetirementView(retirementId, instanceId, requestedBy, requestedAt, correlationId, workload, placements,
                completedAt, outcome, version);
    }

    /** One placement and its last persisted health state. */
    public record PlacementHealth(UUID placementId, Optional<String> lastHealth) {
        public PlacementHealth {
            Objects.requireNonNull(placementId, "placementId");
            Objects.requireNonNull(lastHealth, "lastHealth");
        }
    }
}
