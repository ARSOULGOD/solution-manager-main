package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentEnrollmentMaterial;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException.Reason;
import com.solace.sm.cp.api.agent.AgentEnrollmentRequest;
import com.solace.sm.cp.api.agent.AgentLeaseRefusedException;
import com.solace.sm.cp.api.agent.AgentLeaseMaterial;
import com.solace.sm.cp.api.agent.AgentLeaseRequest;
import com.solace.sm.cp.api.agent.AgentRegistration;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.target.TargetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * CP authority for enrolment and lease claims (plan 015 T2.2). DP remains responsible for durable projections.
 *
 * <p><b>Enrolment is one control-plane transaction</b> (BE-D71): the registration table's {@code agent_registration_consumed}
 * CHECK makes the used-at, CSR digest, issued certificate and target id arrive together, so the CP target row is written
 * first, in the same transaction, and the consumption last. Every write in it runs under {@code AGENT_ENROLLED} with the
 * agent as actor and the gateway-minted correlation id and peer address (BE-D70, OR-25): the database refuses it
 * otherwise. The CA signs BEFORE the first write, so a missing or broken keystore writes nothing.
 *
 * <p><b>A lease writes no row</b> (Step 0 (b)): it reads the registration and signs claims. The placement set in those
 * claims is the one the data plane passed, copied and never extended (AGW-D24).
 *
 * <p><b>The renewal nonce is keyed and deterministic</b> ({@link LeaseNonceSigner}): identical inputs return an identical grant
 * (a lost reply is replayed safely), and without the control plane's secret the chain cannot be recomputed from request ids.
 * <b>The control plane never checks {@code consumedNonce}</b>: it is only an input to the MAC. The single-use check is the
 * data plane's constant-time digest compare, and it is the only one.
 */
public final class ControlPlaneAgentGatewayService implements ControlPlaneAgentGateway {

    /**
     * Per-verb execution ceilings granted in a lease, keyed by the data plane's {@code AgentOperation} name. The DP admits a
     * command only while {@code deadline - now >= ceiling + admissionMargin}, and the apply path stamps commands with the
     * engine's 5-minute step budget, so each ceiling must leave room for the margin inside that budget. A MISSING key is a
     * zero ceiling, so REMATERIALIZE (T2.3) must be here or every such step fails (R-7).
     */
    static final Map<String, Duration> VERB_CEILINGS = Map.of(
            "PREFLIGHT", Duration.ofSeconds(30), "APPLY", Duration.ofSeconds(60), "RESTART", Duration.ofSeconds(120),
            "STOP", Duration.ofSeconds(60), "REMATERIALIZE", Duration.ofSeconds(60));

    /** The agent target's fixed pair (OR-111: v1 agent targets are Docker only). */
    static final String TARGET_KIND = "docker";
    static final String TARGET_TRANSPORT = "agent";

    private final AgentRegistry registry;
    private final AgentCertificateAuthority certificateAuthority;
    private final LeaseNonceSigner nonceSigner;
    private final TargetRepository targets;
    private final AgentAuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final String connectEndpoint;

