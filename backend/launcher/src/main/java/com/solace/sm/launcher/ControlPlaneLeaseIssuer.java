package com.solace.sm.launcher;

import com.solace.sm.cp.api.agent.AgentLeaseMaterial;
import com.solace.sm.cp.api.agent.AgentLeaseRefusedException;
import com.solace.sm.cp.api.agent.AgentLeaseRequest;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adapter 2 of 3 (BE-D69): the data plane's {@link LeaseIssuer}, answered by the control plane. The placement set is the
 * one the DATA PLANE passes and is handed to the control plane as given; the control plane signs exactly it (AGW-D24).
 */
final class ControlPlaneLeaseIssuer implements LeaseIssuer {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ControlPlaneLeaseIssuer.class);
    private final ControlPlaneAgentGateway controlPlane;

    ControlPlaneLeaseIssuer(ControlPlaneAgentGateway controlPlane) {
        this.controlPlane = controlPlane;
    }

    @Override
    public LeaseGrant issue(AgentIdentity identity, AgentSessionRef session, Instant agentClock,
            String requestId, String consumedNonce, List<UUID> placementIds, AgentRequestOrigin origin) {
        try {
            AgentLeaseMaterial material = controlPlane.issueLease(new AgentLeaseRequest(identity.tenantId(), identity.agentId(),
                    identity.targetId(), identity.certificateThumbprint(), identity.agentRevocationEpoch(),
                    identity.fleetRevocationEpoch(), agentClock, requestId, consumedNonce, placementIds));
            return new LeaseGrant(material.agentId(), material.certificateThumbprint(), material.targetId(),
                    material.placementIds(), material.agentRevocationEpoch(), material.fleetRevocationEpoch(),
                    material.issuedAt(), material.expiresAt(), material.audience(), material.nonce(),
                    AgentCapabilities.all(material.capabilities()),
                    material.verbCeilings().entrySet().stream().collect(Collectors.toMap(
                            entry -> operation(entry.getKey()), Map.Entry::getValue)),
                    material.admissionMargin(), material.serverClock());
        } catch (GatewayPortException e) {
            throw e;
        } catch (AgentLeaseRefusedException e) {
            throw new GatewayPortException(code(e.reason()), "control-plane lease refused");
        } catch (IllegalArgumentException e) {
            // A malformed request (not a refusal by reason): treated as not allowed, never as a revocation.
            throw new GatewayPortException(GatewayCode.ISSUE_NOT_ALLOWED, "control-plane lease refused");
        } catch (RuntimeException e) {
            LOG.error("control-plane lease issuance unavailable: {} {}", e.getClass().getName(), FailureDiagnostics.of(e));
            LOG.debug("control-plane lease failure detail", e);
            throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "control-plane lease unavailable");
        }
    }

    /**
     * How the gateway reads each code (AgentGatewayService): IDENTITY_REVOKED closes the streams and ends the session
     * (revokeAndClose), so it is for a revocation and a bumped agent epoch only; TARGET_MISMATCH is its own refusal;
     * ISSUE_NOT_ALLOWED on a first issue becomes LEASE_RENEW_REQUIRED and on a refused renewal RE_ENROLL_REQUIRED, the right
     * answer for a DP that is merely behind (FLEET_EPOCH_STALE: the fleet bump is not proof against THIS agent, and the DP's
     * reconcile through registrations() closes the streams if it really is revoked) and for an agent that must enrol again
     * (not enrolled, certificate not the issued one or expired).
     */
    static GatewayCode code(AgentLeaseRefusedException.Reason reason) {
        return switch (reason) {
            case REVOKED, AGENT_EPOCH_STALE -> GatewayCode.IDENTITY_REVOKED;
            case TARGET_MISMATCH -> GatewayCode.TARGET_MISMATCH;
            case FLEET_EPOCH_STALE, NOT_ENROLLED, CERTIFICATE_MISMATCH, CERTIFICATE_EXPIRED -> GatewayCode.ISSUE_NOT_ALLOWED;
        };
    }

    private static AgentOperation operation(String value) {
        try { return AgentOperation.valueOf(value); }
        catch (IllegalArgumentException e) { throw new GatewayPortException(GatewayCode.ISSUE_NOT_ALLOWED, "unsupported lease verb"); }
    }
}
