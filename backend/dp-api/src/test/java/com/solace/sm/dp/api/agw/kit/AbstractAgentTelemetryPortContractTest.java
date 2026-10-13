package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.AgentTelemetryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentTelemetryPort} must do (plan 015 T2.1b, rewritten in the T3.4 fix round to certify the REAL bean rather
 * than a test adapter): a stream is a placement of the agent's active lease; a batch whose sequence is at or below the stream's
 * watermark is acknowledged without moving it; a stale session or a missing lease is refused before any write; the labels are the
 * closed three, each an identifier, and bind the batch to the stream's own placement; and the organization is the identity's whatever
 * the batch claims. Every refusal is a coded {@code GatewayPortException}, never an unclassified failure.
 */
public abstract class AbstractAgentTelemetryPortContractTest {

    protected abstract AgentKitWorld world();

    private AgentTelemetryResult ingest(AgentSessionRef ref, AgentTelemetryBatch batch) {
        return world().telemetry().ingest(ref, batch, world().origin());
    }

    private static Map<String, String> labels(UUID placement) {
        return Map.of("binding_id", placement.toString(), "replica_id", placement.toString(),
                "incarnation_id", UUID.randomUUID().toString());
    }

    private static AgentTelemetryBatch batch(String stream, long seq, Map<String, String> labels) {
        Instant at = Instant.now().minusSeconds(30);
        return new AgentTelemetryBatch(stream, seq, List.of(new AgentTelemetryBatch.Sample("cache.size", "gauge", at, 1.0, labels)),
                List.of(), at);
    }

    private static AgentTelemetryBatch batch(UUID placement, long seq) {
        return batch(placement.toString(), seq, labels(placement));
    }

    @Test
    void aRedeliveredOrOlderBatchIsAcknowledgedAndNeverMovesTheWatermarkBack() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        UUID stream = agent.placementId();
        AgentTelemetryResult five = ingest(session, batch(stream, 5));
        assertEquals(AgentTelemetryResult.Disposition.ACK, five.disposition());
        assertEquals(5, five.highWatermark());
        assertEquals(5, ingest(session, batch(stream, 5)).highWatermark(), "a redelivery");
        assertEquals(5, ingest(session, batch(stream, 3)).highWatermark(), "an older batch");
        assertEquals(8, ingest(session, batch(stream, 8)).highWatermark());
        assertEquals(OptionalLong.of(8), world().telemetryWatermark(agent, stream.toString()));
        assertEquals(1, world().rows("agent_telemetry_watermark", agent));
    }

    @Test
    void streamsAreIndependent() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        AgentSessionRef session = world().ready(agent);
        ingest(session, batch(agent.placementId(), 9));
        ingest(session, batch(second, 2));
        assertEquals(OptionalLong.of(9), world().telemetryWatermark(agent, agent.placementId().toString()));
        assertEquals(OptionalLong.of(2), world().telemetryWatermark(agent, second.toString()));
    }

    @Test
    void aStaleSessionIsRefusedBeforeAnyWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().ready(agent);
        world().open(agent);
        refused(GatewayCode.STALE_EPOCH, () -> ingest(stale, batch(agent.placementId(), 1)));
        assertEquals(0, world().rows("agent_telemetry_watermark", agent));
    }

    @Test
    void aBatchNeedsAnActiveLease() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> ingest(session, batch(agent.placementId(), 1)));
        assertEquals(0, world().rows("agent_telemetry_watermark", agent));
    }

    @Test
    void theOrganizationIsTheIdentitysAndAForeignStreamOrForeignLabelsAreRefused() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentKitWorld.Agent victim = world().enrol();
        AgentSessionRef session = world().ready(agent);
        Map<String, String> hostile = new HashMap<>(labels(agent.placementId()));
        hostile.put("tenant", victim.tenantId().toString());
        hostile.put("organization", victim.tenantId().toString());
        refused(GatewayCode.UNDECLARED_LABEL, () -> ingest(session, batch(agent.placementId().toString(), 1, hostile)));
        refused(GatewayCode.BINDING_NOT_IN_LEASE, () -> ingest(session, batch(victim.placementId(), 1)));
        ingest(session, batch(agent.placementId(), 1));
        assertEquals(agent.tenantId(), world().storedTenant("agent_telemetry_watermark", agent));
        assertEquals(0, world().rows("agent_telemetry_watermark", victim), "nothing landed under the organization the batch named");
    }

    @Test
    void aStreamThatIsNotAPlacementOfTheLeaseIsRefusedWithACodeNeverAnUnclassifiedFailure() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        for (String stream : List.of("main", "bad stream!", "", "00000000-0000-0000-0000-000000000000")) {
            refused(GatewayCode.BINDING_NOT_IN_LEASE, () -> ingest(session, batch(stream, 1, labels(agent.placementId()))));
        }
        assertEquals(0, world().rows("agent_telemetry_watermark", agent));
    }

    @Test
    void theLabelsAreExactlyTheThreeIdsAndBindTheBatchToItsOwnPlacement() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID other = world().addPlacement(agent);
        AgentSessionRef session = world().ready(agent);
        String stream = agent.placementId().toString();
        refused(GatewayCode.LABEL_INVALID, () -> ingest(session, batch(stream, 1, Map.of())));
        refused(GatewayCode.LABEL_INVALID, () -> ingest(session, batch(stream, 1,
                Map.of("binding_id", stream, "replica_id", stream))));
        refused(GatewayCode.LABEL_INVALID, () -> ingest(session, batch(stream, 1,
                Map.of("binding_id", stream, "replica_id", "not-an-id", "incarnation_id", UUID.randomUUID().toString()))));
        refused(GatewayCode.BINDING_NOT_IN_LEASE, () -> ingest(session, batch(stream, 1, labels(other))));
        Map<String, String> otherReplica = new HashMap<>(labels(agent.placementId()));
        otherReplica.put("replica_id", other.toString());
        refused(GatewayCode.UNKNOWN_REPLICA, () -> ingest(session, batch(stream, 1, otherReplica)));
        assertEquals(0, world().rows("agent_telemetry_watermark", agent));
    }
}
