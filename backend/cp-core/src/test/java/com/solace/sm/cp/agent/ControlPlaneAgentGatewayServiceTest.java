package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.agent.AgentEnrollmentMaterial;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException.Reason;
import com.solace.sm.cp.api.agent.AgentEnrollmentRequest;
import com.solace.sm.cp.api.agent.AgentLeaseMaterial;
import com.solace.sm.cp.api.agent.AgentLeaseRefusedException;
import com.solace.sm.cp.api.agent.AgentLeaseRequest;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.config.AgentGatewayCaProperties;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.target.TargetRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 015 T2.2, the control plane's enrolment and lease authority, offline: the registry, the target repository and
 * the audit context are recorders that share ONE ordered event list, so the order of the agent-actor context and every
 * write is asserted. The real schema, triggers and role grants are {@code AgentEnrollmentIT}.
 */
class ControlPlaneAgentGatewayServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String TOKEN = "t".repeat(43);
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID ISSUER = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final RequestOrigin ORIGIN = new RequestOrigin("198.51.100.23", CORRELATION);

    @TempDir
    Path directory;

    private final List<String> events = new ArrayList<>();
    private final List<UUID> auditedAgents = new ArrayList<>();
    private final List<String[]> auditContexts = new ArrayList<>();
    private final List<TargetView> insertedTargets = new ArrayList<>();
    private final List<TargetRepository.Stored> existingTargets = new ArrayList<>();
    private FakeAgentRegistry registry;
    private UUID agentId;
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        registry = new FakeAgentRegistry(events);
        agentId = UUID.randomUUID();
        registry.pending(agentId, ORG, UNIT, ENV, "edge-host-1", sha256(TOKEN), NOW.plusSeconds(600), ISSUER);
    }

    private ControlPlaneAgentGatewayService service(AgentCertificateAuthority ca) {
        TargetRepository targets = new TargetRepository() {
            @Override public List<TargetView> listLive(UUID organizationId) { return List.of(); }
            @Override public Optional<Stored> findByName(UUID organizationId, String name) {
                return existingTargets.stream().filter(s -> s.view().name().equals(name)).findFirst();
            }
            @Override public Optional<Stored> findById(UUID organizationId, UUID targetId) { return Optional.empty(); }
            @Override public void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
                    ScopePath ownershipScope, UUID createdBy, Instant createdAt) {
                events.add("target.insert");
                insertedTargets.add(new TargetView(targetId, name, kind, transport, ownershipScope));
                assertEquals(ISSUER, createdBy, "created_by is the principal who issued the token");
            }
            @Override public int markRetired(UUID organizationId, UUID targetId, Instant at) { return 0; }
        };
        AgentAuditContext audit = (agent, operation, correlation, address) -> {
            events.add("audit.setAgent");
            auditedAgents.add(agent);
            auditContexts.add(new String[] {operation, String.valueOf(correlation), address});
        };
        return new ControlPlaneAgentGatewayService(registry, ca, nonceSigner(), targets, audit,
                TransactionOperations.withoutTransaction(), clock, "agents.example.test:8443");
    }

    private AgentGatewayCaProperties keystore;

    /** One keystore per test, so the CA and the nonce key a test's services share are the same. */
    private AgentGatewayCaProperties keystore() throws Exception {
        if (keystore == null) keystore = AgentTestPki.properties(directory);
        return keystore;
    }

    private LeaseNonceSigner nonceSigner() {
        try {
            return new Pkcs12LeaseNonceSigner(keystore());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentCertificateAuthority realCa() throws Exception {
        return new Pkcs12AgentCertificateAuthority(keystore());
    }

    private AgentEnrollmentRequest enrollment(byte[] csr) {
        return new AgentEnrollmentRequest(TOKEN, csr, "0.1.0", List.of(1), "linux", "arm64", "docker", ORIGIN);
    }

    // ------------------------------------------------------------------ enrolment

    @Test
    void enrolmentRunsEveryWriteUnderTheAgentActorWithTheGatewayMintedOriginAndWritesTheTargetBeforeTheConsumption()
            throws Exception {
        AgentEnrollmentMaterial material = service(realCa()).enroll(enrollment(AgentTestPki.csr()));

        // The audit context is set BEFORE the first write; the target row precedes the consumption (the registration's
        // agent_registration_target_fk and its consumed CHECK force that order).
        assertEquals(List.of("audit.setAgent", "target.insert", "registry.consume"), events);
        assertEquals(List.of(agentId), auditedAgents);
        assertArrayEquals(new String[] {AuditOperations.AGENT_ENROLLED, CORRELATION.toString(), "198.51.100.23"},
                auditContexts.get(0));
        assertEquals(1, insertedTargets.size());
        TargetView target = insertedTargets.get(0);
        assertEquals("edge-host-1", target.name());
        assertEquals("docker", target.kind());
        assertEquals("agent", target.transport());
        assertEquals(new ScopePath(ORG, UNIT, ENV, null, null), target.ownershipScope());

        assertEquals(agentId, material.agentId());
        assertEquals(target.targetId(), material.targetId());
        assertEquals(target.targetId(), material.registration().targetId());
        assertEquals(ISSUER, material.issuingPrincipal());
        assertEquals(ORG, material.organizationId());
        assertEquals(ENV, material.environmentId());
        assertEquals("/opt/sm/config", material.allowedConfigRoot());
        assertEquals("sm-", material.allowedContainerPrefix());
        assertEquals(2, material.certificateChainDer().size());
        assertEquals("CN=" + agentId, AgentTestPki.parse(material.certificateChainDer().get(0)).getSubjectX500Principal().getName());
    }

    @Test
    void anExpiredTokenIsRefusedAndNothingIsSignedOrWritten() throws Exception {
        clock = Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC);

        AgentEnrollmentRefusedException refused = assertThrows(AgentEnrollmentRefusedException.class,
                () -> service(realCa()).enroll(enrollment(AgentTestPki.csr())));

        assertEquals(Reason.EXPIRED, refused.reason());
        assertEquals(List.of(), events);
        assertFalse(registry.rows.get(agentId).consumed());
    }

    @Test
    void aTokenAtItsExactExpiryIsRefused() throws Exception {
        clock = Clock.fixed(NOW.plusSeconds(600), ZoneOffset.UTC);
        assertEquals(Reason.EXPIRED, assertThrows(AgentEnrollmentRefusedException.class,
                () -> service(realCa()).enroll(enrollment(AgentTestPki.csr()))).reason());
    }

    @Test
    void anUnknownOrRevokedTokenIsRefusedAsUnknown() throws Exception {
        registry.active = false;
        assertEquals(Reason.UNKNOWN_TOKEN, assertThrows(AgentEnrollmentRefusedException.class,
                () -> service(realCa()).enroll(enrollment(AgentTestPki.csr()))).reason());
        assertEquals(List.of(), events);
    }

    @Test
    void theSameTokenWithTheSameCsrReplaysTheSameMaterialAndWritesNothing() throws Exception {
        ControlPlaneAgentGatewayService service = service(realCa());
        byte[] csr = AgentTestPki.csr();
        AgentEnrollmentMaterial first = service.enroll(enrollment(csr));
        events.clear();
        // The token's window is irrelevant to a replay of a consumption that happened inside it.
        clock = Clock.fixed(NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC);

        AgentEnrollmentMaterial again = service(realCa()).enroll(enrollment(csr));

        assertEquals(List.of(), events, "a replay writes nothing and sets no audit context");
        assertEquals(first.targetId(), again.targetId());
        assertArrayEquals(first.certificateChainDer().get(0), again.certificateChainDer().get(0));
    }

    @Test
    void theSameTokenWithADifferentCsrIsRefusedAsConsumed() throws Exception {
        ControlPlaneAgentGatewayService service = service(realCa());
        service.enroll(enrollment(AgentTestPki.csr()));
        events.clear();

        AgentEnrollmentRefusedException refused = assertThrows(AgentEnrollmentRefusedException.class,
                () -> service.enroll(enrollment(AgentTestPki.csr())));

        assertEquals(Reason.CONSUMED, refused.reason());
        assertEquals(List.of(), events);
    }

    @Test
    void withoutAnOperatorKeystoreNothingIsSignedAndNothingIsWritten() throws Exception {
        AgentGatewayCaProperties unconfigured = new AgentGatewayCaProperties();
        unconfigured.setConnectEndpoint("agents.example.test:8443");
        byte[] csr = AgentTestPki.csr();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service(new Pkcs12AgentCertificateAuthority(unconfigured)).enroll(enrollment(csr)));

        assertEquals("agent CA keystore is not configured", failure.getMessage());
        assertEquals(List.of(), events, "the CA signs before the first write, so a missing keystore writes nothing");
        assertFalse(registry.rows.get(agentId).consumed());
    }

    @Test
    void aTargetNameAlreadyInUseRefusesTheEnrolmentBeforeAnyTargetIsWritten() throws Exception {
        existingTargets.add(new TargetRepository.Stored(new TargetView(UUID.randomUUID(), "edge-host-1", "docker",
                "ssh-docker", ScopePath.organization(ORG)), null));

        AgentEnrollmentRefusedException refused = assertThrows(AgentEnrollmentRefusedException.class,
                () -> service(realCa()).enroll(enrollment(AgentTestPki.csr())));

        assertEquals(Reason.TARGET_NAME_TAKEN, refused.reason());
        assertEquals(List.of("audit.setAgent"), events);
        assertFalse(registry.rows.get(agentId).consumed());
    }

    @Test
    void aRetiredTargetOfTheSameNameDoesNotBlockEnrolment() throws Exception {
        existingTargets.add(new TargetRepository.Stored(new TargetView(UUID.randomUUID(), "edge-host-1", "docker",
                "ssh-docker", ScopePath.organization(ORG)), NOW.minusSeconds(60)));

        assertNotNull(service(realCa()).enroll(enrollment(AgentTestPki.csr())).targetId());
    }

    @Test
    void aMalformedOrBadlySignedCsrIsTheCallersFaultAndWritesNothing() throws Exception {
        ControlPlaneAgentGatewayService service = service(realCa());
        for (byte[] bad : List.of("not a csr".getBytes(StandardCharsets.UTF_8), AgentTestPki.csrWithBadSignature())) {
            AgentEnrollmentRefusedException failure = assertThrows(AgentEnrollmentRefusedException.class,
                    () -> service.enroll(enrollment(bad)));
            assertEquals(Reason.CSR_REFUSED, failure.reason());
            assertFalse(failure.getMessage().contains(TOKEN));
        }
        assertEquals(List.of(), events);
        assertFalse(registry.rows.get(agentId).consumed());
    }

    @Test
    void anUnconfiguredConnectEndpointFailsClosed() throws Exception {
        ControlPlaneAgentGatewayService noEndpoint = new ControlPlaneAgentGatewayService(registry, realCa(), nonceSigner(),
                new TargetRepository() {
                    @Override public List<TargetView> listLive(UUID o) { return List.of(); }
                    @Override public Optional<Stored> findByName(UUID o, String n) { return Optional.empty(); }
                    @Override public Optional<Stored> findById(UUID o, UUID t) { return Optional.empty(); }
                    @Override public void insert(UUID o, UUID t, String n, String k, String tr, ScopePath s, UUID c, Instant a) { }
                    @Override public int markRetired(UUID o, UUID t, Instant a) { return 0; }
                }, (a, op, c, ad) -> { }, TransactionOperations.withoutTransaction(), clock, "");
        assertThrows(IllegalStateException.class, () -> noEndpoint.enroll(enrollment(AgentTestPki.csr())));
    }

    // ------------------------------------------------------------------ leases

    private String thumbprint() {
        return java.util.HexFormat.of().formatHex(sha256(registry.rows.get(agentId).certificate().chainDer().get(0)));
    }

    private AgentLeaseMaterial lease(ControlPlaneAgentGatewayService service, UUID target, long epoch, List<UUID> placements,
            String requestId) {
        return service.issueLease(new AgentLeaseRequest(ORG, agentId, target, thumbprint(), epoch, 1, NOW, requestId,
                "prior-nonce", placements));
    }

    private ControlPlaneAgentGatewayService enrolled() throws Exception {
        ControlPlaneAgentGatewayService service = service(realCa());
        service.enroll(enrollment(AgentTestPki.csr()));
        events.clear();
        return service;
    }

    private UUID target() {
        return registry.rows.get(agentId).registration().targetId();
    }

    private static AgentLeaseRefusedException.Reason refusal(Runnable work) {
        return assertThrows(AgentLeaseRefusedException.class, work::run).reason();
    }

    @Test
    void aLeaseNamesExactlyThePlacementSetTheDataPlanePassedAndNeverOneTheControlPlaneInvented() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();
        List<UUID> passed = List.of(UUID.randomUUID(), UUID.randomUUID());

        AgentLeaseMaterial material = lease(service, target(), 1, passed, "r-1");

        assertEquals(passed, material.placementIds());
        assertEquals(List.of(), lease(service, target(), 1, List.of(), "r-2").placementIds(),
                "an empty set stays empty: the control plane holds no placement to fill it with");
        assertEquals(agentId, material.agentId());
        assertEquals(target(), material.targetId());
        assertEquals(thumbprint(), material.certificateThumbprint());
        assertEquals(List.of("apply-config", "lifecycle"), material.capabilities());
        assertEquals(NOW, material.issuedAt());
        assertEquals(NOW.plusSeconds(300), material.expiresAt());
        assertEquals(List.of(), events, "a lease writes nothing and sets no audit context");
    }

    @Test
    void aLeaseCarriesAVerbCeilingForEveryVerbIncludingRematerialize() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();

        Map<String, Duration> ceilings = lease(service, target(), 1, List.of(), "r-1").verbCeilings();

        assertEquals(Map.of("PREFLIGHT", Duration.ofSeconds(30), "APPLY", Duration.ofSeconds(60),
                "RESTART", Duration.ofSeconds(120), "STOP", Duration.ofSeconds(60),
                "REMATERIALIZE", Duration.ofSeconds(60)), ceilings);
    }

    @Test
    void theNonceIsDerivedFromTheRequestAndStableOnRetry() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();

        String first = lease(service, target(), 1, List.of(), "r-1").nonce();

        assertEquals(first, lease(service, target(), 1, List.of(UUID.randomUUID()), "r-1").nonce(),
                "a retried request id yields the same nonce, whatever the placements");
        assertNotEquals(first, lease(service, target(), 1, List.of(), "r-2").nonce());
        assertEquals(64, first.length());
    }

    @Test
    void theNonceIsKeyedNotDerivedFromPublicInputsAlone() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();
        String thumbprint = thumbprint();

        String nonce = lease(service, target(), 1, List.of(), "r-1").nonce();

        // The old derivation: SHA-256 over requestId|agentId|thumbprint|consumedNonce. Anyone holding the stored request ids
        // could recompute it; the keyed one must not equal it.
        String unkeyed = java.util.HexFormat.of().formatHex(sha256(("r-1|" + agentId + "|" + thumbprint + "|prior-nonce")
                .getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(unkeyed, nonce);
        assertEquals(nonce, lease(service, target(), 1, List.of(), "r-1").nonce(), "still deterministic: a lost reply replays");
    }

    @Test
    void withoutTheNonceKeyNoLeaseIsIssued() throws Exception {
        ControlPlaneAgentGatewayService enrolledService = enrolled();
        UUID target = target();
        keystore = AgentTestPki.propertiesWithoutNonceKey(directory);
        ControlPlaneAgentGatewayService noKey = new ControlPlaneAgentGatewayService(registry, realCa(), nonceSigner(),
                new TargetRepository() {
                    @Override public List<TargetView> listLive(UUID o) { return List.of(); }
                    @Override public Optional<Stored> findByName(UUID o, String n) { return Optional.empty(); }
                    @Override public Optional<Stored> findById(UUID o, UUID t) { return Optional.empty(); }
                    @Override public void insert(UUID o, UUID t, String n, String k, String tr, ScopePath s, UUID c, Instant a) { }
                    @Override public int markRetired(UUID o, UUID t, Instant a) { return 0; }
                }, (a, op, c, ad) -> { }, TransactionOperations.withoutTransaction(), clock, "agents.example.test:8443");

        // The registered certificate is the first keystore's, so use its thumbprint: the key check comes after the refusals.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> noKey.issueLease(
                new AgentLeaseRequest(ORG, agentId, target, thumbprint(), 1, 1, NOW, "r", null, List.of())));
        assertEquals("lease nonce key entry is missing or shorter than 32 bytes", failure.getMessage());
        assertNotNull(enrolledService);
    }

    @Test
    void everyLeaseRefusalIsTypedByReasonSoTheLauncherCanSpellEach() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();
        UUID target = target();

        assertEquals(AgentLeaseRefusedException.Reason.AGENT_EPOCH_STALE, refusal(() -> lease(service, target, 2, List.of(), "r")));
        assertEquals(AgentLeaseRefusedException.Reason.FLEET_EPOCH_STALE, refusal(() -> service.issueLease(
                new AgentLeaseRequest(ORG, agentId, target, thumbprint(), 1, 9, NOW, "r", null, List.of()))));
        assertEquals(AgentLeaseRefusedException.Reason.TARGET_MISMATCH,
                refusal(() -> lease(service, UUID.randomUUID(), 1, List.of(), "r")));
        assertEquals(AgentLeaseRefusedException.Reason.NOT_ENROLLED, refusal(() -> service.issueLease(
                new AgentLeaseRequest(ORG, UUID.randomUUID(), target, thumbprint(), 1, 1, NOW, "r", null, List.of()))));
        assertEquals(AgentLeaseRefusedException.Reason.NOT_ENROLLED, refusal(() -> service.issueLease(
                new AgentLeaseRequest(UUID.randomUUID(), agentId, target, thumbprint(), 1, 1, NOW, "r", null, List.of()))),
                "another organization's id is as unknown as an unknown agent (I5)");
        registry.revoked = true;
        assertEquals(AgentLeaseRefusedException.Reason.REVOKED, refusal(() -> lease(service, target, 2, List.of(), "r")),
                "a revocation is named as one even when the epochs also moved");
    }

    @Test
    void aLeaseForAnAgentWhoseTokenWasNeverConsumedIsNotEnrolled() throws Exception {
        // setUp's registration is pending: it has no target and no certificate yet.
        ControlPlaneAgentGatewayService unenrolled = service(realCa());
        assertEquals(AgentLeaseRefusedException.Reason.NOT_ENROLLED, refusal(() -> unenrolled.issueLease(
                new AgentLeaseRequest(ORG, agentId, UUID.randomUUID(), "ab", 1, 1, NOW, "r", null, List.of()))));
    }

    @Test
    void aLeaseIsBoundToTheIssuedCertificate() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();
        String other = java.util.HexFormat.of().formatHex(sha256("another certificate".getBytes(StandardCharsets.UTF_8)));

        assertEquals(AgentLeaseRefusedException.Reason.CERTIFICATE_MISMATCH, refusal(() -> service.issueLease(
                new AgentLeaseRequest(ORG, agentId, target(), other, 1, 1, NOW, "r", null, List.of()))));
        // The gateway reads the thumbprint as lowercase hex; an upper-case spelling of the SAME digest is the same certificate.
        assertNotNull(service.issueLease(new AgentLeaseRequest(ORG, agentId, target(), thumbprint().toUpperCase(), 1, 1,
                NOW, "r", null, List.of())));
    }

    @Test
    void aLeaseNeverOutlivesTheCertificateAndIsRefusedOnceItHasExpired() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();
        Instant validTo = registry.rows.get(agentId).certificate().validTo();

        clock = Clock.fixed(validTo.minusSeconds(100), ZoneOffset.UTC);
        AgentLeaseMaterial nearEnd = lease(service(realCa()), target(), 1, List.of(), "r-1");
        assertEquals(validTo, nearEnd.expiresAt(), "min(issuedAt + ttl, certificate valid_to)");

        clock = Clock.fixed(validTo, ZoneOffset.UTC);
        ControlPlaneAgentGatewayService atExpiry = service(realCa());
        assertEquals(AgentLeaseRefusedException.Reason.CERTIFICATE_EXPIRED,
                refusal(() -> lease(atExpiry, target(), 1, List.of(), "r-2")));
    }

    @Test
    void anExpiredCertificateIsNotReplayedAsAnEnrolment() throws Exception {
        ControlPlaneAgentGatewayService service = service(realCa());
        byte[] csr = AgentTestPki.csr();
        service.enroll(enrollment(csr));
        Instant validTo = registry.rows.get(agentId).certificate().validTo();
        events.clear();
        clock = Clock.fixed(validTo, ZoneOffset.UTC);

        assertEquals(Reason.EXPIRED, assertThrows(AgentEnrollmentRefusedException.class,
                () -> service(realCa()).enroll(enrollment(csr))).reason());
        clock = Clock.fixed(validTo.minusSeconds(1), ZoneOffset.UTC);
        assertNotNull(service(realCa()).enroll(enrollment(csr)));
        assertEquals(List.of(), events);
    }

    @Test
    void registrationsReportsKnownAgentsAndOmitsUnknownOnes() throws Exception {
        ControlPlaneAgentGatewayService service = enrolled();
        UUID stranger = UUID.randomUUID();

        assertEquals(java.util.Set.of(agentId), service.registrations(List.of(agentId, stranger)).keySet());
        assertTrue(service.registrations(List.of()).isEmpty());
    }

    @Test
    void enrolmentMaterialNamesTheSolutionAndTheDeclaredVersion() throws Exception {
        AgentEnrollmentMaterial material = service(realCa()).enroll(enrollment(AgentTestPki.csr()));
        assertEquals(FakeAgentRegistry.SOLUTION, material.catalogSolutionId());
        assertEquals("cache", material.solution());
        assertEquals("1", material.packageVersion());
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
