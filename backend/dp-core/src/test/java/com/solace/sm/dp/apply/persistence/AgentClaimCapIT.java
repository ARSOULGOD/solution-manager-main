package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The claim query's agent filter (plan 015 T3.1, HF22, P15-6), over a real {@code sm_data}: when the worker's agent cap is
 * reached it claims with agent-target requests EXCLUDED, so a request on an agent target stays {@code REQUESTED} and the
 * remaining thread is only ever given dp-remote work. Which requests count as "on an agent target" is read from the registry.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentClaimCapIT {

    private static AgentEngineWorld w;

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_claim_cap");
    }

    private ApplyId admit(ScopeKey scope) {
        return ((AdmissionOutcome.Admitted) w.admission()
                .admit(new ApplyCommand(scope, Operation.APPLY, 1L, Strategy.MANUAL, null, "operator-1"))).applyId();
    }

    @Test
    void withTheCapReachedAnAgentRequestIsLeftRequestedAndADpRemoteOneBehindItIsClaimed() throws Exception {
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ApplyId agentApply = admit(w.agentScope(agent));            // the OLDER request
        Thread.sleep(5);
        ApplyId sshApply = admit(w.sshScope(tenant));
        TenantId t = new TenantId(tenant);
        ApplyClaimRepository claims = w.claims();

        Optional<ApplyClaimRepository.Claim> first = claims.claimNext(t, false);
        assertTrue(first.isPresent());
        assertEquals(sshApply, first.get().id(), "the cap skipped the older agent request and took the dp-remote one");
        assertFalse(first.get().agent());
        assertTrue(claims.claimNext(t, false).isEmpty(), "…and nothing else is claimable while the cap holds");

        Optional<ApplyClaimRepository.Claim> second = claims.claimNext(t, true);
        assertEquals(agentApply, second.orElseThrow().id(), "once a slot frees the agent request is claimed, and flagged");
        assertTrue(second.get().agent());
    }

    @Test
    void withoutTheCapTheOldestIsClaimedWhicheverTransport() throws Exception {
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ApplyId agentApply = admit(w.agentScope(agent));
        Thread.sleep(5);
        admit(w.sshScope(tenant));

        ApplyClaimRepository.Claim claim = w.claims().claimNext(new TenantId(tenant), true).orElseThrow();

        assertEquals(agentApply, claim.id());
        assertTrue(claim.agent());
    }

    /** An instance-wide stop reaches every live placement of the instance, so one on an agent target makes it an agent request. */
    @Test
    void anInstanceWideStopOverAnAgentPlacementCountsAsAnAgentRequest() {
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ScopeKey placementScope = w.agentScope(agent);
        ScopeKey instanceWide = new ScopeKey(placementScope.tenant(), placementScope.instance(), PlacementId.INSTANCE_WIDE);
        w.admission().admit(new ApplyCommand(instanceWide, Operation.STOP, null, null, null, "operator-1"));
        ApplyClaimRepository claims = w.claims();

        assertTrue(claims.claimNext(new TenantId(tenant), false).isEmpty(), "the stop reaches an agent target: held back by the cap");
        ApplyClaimRepository.Claim claim = claims.claimNext(new TenantId(tenant), true).orElseThrow();
        assertTrue(claim.agent());
    }

    @Test
    void theOriginalClaimStillClaimsEverything() {
        UUID tenant = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenant);
        ApplyId agentApply = admit(w.agentScope(agent));

        assertEquals(agentApply, w.claims().claimNextAsStarted(new TenantId(tenant)).orElseThrow(),
                "claimNextAsStarted is the uncapped form and is unchanged");
    }
}
