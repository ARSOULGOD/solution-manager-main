package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.inventory.InstanceRecord;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The reads the push plan needs, in one port (plan 011 P11-5.1), so the plan is a pure function of rows and a test can
 * arrange every row. Read-only and tenant-first (I5); the one adapter ({@link PushRowsAdapter}) delegates to the ports the
 * collector, the inventory and the config of record already own, and holds no SQL.
 */
public interface PushRows {

    /** What of a generation the plan reads: where the component instances are, and the configured values. Never a secret value (references only). */
    record ConfigContent(String componentSelectionJson, String draftValuesJson) {

        /** Redacted: the draft holds configuration values. */
        @Override
        public String toString() {
            return "ConfigContent[redacted]";
        }
    }

    /** The organization's {@code active} instances. */
    List<InstanceRecord> activeInstances(UUID tenantId);

    List<MetricDefinitionSpec> definitions(UUID tenantId, UUID catalogSolutionId, String packageVersion);

    /** The metric names active for one generation (M-Q15), or empty when none was recorded. */
    Optional<List<String>> activeMetrics(UUID tenantId, UUID instanceId, long generation);

    /** The newest generation whose every live placement's apply observed the restart: the configuration the MI is running. */
    OptionalLong inForceGeneration(UUID tenantId, UUID instanceId);

    Optional<ConfigContent> config(UUID tenantId, UUID instanceId, long generation);

    /** The instance's live (not retired) placements. */
    List<UUID> livePlacements(UUID tenantId, UUID instanceId);

    Optional<BrokerConnectionRecord> liveConnection(UUID tenantId, UUID environmentId);
}
