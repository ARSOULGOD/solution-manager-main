package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.inventory.InstanceRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** An in-memory {@link PushRows}: every row a test arranges is the only row there is. Counts every read, for the "reads nothing it must not" tests. */
final class FakePushRows implements PushRows {

    final UUID tenant = UUID.randomUUID();
    final UUID solution = UUID.randomUUID();
    final List<InstanceRecord> instances = new ArrayList<>();
    final Map<UUID, List<UUID>> placements = new HashMap<>();
    final Map<UUID, Long> inForce = new HashMap<>();
    final Map<UUID, List<String>> active = new HashMap<>();
    final Map<UUID, ConfigContent> config = new HashMap<>();
    final Map<UUID, BrokerConnectionRecord> connections = new HashMap<>();
    List<MetricDefinitionSpec> definitions = List.of();
    int configReads;

    /** An ACTIVE instance of {@code environment} whose generation {@code 3} is in force and whose active list names every definition. */
    UUID instance(UUID environment, String adapterIdOrNull, UUID... livePlacements) {
        return instanceRaw(environment, adapterIdOrNull == null ? null : "\"" + adapterIdOrNull + "\"", livePlacements);
    }

    /** As {@link #instance}, with the configured value given as a raw JSON literal (a number, {@code null}, an object ...). */
    UUID instanceRaw(UUID environment, String rawJsonOrNull, UUID... livePlacements) {
        UUID id = UUID.randomUUID();
        instances.add(new InstanceRecord(tenant, id, environment, solution, "file-mesh", "1", "i-" + id,
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH, UUID.randomUUID(), Instant.EPOCH, 1));
        placements.put(id, List.of(livePlacements));
        inForce.put(id, 3L);
        active.put(id, definitions.stream().map(MetricDefinitionSpec::metricName).toList());
        if (rawJsonOrNull != null) {
            config.put(id, new ConfigContent("{\"instances\":[{\"instanceKey\":\"src\",\"type\":\"" + PushFixtures.COMPONENT
                    + "\"},{\"instanceKey\":\"snk\",\"type\":\"COMP_SINK_SOLACE@FILEEVENTS\"}]}",
                    "{\"src\":{\"" + PushFixtures.ADAPTER_ID_FIELD + "\":" + rawJsonOrNull + "},"
                            + "\"snk\":{\"" + PushFixtures.ADAPTER_ID_FIELD + "\":\"SINK-DECOY\"}}"));
        }
        return id;
    }

    UUID connection(UUID environment) {
        BrokerConnectionRecord c = new BrokerConnectionRecord(tenant, environment, UUID.randomUUID(), "tcps",
                "broker.example.test", 55443, "vpn", "sm-push", UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH, null);
        connections.put(environment, c);
        return c.brokerConnectionId();
    }

    @Override
    public List<InstanceRecord> activeInstances(UUID tenantId) {
        return tenantId.equals(tenant) ? List.copyOf(instances) : List.of();
    }

    @Override
    public List<MetricDefinitionSpec> definitions(UUID tenantId, UUID solutionId, String version) {
        return definitions;
    }

    @Override
    public Optional<List<String>> activeMetrics(UUID tenantId, UUID instanceId, long generation) {
        return Optional.ofNullable(active.get(instanceId));
    }

    @Override
    public OptionalLong inForceGeneration(UUID tenantId, UUID instanceId) {
        Long g = inForce.get(instanceId);
        return g == null ? OptionalLong.empty() : OptionalLong.of(g);
    }

    @Override
    public Optional<ConfigContent> config(UUID tenantId, UUID instanceId, long generation) {
        configReads++;
        return Optional.ofNullable(config.get(instanceId));
    }

    @Override
    public List<UUID> livePlacements(UUID tenantId, UUID instanceId) {
        return placements.getOrDefault(instanceId, List.of());
    }

    @Override
    public Optional<BrokerConnectionRecord> liveConnection(UUID tenantId, UUID environmentId) {
        return Optional.ofNullable(connections.get(environmentId));
    }
}
