package com.solace.sm.launcher;

import com.solace.sm.cp.api.agent.AgentRegistration;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.AgentRegistrationState;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter 3 of 3 (BE-D69): gives the DP the control plane's current registration (revocation, epochs, capabilities). It
 * carries no placement set: the control plane holds none (AGW-D24).
 */
final class ControlPlaneRegistrationSource implements AgentRegistrationSource {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ControlPlaneRegistrationSource.class);
    private final ControlPlaneAgentGateway controlPlane;

    ControlPlaneRegistrationSource(ControlPlaneAgentGateway controlPlane) {
        this.controlPlane = controlPlane;
    }

    @Override
    public Map<UUID, AgentRegistrationState> current(Collection<UUID> agentIds) {
        try {
            Map<UUID, AgentRegistrationState> result = new LinkedHashMap<>();
            controlPlane.registrations(agentIds).forEach((id, registration) -> result.put(id, state(registration)));
            return result;
        } catch (GatewayPortException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("control-plane registration read failed: {} {}", e.getClass().getName(), FailureDiagnostics.of(e));
            throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "control-plane registration unavailable");
        }
    }

    private static AgentRegistrationState state(AgentRegistration registration) {
        return new AgentRegistrationState(registration.agentId(), registration.active(), registration.targetId(),
                AgentCapabilities.all(registration.capabilities()), registration.agentRevocationEpoch(),
                registration.fleetRevocationEpoch());
    }
}
