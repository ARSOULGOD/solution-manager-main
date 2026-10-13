package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@code ActualStatePort} must do (plan 015 T2.1b, ported from the branch's {@code AgentReportIT}): trust is derived
 * from the lease at receive time, a replay returns the same verdict, a lower sequence is {@code STALE} and moves nothing, and a
 * placement the lease does not hold is {@code UNTRUSTED}/{@code REFUSED} with nothing written. {@code ReportItem.bindingId} is
 * agent-supplied: it is only ever compared with the lease, and never resolves an organization.
 */
public abstract class AbstractActualStatePortContractTest {

    private static final Instant OBSERVED = Instant.parse("2026-10-07T00:00:00Z");

    protected abstract AgentKitWorld world();

    private List<ReportResult> report(AgentSessionRef session, List<ReportItem> items) {
        return world().reports().report(session, items, world().origin());
    }

    private static ReportItem item(String itemId, long sequence, String binding, String incarnation) {
        return new ReportItem(itemId, sequence, binding, "sm-" + binding, incarnation, 3, OBSERVED, "a".repeat(64),
                "image-digest", "sha256:abc", "low", null, null, null);
    }

    /** As {@link #item}, with a {@code replica_ref} of the caller's choosing (the placement's container name is {@code sm-<id>}). */
    private static ReportItem itemWithReplica(String itemId, long sequence, String binding, String replicaRef) {
        return new ReportItem(itemId, sequence, binding, replicaRef, "inc-1", 3, OBSERVED, "a".repeat(64),
                "image-digest", "sha256:abc", "low", null, null, null);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void replayingTheSameItemReturnsTheSameVerdictAndWritesNoSecondWatermark() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        ReportItem first = item(id(), 10, agent.placementId().toString(), "inc-1");
        List<ReportResult> one = report(session, List.of(first));
        List<ReportResult> again = report(session, List.of(first));
        assertEquals(one, again, "a replay returns the original verdict, identities included");
        assertEquals("APPLIED", one.get(0).disposition());
        assertEquals("TRUSTED", one.get(0).trust());
        assertEquals(1, world().rows("agent_report_watermark", agent));
        assertEquals(OptionalLong.of(10), world().reportWatermark(agent, agent.placementId()));
    }

    @Test
    void aLowerSequenceIsStaleNotAppliedAndNeverMovesTheWatermarkBack() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        String binding = agent.placementId().toString();
        List<ReportResult> inOrder = report(session, List.of(item(id(), 20, binding, "inc-1"),
                item(id(), 15, binding, "inc-1")));
        assertEquals("APPLIED", inOrder.get(0).disposition());
        assertEquals("STALE", inOrder.get(1).disposition());
        assertEquals("TRUSTED", inOrder.get(1).trust(), "stale is a disposition, not a trust verdict");
        assertEquals(OptionalLong.of(20), world().reportWatermark(agent, agent.placementId()));
    }

    @Test
    void aPlacementTheLeaseDoesNotHoldIsUntrustedRefusedAndWritesNothing() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentKitWorld.Agent stranger = world().enrol();
        AgentSessionRef session = world().ready(agent);
        for (String binding : new String[] {UUID.randomUUID().toString(), stranger.placementId().toString(), "not-a-uuid"}) {
            ReportResult result = report(session, List.of(item(id(), 40, binding, "inc-1"))).get(0);
            assertEquals("UNTRUSTED", result.trust(), binding);
            assertEquals("REFUSED", result.disposition(), binding);
            assertEquals(GatewayCode.BINDING_NOT_IN_LEASE, result.refusedReason(), binding);
            assertNull(result.replicaId());
            assertNull(result.incarnationId());
        }
        assertEquals(0, world().rows("agent_report_watermark", agent));
        assertEquals(0, world().rows("agent_report_watermark", stranger), "another organization's placement was never touched");
    }

    @Test
    void theReplicaIsThePlacementAndTheIncarnationIsDerivedAndStable() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        String binding = agent.placementId().toString();
        ReportResult a = report(session, List.of(item(id(), 1, binding, "inc-1"))).get(0);
        ReportResult b = report(session, List.of(item(id(), 2, binding, "inc-1"))).get(0);
        ReportResult c = report(session, List.of(item(id(), 3, binding, "inc-2"))).get(0);
        assertEquals(binding, a.replicaId());
        assertEquals(a.incarnationId(), b.incarnationId(), "the same incarnation keeps its id");
        assertNotEquals(a.incarnationId(), c.incarnationId());
    }

    @Test
    void aStaleSessionIsRefusedBeforeAnyWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().ready(agent);
        world().open(agent);
        refused(GatewayCode.STALE_EPOCH, () -> report(stale,
                List.of(item(id(), 5, agent.placementId().toString(), "inc-1"))));
        assertEquals(0, world().rows("agent_report_watermark", agent));
    }

    @Test
    void aReportNeedsAnActiveLease() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> report(session,
                List.of(item(id(), 5, agent.placementId().toString(), "inc-1"))));
        assertEquals(0, world().rows("agent_report_watermark", agent));
    }

    /** Plan 015 T3.3 (design 4.6): after the lease EXPIRES the report is still taken, on the session that authenticates it, and labelled. */
    @Test
    void aReportAfterTheLeaseExpiredIsAcceptedAndLabelledUntrusted() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        world().advance(java.time.Duration.ofMinutes(30));
        ReportResult result = report(session, List.of(item(id(), 5, agent.placementId().toString(), "inc-1"))).get(0);
        assertEquals("UNTRUSTED", result.trust(), "trust is the lease's verdict at receive time");
        assertEquals("APPLIED", result.disposition(), "the report is taken, not refused");
        assertNotNull(result.replicaId());
        assertEquals(OptionalLong.of(5), world().reportWatermark(agent, agent.placementId()));
    }

    /** AGW-D25 item 2. The refusal keeps the trust the lease gives; nothing is written. */
    @Test
    void aReplicaRefThatIsNotThePlacementsContainerNameIsRefusedAndWritesNothing() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        ReportResult result = report(session, List.of(itemWithReplica(id(), 5, agent.placementId().toString(), "sm-someone-else")))
                .get(0);
        assertEquals("REFUSED", result.disposition());
        assertEquals(GatewayCode.REPLICA_REF_INVALID, result.refusedReason());
        assertEquals("TRUSTED", result.trust(), "REPLICA_REF_INVALID does not change trust");
        assertNull(result.replicaId());
        assertEquals(0, world().rows("agent_report_watermark", agent));
    }

    @Test
    void aMalformedItemIsRefusedWithACodeNotAnUnknown() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        ReportResult negative = report(session, List.of(item(id(), -1, agent.placementId().toString(), "inc-1"))).get(0);
        assertEquals(GatewayCode.LABEL_INVALID, negative.refusedReason());
        ReportResult noId = report(session, List.of(item("", 5, agent.placementId().toString(), "inc-1"))).get(0);
        assertEquals(GatewayCode.LABEL_INVALID, noId.refusedReason());
        ReportResult badIncarnation = report(session, List.of(item(id(), 6, agent.placementId().toString(), "has space"))).get(0);
        assertEquals(GatewayCode.REPLICA_REF_INVALID, badIncarnation.refusedReason());
        assertEquals(0, world().rows("agent_report_watermark", agent));
    }

    @Test
    void theOrganizationOfAWatermarkIsTheIdentitys() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        report(session, List.of(item(id(), 5, agent.placementId().toString(), "inc-1")));
        assertTrue(world().rows("agent_report_watermark", agent) == 1);
        assertEquals(agent.tenantId(), world().storedTenant("agent_report_watermark", agent));
    }

    @Test
    void aNegativeSequenceRefusesThatItemOnlyAndNotTheBatch() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        String binding = agent.placementId().toString();
        List<ReportResult> results = report(session, List.of(item(id(), -1, binding, "inc-1"), item(id(), 7, binding, "inc-1")));
        assertEquals("REFUSED", results.get(0).disposition());
        assertEquals("UNTRUSTED", results.get(0).trust());
        assertEquals("APPLIED", results.get(1).disposition(), "the rest of the batch is unaffected");
        assertEquals(OptionalLong.of(7), world().reportWatermark(agent, agent.placementId()));
    }
}
