package com.solace.sm.cp.api.agent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The CP service the launcher's three adapters call (BE-D69); transport and DP persistence stay outside the control
 * plane. The launcher alone composes this with the data plane's ports.
 */
public interface ControlPlaneAgentGateway {

    /**
     * Consumes the token (or replays the same token and CSR to the same material), signs the certificate and creates
     * the CP target row, in one transaction under {@code AGENT_ENROLLED} with the agent as actor.
     *
     * @throws AgentEnrollmentRefusedException the token is unknown, expired, consumed by another CSR, or its target
     *         name is taken
     * @throws IllegalArgumentException the CSR is malformed or its signature does not verify
     * @throws IllegalStateException the CA is unusable (no operator keystore): nothing was written
     */
    AgentEnrollmentMaterial enroll(AgentEnrollmentRequest request);

    /**
     * Signs a lease for an active agent over exactly {@code request.placementIds()}. Writes no row (plan 015 T2.2
     * Step 0 (b)).
     *
     * @throws AgentLeaseRefusedException by reason: revoked, a stale epoch, a target or certificate that is not the
     *         registration's, an expired certificate, or an agent never enrolled
     */
    AgentLeaseMaterial issueLease(AgentLeaseRequest request);

    /**
     * The CP's current registration for each agent, revoked ones included ({@code active == false}); an agent the CP no
     * longer knows is absent. The DP reads this to keep its copy of an agent's authority from going stale.
     * {@code AgentRegistrationSource.current} names no organization (the reconcile spans sessions), so this one read is
     * keyed by agent id alone; agent ids are globally unique (V021) and nothing but the registration's own state is returned.
     */
    Map<UUID, AgentRegistration> registrations(Collection<UUID> agentIds);
}
