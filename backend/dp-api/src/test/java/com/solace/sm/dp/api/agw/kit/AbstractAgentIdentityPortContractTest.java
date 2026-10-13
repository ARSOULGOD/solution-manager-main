package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.LeaseGrant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentIdentityPort} must do (plan 015 T2.1b; ported from the branch's {@code AgentRevocationIT}): resolution
 * returns server-derived ids, the sweep reads the control plane once for every session, a revocation is sticky and ends the
 * session and the lease, a read failure is a coded refusal that changes nothing, and a changed registration makes the held
 * lease grant nothing until the agent renews. The sweep never re-issues on its own (only the digest of a nonce is stored), so
 * {@code REISSUED} is not an answer here: the agent's renewal is.
 */
public abstract class AbstractAgentIdentityPortContractTest {

    protected abstract AgentKitWorld world();

    @Test
    void resolveReturnsTheServerDerivedIdentityAndNothingForAnUnknownOrMalformedThumbprint() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentIdentity identity = world().identities().resolve(agent.thumbprint()).orElseThrow();
        assertEquals(agent.agentId(), identity.agentId());
        assertEquals(agent.tenantId(), identity.tenantId());
        assertEquals(agent.targetId(), identity.targetId());
        assertFalse(identity.revoked());
        assertTrue(world().identities().resolve("f".repeat(64)).isEmpty());
        assertTrue(world().identities().resolve("not-a-thumbprint").isEmpty());
        assertTrue(world().identities().resolve(null).isEmpty());
    }

    @Test
    void aControlPlaneRevocationEndsTheSessionAndTheLeaseAndMarksTheIdentity() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        world().controlPlane(agent, false, 2, 1, List.of());
        IdentityReconciliation result = world().identities().reconcile(List.of(session)).get(0);
        assertEquals(IdentityReconciliation.Status.REVOKED, result.status());
        assertTrue(world().identityRevoked(agent));
        assertTrue(world().sessionClosed(agent));
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()));
        refused(GatewayCode.STALE_EPOCH, () -> world().sessions().heartbeat(session, world().now()));
        refused(GatewayCode.IDENTITY_REVOKED, () -> world().open(agent));
    }

    @Test
    void aRevokedIdentityStaysRevokedEvenIfTheControlPlaneFlipsBackToActive() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        world().controlPlane(agent, false, 2, 1, List.of());
        world().identities().reconcile(List.of(session));
        world().controlPlane(agent, true, 2, 1, List.of(AgentCapability.APPLY_CONFIG));
        refused(GatewayCode.IDENTITY_REVOKED, () -> world().open(agent));
        assertEquals(IdentityReconciliation.Status.REVOKED, world().identities().reconcile(List.of(session)).get(0).status());
    }

    @Test
    void anAgentTheControlPlaneNoLongerKnowsIsTreatedAsRevoked() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        world().controlPlaneForgets(agent);
        assertEquals(IdentityReconciliation.Status.REVOKED, world().identities().reconcile(List.of(session)).get(0).status());
        assertTrue(world().identityRevoked(agent));
    }

    @Test
    void aPermissionChangeMakesTheHeldLeaseGrantNothingUntilTheAgentRenews() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant held = world().lease(agent, session);
        assertTrue(world().sessions().liveFor(agent.agentId(), agent.placement()));
        world().controlPlane(agent, true, 2, 1, List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE));
        IdentityReconciliation result = world().identities().reconcile(List.of(session)).get(0);
        assertEquals(IdentityReconciliation.Status.RENEWAL_REQUIRED, result.status());
        assertEquals(2, result.agentRevocationEpoch());
        assertFalse(world().sessions().liveFor(agent.agentId(), agent.placement()), "a lease minted before the change grants nothing");
        world().leases().renew("renew-after-change", session, held.nonce(), world().now(), world().origin());
        assertTrue(world().sessions().liveFor(agent.agentId(), agent.placement()));
    }

    @Test
    void aLapsedLeaseUnderAHealthyIdentityNeedsARenewal() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant held = world().lease(agent, session);
        assertEquals(IdentityReconciliation.Status.UNCHANGED, world().identities().reconcile(List.of(session)).get(0).status());
        world().advance(Duration.between(world().now(), held.expiresAt()).plusSeconds(1));
        assertEquals(IdentityReconciliation.Status.RENEWAL_REQUIRED, world().identities().reconcile(List.of(session)).get(0).status());
    }

    @Test
    void aControlPlaneReadFailureIsACodedRefusalAndChangesNothing() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        world().controlPlaneDown(true);
        try {
            refused(GatewayCode.INTERNAL_UNAVAILABLE, () -> world().identities().reconcile(List.of(session)));
        } finally {
            world().controlPlaneDown(false);
        }
        assertFalse(world().identityRevoked(agent));
        assertFalse(world().sessionClosed(agent));
        world().sessions().heartbeat(session, world().now());
    }

    @Test
    void aSessionThatIsNoLongerTheOwnerIsReportedStaleNotRevoked() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef old = world().open(agent);
        world().open(agent);
        assertEquals(IdentityReconciliation.Status.STALE, world().identities().reconcile(List.of(old)).get(0).status());
        assertFalse(world().identityRevoked(agent));
    }

    @Test
    void oneBatchedReadServesEverySession() {
        AgentKitWorld.Agent a = world().enrol();
        AgentKitWorld.Agent b = world().enrol();
        AgentKitWorld.Agent c = world().enrol();
        List<AgentSessionRef> sessions = List.of(world().ready(a), world().ready(b), world().ready(c));
        int before = world().controlPlaneReads();
        List<IdentityReconciliation> results = world().identities().reconcile(sessions);
        assertEquals(3, results.size());
        assertEquals(before + 1, world().controlPlaneReads());
    }

    @Test
    void aControlPlaneReadThatIsBehindNeverLowersTheEpochsTheDataPlaneHolds() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        var caps = List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE);
        world().controlPlane(agent, true, 3, 2, caps);
        world().identities().reconcile(List.of(session));
        assertEquals(3, world().identities().resolve(agent.thumbprint()).orElseThrow().agentRevocationEpoch());
        world().controlPlane(agent, true, 2, 1, caps);                      // a stale replica answers
        world().identities().reconcile(List.of(session));
        AgentIdentity after = world().identities().resolve(agent.thumbprint()).orElseThrow();
        assertEquals(3, after.agentRevocationEpoch());
        assertEquals(2, after.fleetRevocationEpoch());
    }
}
