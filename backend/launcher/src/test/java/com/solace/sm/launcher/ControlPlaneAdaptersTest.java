package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.agent.AgentEnrollmentMaterial;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException.Reason;
import com.solace.sm.cp.api.agent.AgentEnrollmentRequest;
import com.solace.sm.cp.api.agent.AgentLeaseMaterial;
import com.solace.sm.cp.api.agent.AgentLeaseRefusedException;
import com.solace.sm.cp.api.agent.AgentLeaseRequest;
import com.solace.sm.cp.api.agent.AgentRegistration;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentIssuance;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.LeaseGrant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T2.2, the launcher's three adapters (BE-D69, HF21) over a recording control plane: what each passes across, what
 * it maps back, how each refusal is spelled for the gateway, and that no secret reaches a message.
 */
class ControlPlaneAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID ISSUER = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final String TOKEN = "super-secret-enrolment-token-0123456789abcdef";

    private static final AgentRequestOrigin ORIGIN = new AgentRequestOrigin(UUID.randomUUID(), "203.0.113.5");
    private final List<AgentEnrollmentRequest> enrollments = new ArrayList<>();
    private final List<AgentLeaseRequest> leases = new ArrayList<>();
    private Function<AgentEnrollmentRequest, AgentEnrollmentMaterial> onEnroll = request -> material();
    private Function<AgentLeaseRequest, AgentLeaseMaterial> onLease = request -> lease(request.placementIds());
    private Map<UUID, AgentRegistration> registrations = Map.of();
    private RuntimeException registrationFailure;

    private final ControlPlaneAgentGateway controlPlane = new ControlPlaneAgentGateway() {
        @Override public AgentEnrollmentMaterial enroll(AgentEnrollmentRequest request) {
            enrollments.add(request);
            return onEnroll.apply(request);
        }
        @Override public AgentLeaseMaterial issueLease(AgentLeaseRequest request) {
            leases.add(request);
            return onLease.apply(request);
        }
        @Override public Map<UUID, AgentRegistration> registrations(Collection<UUID> agentIds) {
            if (registrationFailure != null) throw registrationFailure;
            return registrations;
        }
    };

    private static AgentRegistration registration(boolean active) {
        return new AgentRegistration(AGENT, TARGET, List.of("apply-config", "lifecycle"), 3, 4, Duration.ofSeconds(300),
                "solution-manager-agent", Duration.ofSeconds(5), active);
    }

    private static AgentEnrollmentMaterial material() {
        return new AgentEnrollmentMaterial(AGENT, List.of(new byte[] {1, 2}, new byte[] {3}), List.of(new byte[] {9}),
                NOW, NOW.plusSeconds(3600), "agents.example.test:8443", registration(true), ORG, ENV, TARGET,
                SOLUTION, "cache", "1", "/opt/sm/config", "sm-", ISSUER);
    }

    private static AgentLeaseMaterial lease(List<UUID> placements) {
        return new AgentLeaseMaterial(AGENT, "thumb", TARGET, placements, 3, 4, NOW, NOW.plusSeconds(300), "aud", "nonce-1",
                List.of("apply-config"), Map.of("APPLY", Duration.ofSeconds(60), "REMATERIALIZE", Duration.ofSeconds(60)),
                Duration.ofSeconds(5), NOW);
    }

    private static EnrollmentRequest request(String peer) {
        return new EnrollmentRequest("enroll-1", TOKEN, new byte[] {7, 7}, "0.1.0", List.of(1), "linux", "arm64", "docker",
                new AgentRequestOrigin(UUID.fromString("00000000-0000-0000-0000-00000000c0de"), peer));
    }

    private GatewayPortException refusal(RuntimeException cause) {
        onEnroll = r -> { throw cause; };
        return assertThrows(GatewayPortException.class,
                () -> new ControlPlaneEnrollmentCertificateIssuer(controlPlane).issue(request("203.0.113.5:44123")));
    }

    // ------------------------------------------------------------------ enrolment

    @Test
    void enrolmentCarriesTheGatewayMintedCorrelationIdAndThePeerLiteralIntoTheControlPlane() {
        new ControlPlaneEnrollmentCertificateIssuer(controlPlane).issue(request("203.0.113.5:44123"));

        AgentEnrollmentRequest sent = enrollments.get(0);
        assertEquals(UUID.fromString("00000000-0000-0000-0000-00000000c0de"), sent.origin().correlationId());
        assertEquals("203.0.113.5", sent.origin().clientAddress());
        assertEquals(TOKEN, sent.token());
        assertEquals("0.1.0", sent.agentVersion());
    }

    @Test
    void thePeerLiteralIsExtractedFromTheGatewaysBareHostForms() {
        assertEquals("203.0.113.5", ControlPlaneEnrollmentCertificateIssuer.peerLiteral("203.0.113.5:44123"));
        assertEquals("203.0.113.5", ControlPlaneEnrollmentCertificateIssuer.peerLiteral("203.0.113.5"));
        assertEquals("2001:db8::7", ControlPlaneEnrollmentCertificateIssuer.peerLiteral("2001:db8::7"));
    }

    @Test
    void aPeerThatIsNotAnIpLiteralFailsClosedAndNeverReachesTheControlPlane() {
        for (String bad : List.of("unknown", "not-an-ip:99", "host.example")) {
            GatewayPortException failure = assertThrows(GatewayPortException.class,
                    () -> new ControlPlaneEnrollmentCertificateIssuer(controlPlane).issue(request(bad)));
            assertEquals(GatewayCode.INTERNAL_UNAVAILABLE, failure.code());
        }
        assertTrue(enrollments.isEmpty());
    }

    @Test
    void enrolmentMapsTheMaterialIncludingTheEnvelopeTheTargetAndTheIssuingPrincipal() {
        EnrollmentIssuance result = new ControlPlaneEnrollmentCertificateIssuer(controlPlane)
                .issue(request("203.0.113.5:44123"));

        assertEquals(AGENT, result.agentId());
        assertEquals(ORG, result.tenantId(), "the tenant is the organization the CP row names");
        assertEquals(SOLUTION, result.catalogSolutionId());
        assertEquals("cache", result.solution());
        assertEquals("1", result.declaredPackageVersion());
        assertEquals(ENV, result.environmentId());
        assertEquals(TARGET, result.targetId());
        assertEquals("/opt/sm/config", result.envelope().allowedConfigRoot());
        assertEquals("sm-", result.envelope().allowedContainerPrefix());
        assertEquals(ISSUER, result.issuingPrincipalId());
        assertEquals(List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), result.capabilities());
        assertEquals(3, result.agentRevocationEpoch());
        assertEquals(4, result.fleetRevocationEpoch());
        assertEquals(2, result.certificateChainDer().size());
        assertEquals("agents.example.test:8443", result.connectEndpoint());
    }

    @Test
    void everyRefusalIsSpelledForTheGatewayAndNamesNoSecret() {
        assertEquals(GatewayCode.UNAUTHENTICATED_IDENTITY,
                refusal(new AgentEnrollmentRefusedException(Reason.UNKNOWN_TOKEN)).code());
        assertEquals(GatewayCode.TOKEN_CONSUMED, refusal(new AgentEnrollmentRefusedException(Reason.EXPIRED)).code());
        assertEquals(GatewayCode.TOKEN_CONSUMED, refusal(new AgentEnrollmentRefusedException(Reason.CONSUMED)).code());
        assertEquals(GatewayCode.ISSUE_NOT_ALLOWED,
                refusal(new AgentEnrollmentRefusedException(Reason.TARGET_NAME_TAKEN)).code());
        assertEquals(GatewayCode.UNAUTHENTICATED_IDENTITY, refusal(new AgentEnrollmentRefusedException(Reason.CSR_REFUSED)).code());
        // Only the CP's own CSR refusal is the caller's fault: a stray IllegalArgumentException is a bug, not "unauthenticated".
        assertEquals(GatewayCode.INTERNAL_UNAVAILABLE, refusal(new IllegalArgumentException("boom")).code());
        GatewayPortException unavailable = refusal(new IllegalStateException("agent CA keystore is not configured " + TOKEN));
        assertEquals(GatewayCode.INTERNAL_UNAVAILABLE, unavailable.code());
        assertFalse(String.valueOf(unavailable.getMessage()).contains(TOKEN));
    }

    // ------------------------------------------------------------------ leases

    private static AgentIdentity identity() {
        return new AgentIdentity(AGENT, "thumb", ORG, ENV, TARGET, 3, 4, List.of(AgentCapability.APPLY_CONFIG), false);
    }

    @Test
    void theLeaseRequestCarriesExactlyThePlacementSetTheDataPlanePassed() {
        List<UUID> passed = List.of(UUID.randomUUID(), UUID.randomUUID());

        LeaseGrant grant = new ControlPlaneLeaseIssuer(controlPlane).issue(identity(), new AgentSessionRef(AGENT, 1), NOW,
                "req-1", "prior", passed, ORIGIN);

        AgentLeaseRequest sent = leases.get(0);
        assertEquals(passed, sent.placementIds());
        assertEquals(ORG, sent.organizationId(), "the DP-resolved tenant, never the agent's");
        assertEquals(AGENT, sent.agentId());
        assertEquals(TARGET, sent.targetId());
        assertEquals("thumb", sent.certificateThumbprint());
        assertEquals(3, sent.agentRevocationEpoch());
        assertEquals(4, sent.fleetRevocationEpoch());
        assertEquals("req-1", sent.requestId());
        assertEquals("prior", sent.consumedNonce());
        assertEquals(passed, grant.placementIds());
        assertEquals(Map.of(AgentOperation.APPLY, Duration.ofSeconds(60), AgentOperation.REMATERIALIZE, Duration.ofSeconds(60)),
                grant.verbCeilings());
        assertEquals(List.of(AgentCapability.APPLY_CONFIG), grant.capabilities());
        assertFalse(grant.toString().contains("nonce-1"));
    }

    @Test
    void anUnknownVerbOrCapabilityInAGrantIsRefusedNotDropped() {
        onLease = r -> new AgentLeaseMaterial(AGENT, "thumb", TARGET, List.of(), 3, 4, NOW, NOW.plusSeconds(300), "aud",
                "n", List.of("apply-config"), Map.of("TELEPORT", Duration.ofSeconds(1)), Duration.ZERO, NOW);
        assertEquals(GatewayCode.ISSUE_NOT_ALLOWED, assertThrows(GatewayPortException.class,
                () -> new ControlPlaneLeaseIssuer(controlPlane).issue(identity(), new AgentSessionRef(AGENT, 1), NOW, "r", null,
                        List.of(), ORIGIN)).code());
        onLease = r -> new AgentLeaseMaterial(AGENT, "thumb", TARGET, List.of(), 3, 4, NOW, NOW.plusSeconds(300), "aud",
                "n", List.of("levitate"), Map.of(), Duration.ZERO, NOW);
        assertEquals(GatewayCode.CAPABILITY_MISMATCH, assertThrows(GatewayPortException.class,
                () -> new ControlPlaneLeaseIssuer(controlPlane).issue(identity(), new AgentSessionRef(AGENT, 1), NOW, "r", null,
                        List.of(), ORIGIN)).code());
    }

    @Test
    void eachLeaseRefusalReasonIsSpelledForTheGatewayAsItsStreamHandlingNeeds() {
        // IDENTITY_REVOKED closes the streams (revokeAndClose); it is for a revocation and a bumped agent epoch only.
        Map<AgentLeaseRefusedException.Reason, GatewayCode> expected = Map.of(
                AgentLeaseRefusedException.Reason.REVOKED, GatewayCode.IDENTITY_REVOKED,
                AgentLeaseRefusedException.Reason.AGENT_EPOCH_STALE, GatewayCode.IDENTITY_REVOKED,
                AgentLeaseRefusedException.Reason.TARGET_MISMATCH, GatewayCode.TARGET_MISMATCH,
                AgentLeaseRefusedException.Reason.FLEET_EPOCH_STALE, GatewayCode.ISSUE_NOT_ALLOWED,
                AgentLeaseRefusedException.Reason.NOT_ENROLLED, GatewayCode.ISSUE_NOT_ALLOWED,
                AgentLeaseRefusedException.Reason.CERTIFICATE_MISMATCH, GatewayCode.ISSUE_NOT_ALLOWED,
                AgentLeaseRefusedException.Reason.CERTIFICATE_EXPIRED, GatewayCode.ISSUE_NOT_ALLOWED);
        assertEquals(AgentLeaseRefusedException.Reason.values().length, expected.size(), "every reason has a mapping");
        expected.forEach((reason, code) -> {
            onLease = r -> { throw new AgentLeaseRefusedException(reason); };
            assertEquals(code, assertThrows(GatewayPortException.class,
                    () -> new ControlPlaneLeaseIssuer(controlPlane).issue(identity(), new AgentSessionRef(AGENT, 1), NOW, "r",
                            null, List.of(), ORIGIN)).code(), reason.name());
        });
    }

    @Test
    void aMalformedLeaseRequestIsNotAllowedNeverARevocationAndAnOutageIsUnavailable() {
        onLease = r -> { throw new IllegalArgumentException("malformed"); };
        assertEquals(GatewayCode.ISSUE_NOT_ALLOWED, assertThrows(GatewayPortException.class,
                () -> new ControlPlaneLeaseIssuer(controlPlane).issue(identity(), new AgentSessionRef(AGENT, 1), NOW, "r", null,
                        List.of(), ORIGIN)).code());
        onLease = r -> { throw new IllegalStateException("db down"); };
        assertEquals(GatewayCode.INTERNAL_UNAVAILABLE, assertThrows(GatewayPortException.class,
                () -> new ControlPlaneLeaseIssuer(controlPlane).issue(identity(), new AgentSessionRef(AGENT, 1), NOW, "r", null,
                        List.of(), ORIGIN)).code());
    }

    // ------------------------------------------------------------------ registrations

    @Test
    void theRegistrationSourceReportsRevocationEpochsAndCapabilitiesAndNoPlacementSet() {
        registrations = Map.of(AGENT, registration(false));

        var state = new ControlPlaneRegistrationSource(controlPlane).current(List.of(AGENT)).get(AGENT);

        assertFalse(state.active());
        assertEquals(TARGET, state.targetId());
        assertEquals(List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), state.capabilities());
        assertEquals(3, state.agentRevocationEpoch());
        assertEquals(4, state.fleetRevocationEpoch());
    }

    @Test
    void aFailingRegistrationReadIsUnavailable() {
        registrationFailure = new IllegalStateException("db down");
        assertEquals(GatewayCode.INTERNAL_UNAVAILABLE, assertThrows(GatewayPortException.class,
                () -> new ControlPlaneRegistrationSource(controlPlane).current(List.of(AGENT))).code());
    }
}
