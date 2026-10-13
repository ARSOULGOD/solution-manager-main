package com.solace.sm.dp.home;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.home.InstanceHealthSummary;
import com.solace.sm.dp.api.home.InventorySummaryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.health.PlacementHealthRecord;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link InventorySummaryService} over the persisted rows (plan 010 T10.5). Reads only: it touches no host and calls no
 * adapter, so it answers when a host is down. One tenant per call - the organization the caller names, never an
 * enumeration of tenants ({@code I5}; this is not a caller of the cross-tenant lists).
 *
 * <p>It returns every ACTIVE instance and rolls its placements' persisted health up ({@link HomeRollup}); who may see
 * which instance is the gateway's to decide.
 */
public final class InventorySummaryServiceImpl implements InventorySummaryService {

    private final InstanceRows instances;
    private final PlacementHealthRows health;

    public InventorySummaryServiceImpl(InstanceRows instances, PlacementHealthRows health) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.health = Objects.requireNonNull(health, "health");
    }

    @Override
    public List<InstanceHealthSummary> summarize(UUID organizationId) {
        return instances.list(organizationId, InstanceLifecycle.ACTIVE, Optional.empty()).stream()
                .map(instance -> summarize(organizationId, instance))
                .toList();
    }

    private InstanceHealthSummary summarize(UUID organizationId, InstanceRecord instance) {
        List<HealthState> placements = health.liveOf(organizationId, instance.instanceId()).stream()
                .map(PlacementHealthRecord::state).toList();
        return new InstanceHealthSummary(instance.instanceId(), instance.catalogSolutionId(),
                HomeRollup.of(placements));
    }
}
