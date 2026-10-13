package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@link PushRows} over the ports the inventory, the collector and the config of record already own (plan 011 P11-5.1). It
 * holds no SQL and adds no query: every read is an existing, tenant-first port (I5). The one new table's port is
 * {@link BrokerConnectionRows}.
 */
public final class PushRowsAdapter implements PushRows {

    private final InstanceRows instances;
    private final CollectionRows collection;
    private final RestartObservedGenerations generations;
    private final ConfigGenerationRows configs;
    private final PlacementRows placements;
    private final BrokerConnectionRows connections;

    public PushRowsAdapter(InstanceRows instances, CollectionRows collection, RestartObservedGenerations generations,
            ConfigGenerationRows configs, PlacementRows placements, BrokerConnectionRows connections) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public List<InstanceRecord> activeInstances(UUID tenantId) {
        return instances.list(tenantId, InstanceLifecycle.ACTIVE, Optional.empty());
    }

    @Override
    public List<MetricDefinitionSpec> definitions(UUID tenantId, UUID catalogSolutionId, String packageVersion) {
        return collection.definitions(tenantId, catalogSolutionId, packageVersion);
    }

    @Override
    public Optional<List<String>> activeMetrics(UUID tenantId, UUID instanceId, long generation) {
        return collection.activeMetrics(tenantId, instanceId, generation);
    }

    @Override
    public OptionalLong inForceGeneration(UUID tenantId, UUID instanceId) {
        return generations.latestRestartObservedGeneration(new TenantId(tenantId), new InstanceId(instanceId));
    }

    @Override
    public Optional<ConfigContent> config(UUID tenantId, UUID instanceId, long generation) {
        return configs.find(new TenantId(tenantId), new InstanceId(instanceId), generation)
                .map(g -> new ConfigContent(g.componentSelectionJson(), g.draftValuesJson()));
    }

    @Override
    public List<UUID> livePlacements(UUID tenantId, UUID instanceId) {
        return placements.live(tenantId, instanceId).stream().map(PlacementRecord::placementId).toList();
    }

    @Override
    public Optional<BrokerConnectionRecord> liveConnection(UUID tenantId, UUID environmentId) {
        return connections.live(tenantId, environmentId);
    }
}
