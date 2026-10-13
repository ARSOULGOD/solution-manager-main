package com.solace.sm.dp.api.agw;

import java.util.Objects;
import java.util.UUID;

/**
 * The sink the data plane's discovery intake calls, after its own commit, to finish registering a PENDING agent-declared
 * instance (plan 015 T4.2, {@code OR-27}, ruled by {@code OR-128} (15)). {@code console-gateway}'s
 * {@code AgentRegistrationCompleter} implements it: the gateway is the one module permitted both planes' {@code -api}s
 * ({@code I7}), so it writes the control plane's governance node ({@code AGENT_INSTANCE_REGISTERED}, the agent as actor) and then
 * tells the data plane what happened through the {@link Settlement} it was handed. Nothing enumerates tenants: the call is made
 * for one agent, in the RPC that reported the container, with the tenant the data plane resolved from the agent's identity
 * ({@code I5}).
 *
 * <p><b>The call is never the contract.</b> The durable contract is the {@code PENDING} {@code agent_discovery} row: if this call
 * is lost (a crash, an exception) the agent's next discovery report, sent on every {@code Connect}, makes it again, or the
 * {@code BE-D32} sweep abandons and tombstones the row. An implementation must therefore be idempotent for an instance it has
 * already registered, and must not throw for a definite refusal: it answers {@link Settlement#refused}.
 *
 * <p>Added by plan 015 T4.2 (HF18): the one new type of W4's lane A, declared and used by the same task.
 */
public interface AgentRegistrationCompletion {

    /**
     * @param pending    the reserved instance to register
     * @param settlement how to tell the data plane the outcome; call exactly one of its methods, once
     */
    void complete(Pending pending, Settlement settlement);

    /**
     * Every id is the data plane's own record of the agent's ENROLMENT, never a value in the agent's report.
     *
     * @param organizationId   the tenant, resolved from the agent's identity
     * @param environmentId    the environment the agent was enrolled into
     * @param catalogSolutionId the catalog entry the agent was enrolled with
     * @param instanceId       the PENDING instance the data plane reserved
     * @param origin           the gateway-minted origin of the RPC: the correlation id and client address of the control-plane write
     */
    record Pending(UUID organizationId, UUID agentId, UUID environmentId, UUID catalogSolutionId, UUID instanceId,
            AgentRequestOrigin origin) {
        public Pending {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(environmentId, "environmentId");
            Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** The data plane's half of the completion, bound to the one pending row it was created for. */
    interface Settlement {

        /** The control plane holds the governance node: the data plane activates the instance and marks the row {@code REGISTERED}. */
        void registered();

        /**
         * A definite refusal (the quota, the entitlement, an environment or entry that is no longer the organization's): the data
         * plane abandons the reserved instance and its placement, marks the row {@code REFUSED} with {@code code} and records an
         * {@code agent_event}.
         */
        void refused(GatewayCode code);
    }
}
