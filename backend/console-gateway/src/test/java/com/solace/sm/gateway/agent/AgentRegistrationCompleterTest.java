package com.solace.sm.gateway.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.governance.AgentInstanceGovernance;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.dp.api.agw.AgentRegistrationCompletion;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The completer's decisions, without a database (plan 015 T4.2): which control-plane call it makes, as whom, and what it tells the
 * data plane. The real two-plane behaviour (the actor in {@code change_audit}, the quota under the lock, the crash windows) is
 * {@code AgentRegistrationCompleterIT}'s in the launcher.
 */
class AgentRegistrationCompleterTest {

    private final UUID organization = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();
    private final UUID entry = UUID.randomUUID();
    private final UUID instance = UUID.randomUUID();
    private final AgentRequestOrigin origin = new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.9");

    private final List<String> calls = new ArrayList<>();
    private final List<RequestOrigin> origins = new ArrayList<>();
    private Set<UUID> alreadyRegistered = Set.of();
    private RuntimeException registerFails;

    private final InstanceGovernance principals = new InstanceGovernance() {
        @Override
        public GovernanceRegistration register(PrincipalRef actor, RequestOrigin o, UUID org, UUID env, UUID entry, UUID id) {
            throw new AssertionError("an agent's registration must never be written as a principal's act");
        }

        @Override
        public Set<UUID> registered(UUID org, Collection<UUID> ids) {
            calls.add("registered");
            return alreadyRegistered;
        }

        @Override
        public boolean withinQuota(UUID org, String solution) {
            throw new AssertionError("the quota is checked inside the registration, under the entitlement lock");
        }
    };

    private final AgentInstanceGovernance agents = (agentId, o, org, env, entry, id) -> {
        calls.add("registerForAgent");
        origins.add(o);
        assertEquals(agent, agentId, "the agent is the actor");
        assertEquals(organization, org);
        assertEquals(environment, env);
        assertEquals(this.entry, entry);
        assertEquals(instance, id);
        if (registerFails != null) {
            throw registerFails;
        }
        return new GovernanceRegistration(UUID.randomUUID(), true, id);
    };

    private final AgentRegistrationCompleter completer = new AgentRegistrationCompleter(principals, agents);

    private AgentRegistrationCompletion.Pending pending() {
        return new AgentRegistrationCompletion.Pending(organization, agent, environment, entry, instance, origin);
    }

    private final List<String> told = new ArrayList<>();
    private final AgentRegistrationCompletion.Settlement settlement = new AgentRegistrationCompletion.Settlement() {
        @Override
        public void registered() {
            told.add("registered");
        }

        @Override
        public void refused(GatewayCode code) {
            told.add("refused:" + code.wire());
        }
    };

    @Test
    void anUnregisteredInstanceIsRegisteredAsTheAgentWithTheRpcsOriginAndConfirmed() {
        completer.complete(pending(), settlement);

        assertEquals(List.of("registered", "registerForAgent"), calls);
        assertEquals(List.of("registered"), told);
        assertEquals(origin.correlationId(), origins.get(0).correlationId(), "the RPC's real correlation id");
        assertEquals("198.51.100.9", origins.get(0).clientAddress(), "and the gRPC peer's address");
    }

    @Test
    void anInstanceTheControlPlaneAlreadyHoldsIsOnlyConfirmedNeverInsertedTwice() {
        alreadyRegistered = Set.of(instance);

        completer.complete(pending(), settlement);

        assertEquals(List.of("registered"), calls, "the node exists: nothing is written");
        assertEquals(List.of("registered"), told);
    }

    @Test
    void theQuotaOrTheEntitlementIsAEntitlementQuotaRefusal() {
        registerFails = new QuotaExceededException();
        completer.complete(pending(), settlement);
        registerFails = new EntitlementRefusedException();
        completer.complete(pending(), settlement);

        assertEquals(List.of("refused:entitlement.quota", "refused:entitlement.quota"), told);
    }

    @Test
    void anEnvironmentOrEntryThatIsNoLongerTheOrganizationsIsRefusedOutsideTheEnvelope() {
        registerFails = new ResourceNotFoundException(ResourceKind.ENVIRONMENT);

        completer.complete(pending(), settlement);

        assertEquals(List.of("refused:agent.discovery.outside.envelope"), told);
    }

    @Test
    void anUnexpectedFailureIsNotADefiniteRefusalAndTellsTheDataPlaneNothing() {
        registerFails = new IllegalStateException("connection lost");

        assertThrows(IllegalStateException.class, () -> completer.complete(pending(), settlement));

        assertTrue(told.isEmpty(), "the PENDING row stays: the next Connect's report, or the sweep, decides it");
    }
}
