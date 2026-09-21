package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** CP-owned local login with generic failures and a configurable durable throttle. */
public final class LocalLoginService implements LocalAuthenticationService {

    private final PrincipalRepository principals;
    private final SessionService sessions;
    private final CredentialVerifier hasher;
    private final LoginThrottleRepository throttles;
    private final LoginRateLimitPolicy policy;
    private final Clock clock;
    private final String unknownCredentialHash;

    public LocalLoginService(PrincipalRepository principals, SessionService sessions, CredentialVerifier hasher,
                             LoginThrottleRepository throttles, LoginRateLimitPolicy policy, Clock clock) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.throttles = Objects.requireNonNull(throttles, "throttles");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        char[] unknownCredential = new char[32];
        try {
            unknownCredentialHash = hasher.hash(unknownCredential);
        } finally {
            Arrays.fill(unknownCredential, '\0');
        }
    }

    @Override
    public LocalLoginResult authenticate(LocalCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        String subjectHash = sha256(credentials.username());
        Instant now = clock.instant();
        if (throttles.isLocked(subjectHash, now)) {
            throw new LoginRefusedException();
        }
        char[] candidate = credentials.credential();
        try {
            Optional<PrincipalRepository.Principal> principal = principals.findLocalByUsername(credentials.username());
            String credentialHash = principal.map(PrincipalRepository.Principal::credentialHash)
                    .orElse(unknownCredentialHash);
            boolean verified = hasher.verify(candidate, credentialHash);
            if (principal.isEmpty() || !verified
                    || principal.orElseThrow().status() != PrincipalRepository.Status.ACTIVE) {
                throttles.recordFailure(subjectHash, policy, now);
                throw new LoginRefusedException();
            }
            throttles.clearFailures(subjectHash, now);
            SessionToken token = sessions.open(new PrincipalRef(principal.orElseThrow().id()));
            Session session = sessions.resolve(token).orElseThrow(LoginRefusedException::new);
            CsrfToken csrf = sessions.csrfTokenFor(session.id()).orElseThrow(LoginRefusedException::new);
            return new LocalLoginResult(token, csrf, session);
        } finally {
            Arrays.fill(candidate, '\0');
        }
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
