package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.CredentialRotation;
import com.solace.sm.cp.api.identity.CredentialRotationRefusedException;
import com.solace.sm.cp.api.session.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Atomically replaces a forced-rotation credential and revokes every other session, guarded by the
 * same durable throttle mechanism as local login ({@link LoginThrottleRepository} /
 * {@link LoginRateLimitPolicy}) — keyed by its OWN subject-hash space
 * ({@link #rotationSubjectHash}), domain-separated from login's
 * {@link LocalLoginService#loginSubjectHash} ({@code sha256("local-login:" + username)}) by a
 * distinct literal prefix on each side ({@code "credential-rotation:"} here, {@code
 * "local-login:"} there), so a rotation lockout and a login lockout for the same principal never
 * collide either way — not even for the adversarial username literally equal to
 * {@code "credential-rotation:<principal-id>"}.
 */
public final class CredentialRotationService implements CredentialRotation {

    /** Every rotation subject hash is over this literal plus the principal id — never a username. */
    private static final String SUBJECT_PREFIX = "credential-rotation:";

    private final PrincipalRepository principals;
    private final SessionRepository sessions;
    private final CredentialVerifier hasher;
    private final LoginThrottleRepository throttles;
    private final LoginRateLimitPolicy policy;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public CredentialRotationService(PrincipalRepository principals, SessionRepository sessions, CredentialVerifier hasher,
                                     LoginThrottleRepository throttles, LoginRateLimitPolicy policy,
                                     AuditContext auditContext, TransactionOperations transactions, Clock clock) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.throttles = Objects.requireNonNull(throttles, "throttles");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditContext = Objects.requireNonNull(auditContext, "audit context");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void rotate(Session currentSession, char[] currentCredential, char[] replacementCredential,
                       RequestOrigin origin) {
        Objects.requireNonNull(currentSession, "current session");
        Objects.requireNonNull(origin, "origin");
        if (!sessions.lockActiveFor(currentSession.id(), currentSession.principal(), clock.instant())) {
            throw new CredentialRotationRefusedException();
        }
        PrincipalRepository.Principal principal = principals.find(currentSession.principal().id())
                .filter(candidate -> candidate.source() == PrincipalRepository.Source.LOCAL)
                .filter(candidate -> candidate.status() == PrincipalRepository.Status.ACTIVE)
                .filter(PrincipalRepository.Principal::mustRotate)
                .orElseThrow(CredentialRotationRefusedException::new);
        String subjectHash = rotationSubjectHash(principal.id());
        Instant now = clock.instant();
        if (throttles.isLocked(subjectHash, now)) {
            // OR-24: no Argon2 verification and no further principal lookup against a locked
            // rotation subject — the lock state this would hide is the caller's own. Set the audit
            // context and probe (fires the OR-19 trigger AT MOST ONCE PER LOCKOUT, never once per
            // refused request — see LoginThrottleRepository#probe) inside ONE transaction, attributed
            // to this principal (already resolved above, unlike login's unresolved-username case).
            transactions.executeWithoutResult(status -> {
                auditContext.set(principal.id(), AuditOperations.CREDENTIAL_ROTATION_REFUSED,
                        origin.correlationId(), origin.clientAddress());
                throttles.probe(subjectHash, now);
            });
            throw new CredentialRotationRefusedException();
        }
        if (!hasher.verify(currentCredential, principal.credentialHash())) {
            transactions.executeWithoutResult(status -> {
                auditContext.set(principal.id(), AuditOperations.CREDENTIAL_ROTATION_REFUSED,
                        origin.correlationId(), origin.clientAddress());
                principals.recordCredentialFailure(principal.id());
                throttles.recordFailure(subjectHash, policy, now);
            });
            throw new CredentialRotationRefusedException();
        }
        if (hasher.verify(replacementCredential, principal.credentialHash())) {
            throw new CredentialRotationRefusedException();
        }
        String replacementHash = hasher.hash(replacementCredential);
        transactions.executeWithoutResult(status -> {
            auditContext.set(principal.id(), AuditOperations.CREDENTIAL_ROTATED, origin.correlationId(),
                    origin.clientAddress());
            // Moved inside this transaction (was a bare autocommit write): otherwise this UPDATE's own
            // change_audit row carries NULL context instead of CREDENTIAL_ROTATED / the correlation id.
            throttles.clearFailures(subjectHash, now);
            if (!sessions.lockActiveFor(currentSession.id(), currentSession.principal(), clock.instant())) {
                throw new CredentialRotationRefusedException();
            }
            if (!principals.rotateCredential(principal.id(), principal.credentialHash(), replacementHash)) {
                throw new CredentialRotationRefusedException();
            }
            sessions.revokeAllExceptFor(new PrincipalRef(principal.id()), currentSession.id(), clock.instant());
        });
    }

    /** The rotation throttle's own subject-hash space — never the username login's hash uses. */
    public static String rotationSubjectHash(UUID principalId) {
        return sha256(SUBJECT_PREFIX + principalId);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
