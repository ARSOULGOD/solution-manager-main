package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionAuthentication;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.api.session.SessionToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Issues and resolves durable, opaque server-side sessions. A deactivation committed before the issuance statement
 * prevents issuance; a concurrent deactivation after that statement's snapshot can leave a returned token unusable,
 * because every resolve rechecks the principal's active status.
 */
public final class SessionService implements SessionAuthentication {

    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;

    private final SessionRepository sessions;
    private final PrincipalRepository principals;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final SecureRandom random;

    public SessionService(SessionRepository sessions, PrincipalRepository principals, AuditContext auditContext,
                          TransactionOperations transactions, Clock clock) {
        this(sessions, principals, auditContext, transactions, clock, new SecureRandom());
    }

    SessionService(SessionRepository sessions, PrincipalRepository principals, AuditContext auditContext,
                   TransactionOperations transactions, Clock clock, SecureRandom random) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.auditContext = Objects.requireNonNull(auditContext, "audit context");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @param origin attributes the session-open write as {@code LOGIN_SUCCEEDED} ({@code OR-25}):
     *               this method sets that context itself, in the SAME transaction as the {@code
     *               INSERT}, so a caller (login today; a future SSO/impersonation path tomorrow)
     *               never has to remember to do so around it. Calling this from an ALREADY-open
     *               transaction that set the identical context first (as {@link LocalLoginService}
     *               does, so its own throttle-clear shares one audit trail with this insert) is
     *               harmless — re-setting a transaction-local GUC to the same value changes nothing.
     */
    public SessionToken open(PrincipalRef principal, RequestOrigin origin) {
        if (principal == null) {
            throw new SessionNotIssuableException();
        }
        Objects.requireNonNull(origin, "origin");
        PrincipalRepository.Principal stored = principals.find(principal.id())
                .filter(candidate -> candidate.status() == PrincipalRepository.Status.ACTIVE)
                .orElseThrow(SessionNotIssuableException::new);
        Instant now = clock.instant();
        SessionToken token = new SessionToken(nextOpaqueValue());
        boolean issued = transactions.execute(status -> {
            auditContext.set(principal.id(), AuditOperations.LOGIN_SUCCEEDED, origin.correlationId(),
                    origin.clientAddress());
            return sessions.createForActivePrincipal(
                    new Session(new SessionId(UUID.randomUUID()), principal, stored.organizationId(),
                            stored.mustRotate(), now, now, now.plus(IDLE_TIMEOUT), now.plus(ABSOLUTE_TIMEOUT)),
                    sha256(token.value()));
        });
        if (!issued) {
            throw new SessionNotIssuableException();
        }
        return token;
    }

    @Override
    public Optional<Session> resolve(SessionToken token) {
        if (token == null) {
            return Optional.empty();
        }
        return sessions.resolveAndTouch(sha256(token.value()), clock.instant(), IDLE_TIMEOUT);
    }

    @Override
    public void revoke(SessionId id, RequestOrigin origin) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(origin, "origin");
        transactions.executeWithoutResult(status -> {
            UUID actor = sessions.principalIdFor(id).orElse(null);
            auditContext.set(actor, AuditOperations.LOGOUT, origin.correlationId(), origin.clientAddress());
            sessions.revoke(id, clock.instant());
        });
    }

    public void revokeAllFor(PrincipalRef principal) {
        sessions.revokeAllFor(principal, clock.instant());
    }

    @Override
    public Optional<CsrfToken> csrfTokenFor(SessionId id) {
        if (id == null) {
            return Optional.empty();
        }
        return sessions.activeTokenHash(id, clock.instant()).map(tokenHash -> csrfFor(id, tokenHash));
    }

    @Override
    public boolean verifyCsrf(SessionId id, CsrfToken token) {
        if (id == null || token == null) {
            return false;
        }
        return sessions.activeTokenHash(id, clock.instant())
                .map(tokenHash -> MessageDigest.isEqual(
                        csrfFor(id, tokenHash).value().getBytes(StandardCharsets.US_ASCII),
                        token.value().getBytes(StandardCharsets.US_ASCII)))
                .orElse(false);
    }

    private static CsrfToken csrfFor(SessionId id, String tokenHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenHash.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return new CsrfToken(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(id.value().toString().getBytes(StandardCharsets.US_ASCII))));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private String nextOpaqueValue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
