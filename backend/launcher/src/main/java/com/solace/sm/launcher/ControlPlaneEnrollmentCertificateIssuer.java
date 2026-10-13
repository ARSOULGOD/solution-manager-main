package com.solace.sm.launcher;

import com.solace.sm.cp.api.agent.AgentEnrollmentMaterial;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.api.agent.AgentEnrollmentRequest;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.cp.api.audit.ClientAddresses;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.EnrollmentEnvelope;
import com.solace.sm.dp.api.agw.EnrollmentIssuance;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;

/**
 * Adapter 1 of 3 across the cp-api / dp-api seam (BE-D69): the data plane's {@link EnrollmentCertificateIssuer}, answered
 * by the control plane's enrolment. The gateway-minted correlation id and peer address travel into the control plane's
 * audit context (plan 015 P15-8): the agent's own values are never read here, and the gateway's {@code requestId} is a
 * label for its own audit - it keys nothing in the control plane, whose replay key is the stored token and CSR digests.
 */
final class ControlPlaneEnrollmentCertificateIssuer implements EnrollmentCertificateIssuer {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ControlPlaneEnrollmentCertificateIssuer.class);
    private final ControlPlaneAgentGateway controlPlane;

    ControlPlaneEnrollmentCertificateIssuer(ControlPlaneAgentGateway controlPlane) {
        this.controlPlane = controlPlane;
    }

    @Override
    public EnrollmentIssuance issue(EnrollmentRequest request) {
        RequestOrigin origin = origin(request);
        try {
            AgentEnrollmentMaterial material = controlPlane.enroll(new AgentEnrollmentRequest(request.token(),
                    request.csrDer(), request.agentVersion(), request.protocolVersions(), request.hostOs(),
                    request.hostArch(), request.runtimeKind(), origin));
            // The tenant is the organization the token was issued in (I5): taken from the control plane's row, never the request.
            return new EnrollmentIssuance(material.organizationId(), material.catalogSolutionId(), material.solution(),
                    material.packageVersion(), material.environmentId(), material.targetId(), material.agentId(),
                    material.certificateChainDer(), material.trustBundlePem(), material.validFrom(), material.validTo(),
                    material.connectEndpoint(),
                    new EnrollmentEnvelope(material.allowedConfigRoot(), material.allowedContainerPrefix()),
                    material.issuingPrincipal(), AgentCapabilities.all(material.registration().capabilities()),
                    material.registration().agentRevocationEpoch(), material.registration().fleetRevocationEpoch());
        } catch (AgentEnrollmentRefusedException e) {
            // A fixed sentence per reason; the token and the CSR appear in no message and no log line.
            throw switch (e.reason()) {
                case UNKNOWN_TOKEN -> new GatewayPortException(GatewayCode.UNAUTHENTICATED_IDENTITY, "control-plane enrollment refused");
                case EXPIRED, CONSUMED -> new GatewayPortException(GatewayCode.TOKEN_CONSUMED, "control-plane enrollment refused");
                case TARGET_NAME_TAKEN -> new GatewayPortException(GatewayCode.ISSUE_NOT_ALLOWED, "control-plane enrollment refused");
                // Only the CP's own CSR refusal is the caller's fault. A bare IllegalArgumentException from anywhere else is a
                // bug or an outage and falls to INTERNAL_UNAVAILABLE below, never to "unauthenticated".
                case CSR_REFUSED -> new GatewayPortException(GatewayCode.UNAUTHENTICATED_IDENTITY, "control-plane enrollment refused");
            };
        } catch (GatewayPortException e) {
            throw e;
        } catch (RuntimeException e) {
            // The class name only at ERROR (never the message: it can echo SQL values); detail at DEBUG.
            LOG.error("control-plane enrollment unavailable: {} {}", e.getClass().getName(), FailureDiagnostics.of(e));
            LOG.debug("control-plane enrollment failure detail", e);
            throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "control-plane enrollment unavailable");
        }
    }

    private static RequestOrigin origin(EnrollmentRequest request) {
        String literal = peerLiteral(request.origin().clientAddress());
        return new RequestOrigin(ClientAddresses.canonicalize(literal).orElseThrow(() -> {
            // OR-25 requires a client address on every agent write; one that is not an IP is never invented or defaulted.
            LOG.error("enrollment peer address is not an IP literal");
            return new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "control-plane enrollment unavailable");
        }), request.origin().correlationId());
    }

    /**
     * The IP literal inside the gateway's bare host address (T2.1a: {@code AgentRequestOrigin} admits only
     * {@code [A-Za-z0-9:._%-]{1,64}}, so a slash or bracket never arrives): an {@code ip:port} loses its port, an IPv6
     * literal (several colons) is left whole.
     */
    static String peerLiteral(String peer) {
        String candidate = peer.strip();
        int first = candidate.indexOf(':');
        if (first >= 0 && first == candidate.lastIndexOf(':')) return candidate.substring(0, first); // ip:port
        return candidate; // a bare IPv4, or an IPv6 literal
    }
}
