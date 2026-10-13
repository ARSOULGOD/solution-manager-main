package com.solace.sm.dp.api.agw.archfixture;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.AgentTelemetryResult;

/**
 * Planted violation: a production class that IS an {@code AgentTelemetryPort} before T3.4, which would let a persistence helper
 * acknowledge batches it never forwarded (plan 015 T2.1b fix round, finding 4).
 */
public final class PlantedPortBean implements AgentTelemetryPort {
    @Override
    public AgentTelemetryResult ingest(AgentSessionRef ref, AgentTelemetryBatch batch, AgentRequestOrigin origin) {
        return AgentTelemetryResult.acked(0, 0);
    }
}
