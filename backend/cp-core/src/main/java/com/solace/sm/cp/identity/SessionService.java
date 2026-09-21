package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
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

/**
 * Issues and resolves durable, opaque server-side sessions. A deactivation committed before the issuance statement
 * prevents issuance; a concurrent deactivation after that statement's snapshot can leave a returned token unusable,
 * because every resolve rechecks the principal's active status.
 */
public final class SessionService {

    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;

    private final SessionRepository sessions;
    private final PrincipalRepository principals;
    private final Clock clock;
    private final SecureRandom random;

    public SessionService(SessionRepository sessions, PrincipalRepository principals, Clock clock) {
        this(sessions, principals, clock, new SecureRandom());
    }

    SessionService(SessionRepository sessions, PrincipalRepository principals, Clock clock, SecureRandom random) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public SessionToken open(PrincipalRef principal) {
        if (principal == null) {
            throw new SessionNotIssuableException();
        }
        PrincipalRepository.Principal stored = principals.find(principal.id())
                .filter(candidate -> candidate.status() == PrincipalRepository.Status.ACTIVE)
                .orElseThrow(SessionNotIssuableException::new);
        Instant now = clock.instant();
        SessionToken token = new SessionToken(nextOpaqueValue());
        boolean issued = sessions.createForActivePrincipal(
                new Session(new SessionId(UUID.randomUUID()), principal, stored.organizationId(), stored.mustRotate(),
                        now, now, now.plus(IDLE_TIMEOUT), now.plus(ABSOLUTE_TIMEOUT)),
                sha256(token.value()));
        if (!issued) {
            throw new SessionNotIssuableException();
        }
        return token;
    }

    public Optional<Session> resolve(SessionToken token) {
        if (token == null) {
            return Optional.empty();
        }
        return sessions.resolveAndTouch(sha256(token.value()), clock.instant(), IDLE_TIMEOUT);
    }

    public void revoke(SessionId id) {
        sessions.revoke(id, clock.instant());
    }

    public void revokeAllFor(PrincipalRef principal) {
        sessions.revokeAllFor(principal, clock.instant());
    }

    public Optional<CsrfToken> csrfTokenFor(SessionId id) {
        if (id == null) {
            return Optional.empty();
        }
        return sessions.activeTokenHash(id, clock.instant()).map(tokenHash -> csrfFor(id, tokenHash));
    }

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
