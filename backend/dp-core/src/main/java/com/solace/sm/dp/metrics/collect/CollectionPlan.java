package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What one tenant's collector should read right now (plan 011 P11-4.3), built from DP rows only and replaced every
 * tick. The plan is a value: nothing in it reaches back to a database or a host.
 *
 * <p>A {@link Unit} is one placement read at one interval: its sources (distinct URLs, each shared by the metrics
 * that read it), one restart-token read per cycle, one ingest batch. The unit is what the scheduler dispatches, what
 * holds a per-target slot, and what a missed deadline is counted against.
 */
public record CollectionPlan(TenantId tenant, List<Unit> units) {

    public CollectionPlan {
        Objects.requireNonNull(tenant, "tenant");
        units = List.copyOf(units);
    }

    /** One metric read from a source's document. */
    public record Metric(String name, String componentType, String pointer) {
        public Metric {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(componentType, "componentType");
        }
    }

    /** One document and the metrics read from it. {@code request} carries the HOST port (M-Q14) and no host. */
    public record Source(FetchRequest request, List<Metric> metrics) {
        public Source {
            Objects.requireNonNull(request, "request");
            metrics = List.copyOf(metrics);
        }
    }

    /**
     * @param mode    the INSTANCE's acquisition mode, the only thing that picks a fetcher (M-Q2: no fallback)
     * @param signals direct mode only: each component's declared restart signal, with its HOST port; empty when none
     *                is declared or its port is unmapped. SSH mode's signal is the container's own
     */
    public record Unit(PlacementRef placement, UUID targetId, AcquisitionMode mode, int intervalSeconds,
            List<Source> sources, Map<String, RestartRequest.Signal> signals) {
        public Unit {
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(mode, "mode");
            if (intervalSeconds < 1) {
                throw new IllegalArgumentException("a unit's interval must be at least one second");
            }
            sources = List.copyOf(sources);
            signals = Map.copyOf(signals);
        }

        public UUID placementId() {
            return placement.placement().value();
        }

        /** The scheduling identity: a placement read at one interval. */
        public Id id() {
            return new Id(placement.tenant(), placementId(), intervalSeconds);
        }

        /** The host a failure or a slot belongs to: tenant-scoped, because a target belongs to one tenant. */
        public TargetKey target() {
            return new TargetKey(placement.tenant(), targetId);
        }

        public record Id(TenantId tenant, UUID placementId, int intervalSeconds) {
        }
    }

    public record TargetKey(TenantId tenant, UUID targetId) {
    }
}
