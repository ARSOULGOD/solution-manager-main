package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentFeature;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.api.agw.CloseReason;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentSessionPort} must do (plan 015 T2.1b): one open owner per agent, fenced by a rising epoch; a stale
 * session refused before any write; the organization derived from the identity and never from the caller. A claim carries no
 * request id, so "idempotent" here means FENCED: a repeated claim does not replay, it supersedes, and the earlier stream is the
 * one refused.
 */
public abstract class AbstractAgentSessionPortContractTest {

    protected abstract AgentKitWorld world();

    @Test
    void eachClaimTakesTheSessionOverAtAStrictlyHigherEpoch() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef first = world().open(agent);
        AgentSessionRef second = world().open(agent);
        assertEquals(agent.agentId(), first.agentId());
        assertTrue(second.fencingEpoch() > first.fencingEpoch(), "the epoch only rises");
        assertEquals(1, world().rows("agent_session", agent), "one session row per agent, however many claims");
    }

    @Test
    void aSupersededSessionIsRefusedBeforeAnyWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().open(agent);
        world().open(agent);
        var seenBefore = world().sessionLastSeen(agent);
        world().advance(Duration.ofSeconds(30));
        refused(GatewayCode.STALE_EPOCH, () -> world().sessions().heartbeat(stale, world().now()));
        assertEquals(seenBefore, world().sessionLastSeen(agent), "the refused heartbeat wrote nothing");
    }

    @Test
    void closingASupersededSessionDoesNotCloseItsSuccessor() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef old = world().open(agent);
        AgentSessionRef current = world().open(agent);
        world().sessions().close(old, CloseReason.SUPERSEDED);
        assertFalse(world().sessionClosed(agent), "the successor is still open");
        world().sessions().heartbeat(current, world().now());      // and still the owner
    }

    @Test
    void closeIsIdempotentAndAClosedSessionIsRefused() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().sessions().close(session, CloseReason.STREAM_CLOSED);
        world().sessions().close(session, CloseReason.STREAM_CLOSED);
        assertTrue(world().sessionClosed(agent));
        refused(GatewayCode.STALE_EPOCH, () -> world().sessions().heartbeat(session, world().now()));
        AgentSessionRef reopened = world().open(agent);
        assertTrue(reopened.fencingEpoch() > session.fencingEpoch(), "a reconnect is a new, higher epoch");
        assertFalse(world().sessionClosed(agent));
    }

    @Test
    void aRevokedIdentityIsRefusedAtClaimAndNothingIsWritten() {
        AgentKitWorld.Agent agent = world().enrol();
        world().controlPlane(agent, false, 2, 1, List.of());
        refused(GatewayCode.IDENTITY_REVOKED, () -> world().open(agent));
        assertEquals(0, world().rows("agent_session", agent));
        assertTrue(world().identityRevoked(agent), "the revocation was recorded on the data plane's own identity");
    }

    @Test
    void anUnenrolledCertificateIsRefusedAndNothingIsWritten() {
        AgentKitWorld.Agent agent = world().enrol();
        String unknown = "f".repeat(64);
        refused(GatewayCode.UNAUTHENTICATED_IDENTITY, () -> world().sessions().claim(unknown, world().opening(), world().origin()));
        assertEquals(0, world().rows("agent_session", agent));
    }

    @Test
    void theOrganizationIsTheIdentitysNeverTheCallers() {
        AgentKitWorld.Agent a = world().enrol();
        AgentKitWorld.Agent b = world().enrol();
        assertNotEquals(a.tenantId(), b.tenantId(), "precondition: two organizations");
        world().open(a);
        world().open(b);
        // claim takes a thumbprint and an origin and nothing else: the stored organization can only have come from the identity
        assertEquals(a.tenantId(), world().storedTenant("agent_session", a));
        assertEquals(b.tenantId(), world().storedTenant("agent_session", b));
    }

    @Test
    void liveForNeedsAnOpenSessionAndAnUnexpiredLeaseHoldingThePlacement() {
        AgentKitWorld.Agent agent = world().enrol();
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()), "no session");
        AgentSessionRef session = world().open(agent);
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()), "a session without a lease");
        var grant = world().lease(agent, session);
        assertTrue(world().sessions().liveFor(agent.agentId(), agent.placement()));
        assertFalse(world().sessions().liveFor(agent.agentId(), world().enrol().placement()),
                "a placement the lease does not hold, and of another organization");
        world().sessions().close(session, CloseReason.STREAM_CLOSED);
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()), "a closed session");
        world().open(agent);
        assertTrue(world().sessions().liveFor(agent.agentId(), agent.placement()), "a new session over the same live lease");
        world().advance(Duration.between(world().now(), grant.expiresAt()).plusSeconds(1));
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()), "an expired lease");
    }

    @Test
    void aNonPositiveProtocolVersionIsRefusedAndNothingIsWritten() {
        AgentKitWorld.Agent agent = world().enrol();
        refused(GatewayCode.PROTOCOL_VERSION_UNSUPPORTED,
                () -> world().open(agent, new SessionOpening(0, "0.1.0", Set.of(), world().now())));
        assertEquals(0, world().rows("agent_session", agent));
    }

    @Test
    void theOpeningsVersionsFeaturesAndClockOffsetArePersistedBoundedAndClamped() {
        AgentKitWorld.Agent agent = world().enrol();
        world().open(agent, new SessionOpening(2, "", Set.of(AgentFeature.DISCOVERY, AgentFeature.REMATERIALIZE),
                world().now().minusSeconds(5)));
        String row = world().rowText("agent_session", agent);
        assertTrue(row.contains("\"agent_version\": \"unknown\""), "an empty agent version is recorded as unknown: " + row);
        assertTrue(row.contains("\"protocol_version\": 2"), row);
        assertTrue(row.contains("discovery") && row.contains("rematerialize") && !row.contains("declarations"), row);
        assertEquals(5000L, world().clockOffsetMs(agent), "DP now minus the agent's clock");
        world().open(agent, new SessionOpening(1, "v".repeat(100), Set.of(), world().now().minus(Duration.ofDays(400))));
        assertTrue(world().rowText("agent_session", agent).contains("\"agent_version\": \"" + "v".repeat(64) + "\""), "capped at 64");
        assertEquals(Duration.ofHours(24).toMillis(), world().clockOffsetMs(agent), "an absurd skew is clamped");
        world().open(agent, new SessionOpening(1, "0.1.0", Set.of(), world().now().plus(Duration.ofDays(400))));
        assertEquals(-Duration.ofHours(24).toMillis(), world().clockOffsetMs(agent));
    }

    @Test
    void aHeartbeatWithASkewedAgentClockRecordsTheOffsetButNeverMovesLastSeen() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().advance(Duration.ofSeconds(30));
        world().sessions().heartbeat(session, world().now().minus(Duration.ofHours(10)));
        assertEquals(world().now(), world().sessionLastSeen(agent), "last_seen_at is the data plane's clock");
        assertEquals(Duration.ofHours(10).toMillis(), world().clockOffsetMs(agent));
        world().sessions().heartbeat(session, world().now().plus(Duration.ofHours(10)));
        assertEquals(world().now(), world().sessionLastSeen(agent), "an agent clock in the future does not move it either");
    }

    @Test
    void liveForAndFeaturesOfAreBoundToTheCallersOrganizationAndTheAgentsOwnTarget() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentKitWorld.Agent stranger = world().enrol();
        AgentSessionRef session = world().open(agent, new SessionOpening(1, "0.1.0", Set.of(AgentFeature.DECLARATIONS), world().now()));
        assertEquals(Set.of(), world().sessions().featuresOf(agent.agentId(), agent.placement()), "no lease yet");
        world().lease(agent, session);
        assertEquals(Set.of(AgentFeature.DECLARATIONS), world().sessions().featuresOf(agent.agentId(), agent.placement()));
        AgentPlacementRef forged = new AgentPlacementRef(stranger.tenantId(), agent.instanceId(), agent.placementId());
        assertFalse(world().sessions().liveFor(agent.agentId(), forged), "a forged tenant on the agent's placement");
        assertEquals(Set.of(), world().sessions().featuresOf(agent.agentId(), forged));
        assertFalse(world().sessions().liveFor(agent.agentId(), stranger.placement()), "another organization's placement");
        assertEquals(Set.of(), world().sessions().featuresOf(agent.agentId(), stranger.placement()));
        assertFalse(world().sessions().liveFor(stranger.agentId(), agent.placement()), "another agent");
    }
}
