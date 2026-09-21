package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalLoginServiceTest {

    @Test
    void unknownUsernameStillPerformsCredentialVerificationBeforeGenericRefusal() {
        RecordingVerifier verifier = new RecordingVerifier();
        LocalLoginService service = new LocalLoginService(new UnknownPrincipalRepository(), unusedSessionService(), verifier,
                new UnlockedThrottleRepository(), LoginRateLimitPolicy.LOCAL_DEFAULT, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("missing@example.test", "candidate".toCharArray())))
                .isInstanceOf(LoginRefusedException.class)
                .hasMessage("authentication failed");

        assertThat(verifier.verifications).isEqualTo(1);
    }

    private static SessionService unusedSessionService() {
        return new SessionService(new SessionRepository() {
            @Override
            public boolean createForActivePrincipal(Session session, String tokenHash) {
                throw new AssertionError("unknown-user login must not open a session");
            }

            @Override
            public Optional<Session> resolveAndTouch(String tokenHash, Instant now, Duration idleTimeout) {
                throw new AssertionError("unknown-user login must not resolve a session");
            }

            @Override
            public void revoke(SessionId id, Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void revokeAllFor(PrincipalRef principal, Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void revokeAllExceptFor(PrincipalRef principal, SessionId retainedSession, Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<String> activeTokenHash(SessionId id, Instant now) {
                throw new UnsupportedOperationException();
            }
        }, new UnknownPrincipalRepository(), Clock.systemUTC());
    }

    private static final class RecordingVerifier implements CredentialVerifier {
        private int verifications;

        @Override
        public String hash(char[] credential) {
            return "dummy-hash";
        }

        @Override
        public boolean verify(char[] credential, String credentialHash) {
            verifications++;
            return false;
        }
    }

    private static final class UnlockedThrottleRepository implements LoginThrottleRepository {
        @Override
        public boolean isLocked(String subjectHash, Instant now) {
            return false;
        }

        @Override
        public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
        }

        @Override
        public void clearFailures(String subjectHash, Instant now) {
        }
    }

    private static final class UnknownPrincipalRepository implements PrincipalRepository {
        @Override
        public UUID createLocal(UUID organizationId, String username, String credentialHash, boolean mustRotate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UUID createIdp(UUID organizationId, String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Principal> find(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<Principal> findLocalByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public boolean rotateCredential(UUID id, String expectedCredentialHash, String replacementCredentialHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordCredentialFailure(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deactivate(UUID id) {
            throw new UnsupportedOperationException();
        }
    }
}