    public ControlPlaneAgentGatewayService(AgentRegistry registry, AgentCertificateAuthority certificateAuthority,
            LeaseNonceSigner nonceSigner, TargetRepository targets, AgentAuditContext auditContext, TransactionOperations transactions,
            Clock clock, String connectEndpoint) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.certificateAuthority = Objects.requireNonNull(certificateAuthority, "certificateAuthority");
        this.nonceSigner = Objects.requireNonNull(nonceSigner, "nonceSigner");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.connectEndpoint = Objects.requireNonNull(connectEndpoint, "connectEndpoint");
    }

    @Override
    public AgentEnrollmentMaterial enroll(AgentEnrollmentRequest request) {
        if (connectEndpoint.isBlank()) throw new IllegalStateException("agent gateway endpoint is not configured");
        byte[] tokenDigest = digest(request.token());
        byte[] csrDigest = digest(request.csrDer());
        try {
            return transactions.execute(status -> enrollOnce(request, tokenDigest, csrDigest));
        } catch (DuplicateKeyException lost) {
            // The live-name index (V018 target_name_once) is the structural backstop of the pre-check below.
            throw new AgentEnrollmentRefusedException(Reason.TARGET_NAME_TAKEN);
        }
    }

    private AgentEnrollmentMaterial enrollOnce(AgentEnrollmentRequest request, byte[] tokenDigest, byte[] csrDigest) {
        AgentRegistry.Enrollment found = registry.lockByTokenDigest(tokenDigest)
                .orElseThrow(() -> new AgentEnrollmentRefusedException(Reason.UNKNOWN_TOKEN));
        Instant now = clock.instant();
        if (found.consumed()) {
            // The same token with the same CSR is a replay of a lost reply, and answers the same certificate and target
            // (V015's behaviour). It writes nothing, so it needs no audit context, and the TOKEN's window no longer matters;
            // the CERTIFICATE's does: once the issued leaf has expired a replay would hand back a dead credential.
            if (!MessageDigest.isEqual(found.csrDigest(), csrDigest) || found.certificate() == null
                    || found.registration().targetId() == null) {
                throw new AgentEnrollmentRefusedException(Reason.CONSUMED);
            }
            if (!found.certificate().validTo().isAfter(now)) throw new AgentEnrollmentRefusedException(Reason.EXPIRED);
            // plan 015 T3.5: a replay is honoured until the certificate ends, but never into a target the control plane has retired
            if (targets.findById(found.organizationId(), found.registration().targetId()).map(t -> t.retired()).orElse(false)) {
                throw new AgentEnrollmentRefusedException(Reason.CONSUMED);
            }
            return material(found, found.registration(), found.certificate());
        }
        if (!found.expiresAt().isAfter(now)) throw new AgentEnrollmentRefusedException(Reason.EXPIRED);
        UUID agentId = found.registration().agentId();

        // The certificate's life is the CA's maximum leaf lifetime, NOT the lease TTL (design section 7: "authority bounded by
        // the lease, not the cert TTL"); ZERO means "the CA's maximum"; rotation of the leaf is manual in v1. Signed before
        // the first write: an unusable CA leaves the registration unconsumed and writes nothing.
        AgentCertificateAuthority.IssuedCertificate issued =
                certificateAuthority.issue(agentId, request.csrDer(), now, Duration.ZERO);

        auditContext.setAgent(agentId, AuditOperations.AGENT_ENROLLED, request.origin().correlationId(),
                request.origin().clientAddress());
        targets.lockName(found.organizationId(), found.targetName());      // shared with registerTarget and token issue
        if (targets.findByName(found.organizationId(), found.targetName()).filter(t -> !t.retired()).isPresent()) {
            throw new AgentEnrollmentRefusedException(Reason.TARGET_NAME_TAKEN);
        }
        UUID targetId = UUID.randomUUID();
        // created_by is the principal who ISSUED the token (OR-128 (5)); the agent is the actor beside it.
        targets.insert(found.organizationId(), targetId, found.targetName(), TARGET_KIND, TARGET_TRANSPORT,
                new ScopePath(found.organizationId(), found.organizationUnitId(), found.environmentId(), null, null),
                found.createdBy(), now);
        registry.consume(agentId, csrDigest, targetId, issued, now);
        AgentRegistration consumed = withTarget(found.registration(), targetId);
        return material(found, consumed, issued);
    }

    @Override
    public AgentLeaseMaterial issueLease(AgentLeaseRequest request) {
        AgentRegistry.Registered registered = registry.find(request.organizationId(), request.agentId())
                .orElseThrow(() -> new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.NOT_ENROLLED));
        AgentRegistration registration = registered.registration();
        // Order is part of the contract: a revocation is named as one even when its epochs also moved.
        if (!registration.active()) throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.REVOKED);
        if (registration.targetId() == null || registered.leafDer() == null || registered.validTo() == null) {
            throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.NOT_ENROLLED);
        }
        if (request.agentRevocationEpoch() != registration.agentRevocationEpoch()) {
            throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.AGENT_EPOCH_STALE);
        }
        if (request.fleetRevocationEpoch() != registration.fleetRevocationEpoch()) {
            throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.FLEET_EPOCH_STALE);
        }
        if (!registration.targetId().equals(request.targetId())) {
            throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.TARGET_MISMATCH);
        }
        // The lease is bound to the certificate the CP issued: the presented leaf's SHA-256 (lowercase hex, the form the
        // gateway reads from the TLS peer) must be the stored leaf's, and its authority ends when that certificate does.
        String issuedThumbprint = HexFormat.of().formatHex(digest(registered.leafDer()));
        if (!MessageDigest.isEqual(issuedThumbprint.getBytes(StandardCharsets.US_ASCII),
                request.certificateThumbprint().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.CERTIFICATE_MISMATCH);
        }
        Instant issuedAt = clock.instant();
        if (!registered.validTo().isAfter(issuedAt)) throw new AgentLeaseRefusedException(AgentLeaseRefusedException.Reason.CERTIFICATE_EXPIRED);
        Instant expiresAt = issuedAt.plus(registration.leaseTtl());
        if (registered.validTo().isBefore(expiresAt)) expiresAt = registered.validTo();
        return new AgentLeaseMaterial(registration.agentId(), issuedThumbprint,
                registration.targetId(), request.placementIds(), registration.agentRevocationEpoch(),
                registration.fleetRevocationEpoch(), issuedAt, expiresAt,
                registration.audience(), nonceSigner.nonce(registration.agentId(), request.requestId(), issuedThumbprint, request.consumedNonce()), registration.capabilities(),
                VERB_CEILINGS, registration.admissionMargin(), issuedAt);
    }

    @Override
    public Map<UUID, AgentRegistration> registrations(Collection<UUID> agentIds) {
        return agentIds.isEmpty() ? Map.of() : registry.findAll(agentIds);
    }

    private static AgentRegistration withTarget(AgentRegistration r, UUID targetId) {
        return new AgentRegistration(r.agentId(), targetId, r.capabilities(), r.agentRevocationEpoch(),
                r.fleetRevocationEpoch(), r.leaseTtl(), r.audience(), r.admissionMargin(), r.active());
    }

    private AgentEnrollmentMaterial material(AgentRegistry.Enrollment found, AgentRegistration registration,
            AgentCertificateAuthority.IssuedCertificate issued) {
        return new AgentEnrollmentMaterial(registration.agentId(), issued.chainDer(), issued.trustBundlePem(),
                issued.validFrom(), issued.validTo(), connectEndpoint, registration, found.organizationId(),
                found.environmentId(), registration.targetId(), found.catalogSolutionId(), found.solution(),
                found.packageVersion(), found.allowedConfigRoot(), found.allowedContainerPrefix(), found.createdBy());
    }

    private static byte[] digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

}
