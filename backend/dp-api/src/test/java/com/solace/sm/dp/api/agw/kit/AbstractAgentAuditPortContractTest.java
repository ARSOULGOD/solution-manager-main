package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentAuditPort} must do (plan 015 T2.1b, {@code AGW-D29}): one append-only row per request id, under the
 * organization of the session's identity, recordable for a REVOKED identity (the revocation is the event that most needs a
 * record), and never carrying the free text the caller passes as the subject.
 */
public abstract class AbstractAgentAuditPortContractTest {

    protected abstract AgentKitWorld world();

    private String request() {
        return "audit-" + UUID.randomUUID();
    }

    @Test
    void recordingTheSameRequestIdTwiceWritesOneRow() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        String request = request();
        world().audits().record(request, session, world().origin(), "gateway.refusal", GatewayCode.STALE_EPOCH, "s", world().now());
        world().audits().record(request, session, world().origin(), "gateway.refusal", GatewayCode.STALE_EPOCH, "s", world().now());
        assertEquals(1, world().rows("agent_event", agent));
        assertEquals(agent.tenantId(), world().storedTenant("agent_event", agent));
    }

    @Test
    void aStaleSessionIsRefusedBeforeAnyWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().open(agent);
        world().open(agent);
        refused(GatewayCode.STALE_EPOCH, () -> world().audits().record(request(), stale, world().origin(), "gateway.refusal",
                GatewayCode.STALE_EPOCH, "s", world().now()));
        assertEquals(0, world().rows("agent_event", agent));
    }

    @Test
    void aRevokedIdentityCanStillBeRecorded() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        world().controlPlane(agent, false, 1, 1, List.of());
        assertEquals(IdentityReconciliation.Status.REVOKED, world().identities().reconcile(List.of(session)).get(0).status());
        world().audits().record(request(), session, world().origin(), "gateway.revoked", GatewayCode.IDENTITY_REVOKED, "s", world().now());
        assertEquals(1, world().rows("agent_event", agent));
    }

    @Test
    void theSubjectIsNeverStored() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().audits().record(request(), session, world().origin(), "gateway.refusal", GatewayCode.STALE_EPOCH,
                "agent-supplied-secret-subject", world().now());
        assertFalse(world().anyAgentRowContains(agent, "agent-supplied-secret-subject"));
    }

    @Test
    void anEventTypeOutsideTheClosedVocabularyIsRefusedAndNothingIsStored() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        assertThrows(RuntimeException.class, () -> world().audits().record(request(), session, world().origin(), "made.up.event",
                GatewayCode.STALE_EPOCH, "s", world().now()));
        assertEquals(0, world().rows("agent_event", agent));
    }

    @Test
    void theRowCarriesTheGatewayMintedOriginNotADerivedOne() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        var origin = new com.solace.sm.dp.api.agw.AgentRequestOrigin(UUID.randomUUID(), "10.9.8.7");
        world().audits().record(request(), session, origin, "gateway.refusal", GatewayCode.STALE_EPOCH, "s", world().now());
        String row = world().rowText("agent_event", agent);
        assertTrue(row.contains(origin.correlationId().toString()) && row.contains("10.9.8.7"), row);
        assertFalse(row.contains("unknown"));
    }

    @Test
    void aSecondRecordUnderTheSameRequestIdForADifferentEventDoesNotOverwriteTheFirst() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        String request = request();
        world().audits().record(request, session, world().origin(), "gateway.refusal", GatewayCode.STALE_EPOCH, "s", world().now());
        world().audits().record(request, session, world().origin(), "gateway.drain", GatewayCode.DRAINING, "s", world().now());
        String row = world().rowText("agent_event", agent);
        assertEquals(1, world().rows("agent_event", agent));
        assertTrue(row.contains("gateway.refusal") && !row.contains("gateway.drain"),
                "first writer wins: the reuse is documented behaviour, a no-op and not an overwrite: " + row);
    }
}
