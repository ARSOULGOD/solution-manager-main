package com.solace.sm.gateway.agent;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.governance.AgentInstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.dp.api.agw.AgentRegistrationCompletion;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The control plane's half of {@code OR-27} for an instance an agent discovered (plan 015 T4.2, {@code OR-128} (15)): the data plane
 * has reserved a PENDING instance and calls this, in the RPC that reported the container, with the tenant it resolved from the
 * agent's identity and the RPC's gateway-minted origin. This registers the governance node through the same protocol an operator's
 * declare uses, with {@code OR-40}'s quota checked under the entitlement row's lock, under {@code AGENT_INSTANCE_REGISTERED} with the
 * AGENT as actor and no principal ({@code BE-D70}), then tells the data plane the outcome through the {@link Settlement} it was
 * handed: {@code registered()} (the data plane activates the instance and marks the row {@code REGISTERED}) or, for a definite
 * refusal, {@code refused(code)} (the data plane abandons the reservation, marks the row {@code REFUSED} and records an
 * {@code agent_event}).
 *
 * <p>Composed here because the gateway is the one module permitted both planes' {@code -api}s ({@code I7}); the gateway of agents
 * ({@code agent-gateway}) imports no {@code cp..}. Nothing here enumerates tenants and nothing is a loop ({@code OR-113} (7)).
 *
 * <p><b>The call is never the contract</b> ({@code AGW-D2}). The durable contract is the data plane's PENDING row. If this fails after
 * the control plane committed but before the data plane confirmed, the agent's next discovery report (sent on every {@code Connect})
 * makes the call again, and the first thing it does is ask the control plane whether the node already exists: a registered node is
 * only confirmed, never inserted twice. If the control plane never accepted it, the {@code BE-D32} sweep abandons and tombstones it
 * after the window. Only a DEFINITE refusal ends the row here (the quota, the entitlement, an environment or entry that is no longer
 * the organization's); anything else (a lost connection, an unexpected exception) propagates and the row stays PENDING.
 */
@Component
public class AgentRegistrationCompleter implements AgentRegistrationCompletion {

    private final InstanceGovernance governance;
    private final AgentInstanceGovernance agentGovernance;

    @Autowired
    public AgentRegistrationCompleter(InstanceGovernance governance, AgentInstanceGovernance agentGovernance) {
        this.governance = Objects.requireNonNull(governance, "governance");
        this.agentGovernance = Objects.requireNonNull(agentGovernance, "agentGovernance");
    }

    @Override
    public void complete(Pending pending, Settlement settlement) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(settlement, "settlement");
        boolean alreadyRegistered = governance.registered(pending.organizationId(), List.of(pending.instanceId()))
                .contains(pending.instanceId());
        if (!alreadyRegistered) {
            // The RPC's own correlation id and the gRPC peer's address: the control-plane write is attributable to this call.
            RequestOrigin origin = new RequestOrigin(pending.origin().clientAddress(), pending.origin().correlationId());
            try {
                agentGovernance.registerForAgent(pending.agentId(), origin, pending.organizationId(), pending.environmentId(),
                        pending.catalogSolutionId(), pending.instanceId());
            } catch (QuotaExceededException | EntitlementRefusedException refused) {
                settlement.refused(GatewayCode.ENTITLEMENT_QUOTA);
                return;
            } catch (ResourceNotFoundException gone) {
                // the environment or the catalog entry the agent was enrolled into is not the organization's any more
                settlement.refused(GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE);
                return;
            }
        }
        settlement.registered();
    }
}
