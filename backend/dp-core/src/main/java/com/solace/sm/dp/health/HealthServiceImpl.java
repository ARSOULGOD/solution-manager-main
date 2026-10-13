package com.solace.sm.dp.health;

import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.api.health.HealthView;
import com.solace.sm.dp.api.health.PlacementHealthView;
import com.solace.sm.dp.inventory.PlacementRows;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link HealthService} over the persisted rows (plan 010 T7.2). Reads only: it never touches a host, so a request
 * never waits on SSH, and it writes nothing (the agent staleness verdict is a scheduled job, plan 015 T4.6). Each placement carries its own state version and check time; a live placement nothing has polled
 * yet is counted, not invented.
 */
public final class HealthServiceImpl implements HealthService {

    private final PlacementRows placements;
    private final PlacementHealthRows health;

    public HealthServiceImpl(PlacementRows placements, PlacementHealthRows health) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.health = Objects.requireNonNull(health, "health");
    }

    @Override
    public HealthView health(UUID organizationId, UUID instanceId) {
        // OR-140 (plan 015 T4.6): a read marks nothing. AGW-D28 item 1's staleness verdict ("an agent that stopped reporting is
        // unreachable") is the scheduled AgentStalenessJob, which writes the verdict and fires instance.health whether or not
        // anyone is reading.
        int live = placements.live(organizationId, instanceId).size();
        List<PlacementHealthView> polled = health.liveOf(organizationId, instanceId).stream()
                .map(r -> new PlacementHealthView(r.placementId(), r.state(), r.checkedAt(), r.stateVersion()))
                .toList();
        return new HealthView(polled, Math.max(0, live - polled.size()));
    }
}
