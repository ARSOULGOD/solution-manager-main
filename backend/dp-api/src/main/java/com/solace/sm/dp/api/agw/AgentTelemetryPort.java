package com.solace.sm.dp.api.agw;

/**
 * Renamed from the branch's {@code TelemetryIngestPort}, which collides by simple name with the built
 * {@code dp.api.metrics.ingest.TelemetryIngestPort}. {@code origin} is minted per Telemetry stream by the gateway.
 */
public interface AgentTelemetryPort {
    AgentTelemetryResult ingest(AgentSessionRef ref, AgentTelemetryBatch batch, AgentRequestOrigin origin);
}
