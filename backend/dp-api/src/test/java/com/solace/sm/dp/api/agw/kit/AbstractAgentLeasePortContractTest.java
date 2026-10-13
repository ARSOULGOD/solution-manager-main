package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.LeaseGrant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentLeasePort} must do (plan 015 T2.1b, {@code AGW-D24}): the data plane supplies the placement set and the
 * issuer signs exactly it; a renewal nonce is single use and only its digest is kept; a stale session is refused before any write.
 * "Idempotent by request id" means no second effect: a replayed {@code issue} mints no second lease and a replayed {@code renew}
 * finds its nonce consumed.
 */
public abstract class AbstractAgentLeasePortContractTest {

    protected abstract AgentKitWorld world();

    @Test
    void issueGrantsExactlyTheLivePlacementsOfTheAgentsOwnTarget() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        UUID retired = world().addPlacement(agent);
        world().retirePlacement(agent, retired);
        AgentSessionRef session = world().open(agent);
        LeaseGrant grant = world().lease(agent, session);
        assertEquals(java.util.Set.of(agent.placementId(), second), new java.util.HashSet<>(grant.placementIds()));
        assertEquals(1, world().rows("agent_lease", agent));
    }

    @Test
    void aReplayedIssueAfterALostReplyReturnsTheSameGrantAndMintsNoSecondLease() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().leases().issue("lease-1", session, world().now(), world().origin());
        LeaseGrant again = world().leases().issue("lease-1", session, world().now(), world().origin());
        assertEquals(first.nonce(), again.nonce(), "the issuer is deterministic in its inputs, so the retry gets the grant it lost");
        assertEquals(1, world().rows("agent_lease", agent));
        refused(GatewayCode.ISSUE_NOT_ALLOWED, () -> world().leases().issue("lease-2", session, world().now(), world().origin()));
        assertEquals(1, world().rows("agent_lease", agent));
    }

    @Test
    void aLostRenewReplyIsReplayedToTheSameGrantWithoutAnotherWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        LeaseGrant renewed = world().leases().renew("renew-1", session, first.nonce(), world().now(), world().origin());
        String digest = world().activeLeaseNonceDigest(agent);
        long rows = world().rows("agent_lease", agent);
        LeaseGrant replay = world().leases().renew("renew-1", session, first.nonce(), world().now(), world().origin());
        assertEquals(renewed.nonce(), replay.nonce());
        assertEquals(digest, world().activeLeaseNonceDigest(agent), "a replay writes nothing");
        assertEquals(rows, world().rows("agent_lease", agent));
        refused(GatewayCode.LEASE_RENEW_REFUSED,
                () -> world().leases().renew("renew-other", session, first.nonce(), world().now(), world().origin()));
    }

    @Test
    void issueAfterAnEpochRaiseIsNotAllowed() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().lease(agent, session);
        world().controlPlane(agent, true, 2, 1, List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE));
        refused(GatewayCode.ISSUE_NOT_ALLOWED,
                () -> world().leases().issue("lease-after-raise", session, world().now(), world().origin()));
        assertEquals(1, world().rows("agent_lease", agent), "no nonce-free grant was minted for an agent that held a lease");
    }

    @Test
    void issueSyncsTheRegistrationFirstSoARevokedIdentityGetsNoLease() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().controlPlane(agent, false, 1, 1, List.of());
        refused(GatewayCode.IDENTITY_REVOKED, () -> world().leases().issue("lease-rev", session, world().now(), world().origin()));
        assertEquals(0, world().rows("agent_lease", agent));
        assertTrue(world().identityRevoked(agent));
    }

    @Test
    void aGrantThatDoesNotMatchTheIdentityIsRefusedBeforeAnyWrite() {
        String other = "e".repeat(64);
        java.util.Map<String, java.util.function.UnaryOperator<LeaseGrant>> corruptions = new java.util.LinkedHashMap<>();
        corruptions.put("another certificate", g -> new LeaseGrant(g.agentId(), other, g.targetId(), g.placementIds(),
                g.agentRevocationEpoch(), g.fleetRevocationEpoch(), g.issuedAt(), g.expiresAt(), g.audience(), g.nonce(),
                g.capabilities(), g.verbCeilings(), g.admissionMargin(), g.serverClock()));
        corruptions.put("another target", g -> new LeaseGrant(g.agentId(), g.certificateThumbprint(), UUID.randomUUID(),
                g.placementIds(), g.agentRevocationEpoch(), g.fleetRevocationEpoch(), g.issuedAt(), g.expiresAt(), g.audience(),
                g.nonce(), g.capabilities(), g.verbCeilings(), g.admissionMargin(), g.serverClock()));
        corruptions.put("other epochs", g -> new LeaseGrant(g.agentId(), g.certificateThumbprint(), g.targetId(),
                g.placementIds(), g.agentRevocationEpoch() + 1, g.fleetRevocationEpoch(), g.issuedAt(), g.expiresAt(),
                g.audience(), g.nonce(), g.capabilities(), g.verbCeilings(), g.admissionMargin(), g.serverClock()));
        corruptions.put("a capability the identity lacks", g -> new LeaseGrant(g.agentId(), g.certificateThumbprint(),
                g.targetId(), g.placementIds(), g.agentRevocationEpoch(), g.fleetRevocationEpoch(), g.issuedAt(), g.expiresAt(),
                g.audience(), g.nonce(), List.of(AgentCapability.REPORT_ONLY), g.verbCeilings(), g.admissionMargin(),
                g.serverClock()));
        corruptions.put("a negative margin", g -> new LeaseGrant(g.agentId(), g.certificateThumbprint(), g.targetId(),
                g.placementIds(), g.agentRevocationEpoch(), g.fleetRevocationEpoch(), g.issuedAt(), g.expiresAt(), g.audience(),
                g.nonce(), g.capabilities(), g.verbCeilings(), Duration.ofSeconds(-1), g.serverClock()));
        corruptions.put("a lifetime shorter than its longest ceiling plus margin", g -> new LeaseGrant(g.agentId(),
                g.certificateThumbprint(), g.targetId(), g.placementIds(), g.agentRevocationEpoch(), g.fleetRevocationEpoch(),
                g.issuedAt(), g.issuedAt().plusSeconds(5), g.audience(), g.nonce(), g.capabilities(), g.verbCeilings(),
                g.admissionMargin(), g.serverClock()));
        for (var entry : corruptions.entrySet()) {
            AgentKitWorld.Agent agent = world().enrol();
            AgentSessionRef session = world().open(agent);
            world().issuerCorrupts(entry.getValue());
            try {
                refused(GatewayCode.GRANT_INVALID, () -> world().leases().issue("lease-bad", session, world().now(), world().origin()));
            } finally {
                world().issuerCorrupts(null);
            }
            assertEquals(0, world().rows("agent_lease", agent), entry.getKey() + " wrote a lease");
        }
    }

    @Test
    void aStaleSessionIsRefusedBeforeAnyWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().open(agent);
        world().open(agent);
        refused(GatewayCode.STALE_EPOCH, () -> world().leases().issue("lease-stale", stale, world().now(), world().origin()));
        refused(GatewayCode.STALE_EPOCH, () -> world().leases().renew("renew-stale", stale, "any", world().now(), world().origin()));
        assertEquals(0, world().rows("agent_lease", agent));
    }

    @Test
    void aRenewalConsumesItsNonceExactlyOnce() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        LeaseGrant renewed = world().leases().renew("renew-1", session, first.nonce(), world().now(), world().origin());
        assertNotEquals(first.nonce(), renewed.nonce(), "a renewal mints a new nonce");
        refused(GatewayCode.LEASE_RENEW_REFUSED,
                () -> world().leases().renew("renew-again", session, first.nonce(), world().now(), world().origin()));
        LeaseGrant again = world().leases().renew("renew-2", session, renewed.nonce(), world().now(), world().origin());
        assertNotEquals(renewed.nonce(), again.nonce());
        assertEquals(1, activeLeases(agent), "exactly one ACTIVE lease at any time");
    }

    private long activeLeases(AgentKitWorld.Agent agent) {
        return world().rowText("agent_lease", agent).split("\"state\": ?\"ACTIVE\"", -1).length - 1;
    }

    @Test
    void aWrongNonceIsRefusedAndTheLeaseSurvives() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        refused(GatewayCode.LEASE_RENEW_REFUSED, () -> world().leases().renew("renew-bad", session, "not-the-nonce", world().now(), world().origin()));
        world().leases().renew("renew-good", session, first.nonce(), world().now(), world().origin());
    }

    @Test
    void renewWithoutAnyPriorLeaseIsRefused() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> world().leases().renew("renew-none", session, "n", world().now(), world().origin()));
    }

    @Test
    void onlyTheDigestOfTheNonceIsStoredAndCurrentNeverDiscloses() throws Exception {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        assertTrue(world().leases().current(session).isEmpty(), "no lease has ever been held");
        LeaseGrant grant = world().lease(agent, session);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(grant.nonce().getBytes(StandardCharsets.UTF_8)));
        assertEquals(digest, world().activeLeaseNonceDigest(agent));
        assertFalse(world().anyAgentRowContains(agent, grant.nonce()), "the bearer nonce is in no agent row");
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> world().leases().current(session));
    }

    @Test
    void aRevokedIdentityCannotRenewAndItsLeaseStopsGrantingAnything() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant grant = world().lease(agent, session);
        world().controlPlane(agent, false, 1, 1, List.of());
        refused(GatewayCode.IDENTITY_REVOKED, () -> world().leases().renew("renew-rev", session, grant.nonce(), world().now(), world().origin()));
        assertTrue(world().identityRevoked(agent));
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()));
    }

    @Test
    void aGrantNamingAPlacementTheDataPlaneDidNotSupplyIsRefused() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().issuerAlsoNames(UUID.randomUUID());
        refused(GatewayCode.GRANT_INVALID, () -> world().leases().issue("lease-extra", session, world().now(), world().origin()));
        assertEquals(0, world().rows("agent_lease", agent));
    }

    @Test
    void theIssuerIsAskedAboutTheIdentityTheDataPlaneDerived() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        world().lease(agent, session);
        assertEquals(agent.tenantId(), world().lastIssuedFor().tenantId());
        assertEquals(agent.targetId(), world().lastIssuedFor().targetId());
        assertEquals(agent.tenantId(), world().storedTenant("agent_lease", agent));
    }

    @Test
    void aRenewalReflectsAPermissionChangeInsteadOfBeingRefusedForAStaleEpoch() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        world().controlPlane(agent, true, 2, 1, List.of(AgentCapability.REPORT_ONLY));
        LeaseGrant renewed = world().leases().renew("renew-perm", session, first.nonce(), world().now(), world().origin());
        assertEquals(2, renewed.agentRevocationEpoch());
        assertEquals(List.of(AgentCapability.REPORT_ONLY), renewed.capabilities());
    }

    @Test
    void aLapsedLeaseCannotBeReplacedByIssueButCanBeRenewed() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        world().advance(Duration.between(world().now(), first.expiresAt()).plusSeconds(1));
        refused(GatewayCode.ISSUE_NOT_ALLOWED, () -> world().leases().issue("lease-again", session, world().now(), world().origin()));
        world().leases().renew("renew-lapsed", session, first.nonce(), world().now(), world().origin());
    }

    @Test
    void aReplayedRenewReturnsTheStoredRowsTimesEvenWhenTheIssuersClockHasMoved() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        LeaseGrant renewed = world().leases().renew("renew-t", session, first.nonce(), world().now(), world().origin());
        world().advance(Duration.ofMinutes(5));
        LeaseGrant replay = world().leases().renew("renew-t", session, first.nonce(), world().now(), world().origin());
        assertEquals(renewed.nonce(), replay.nonce());
        assertEquals(renewed.issuedAt(), replay.issuedAt(), "the agent must not be promised a later start than the DP holds");
        assertEquals(renewed.expiresAt(), replay.expiresAt(), "nor a later expiry: the stored row is what admission checks");
        assertEquals(world().now(), replay.serverClock());
    }

    /**
     * Plan 015 W3 integration finding 2: the reply to a renewal is lost and the agent's permissions change before it retries. The
     * retry's nonce matches the request whose reply was lost, so it is that request: it gets the STORED grant (the row's
     * permissions, nothing minted, nothing written, no nonce-free re-issue) and the next renewal, under the nonce it now holds,
     * picks the change up. Refusing it stranded the agent in a reconnect loop.
     */
    @Test
    void aReplayAfterACapabilityChangeReturnsTheStoredGrantAndTheNextRenewalPicksTheChangeUp() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        LeaseGrant renewed = world().leases().renew("renew-c", session, first.nonce(), world().now(), world().origin());
        String digest = world().activeLeaseNonceDigest(agent);
        long rows = world().rows("agent_lease", agent);
        world().controlPlane(agent, true, 1, 1, List.of(AgentCapability.APPLY_CONFIG));
        LeaseGrant replay = world().leases().renew("renew-c", session, first.nonce(), world().now(), world().origin());
        assertEquals(renewed.nonce(), replay.nonce(), "the agent gets the nonce its lost reply carried");
        assertEquals(renewed.capabilities(), replay.capabilities(), "with the permissions the stored row holds");
        assertEquals(renewed.expiresAt(), replay.expiresAt());
        assertEquals(digest, world().activeLeaseNonceDigest(agent), "a replay writes nothing");
        assertEquals(rows, world().rows("agent_lease", agent));
        LeaseGrant next = world().leases().renew("renew-d", session, replay.nonce(), world().now(), world().origin());
        assertEquals(List.of(AgentCapability.APPLY_CONFIG), next.capabilities(), "the next renewal reflects the change");
    }

    @Test
    void aReplayAfterAPlacementChangeReturnsTheStoredGrantAndTheNextRenewalPicksTheChangeUp() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        LeaseGrant renewed = world().leases().renew("renew-p", session, first.nonce(), world().now(), world().origin());
        String digest = world().activeLeaseNonceDigest(agent);
        long rows = world().rows("agent_lease", agent);
        UUID added = world().addPlacement(agent);
        LeaseGrant replay = world().leases().renew("renew-p", session, first.nonce(), world().now(), world().origin());
        assertEquals(renewed.nonce(), replay.nonce());
        assertEquals(java.util.Set.of(agent.placementId()), new java.util.HashSet<>(replay.placementIds()),
                "the stored row's placements: the agent is not promised the new one yet");
        assertEquals(digest, world().activeLeaseNonceDigest(agent));
        assertEquals(rows, world().rows("agent_lease", agent));
        LeaseGrant next = world().leases().renew("renew-q", session, replay.nonce(), world().now(), world().origin());
        assertEquals(java.util.Set.of(agent.placementId(), added), new java.util.HashSet<>(next.placementIds()));
    }
}
