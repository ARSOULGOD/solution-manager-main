package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;

class LocalLoginServiceTest {

    private static final RequestOrigin ORIGIN = new RequestOrigin("198.51.100.10", UUID.randomUUID());
    private static final AuditContext NO_OP_AUDIT_CONTEXT = (actor, operation, correlationId, clientAddress) -> { };
    private static final TransactionOperations NO_TRANSACTION = TransactionOperations.withoutTransaction();

    @Test
    void unknownUsernameStillPerformsCredentialVerificationBeforeGenericRefusal() {
        RecordingVerifier verifier = new RecordingVerifier();
        LocalLoginService service = new LocalLoginService(new UnknownPrincipalRepository(), unusedSessionService(), verifier,
                new UnlockedThrottleRepository(), LoginRateLimitPolicy.LOCAL_DEFAULT, LoginRateLimitPolicy.ADDRESS_DEFAULT,
                NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("acme", "missing@example.test", "candidate".toCharArray()), ORIGIN))
                .isInstanceOf(LoginRefusedException.class)
                .hasMessage("authentication failed");

        assertThat(verifier.verifications).isEqualTo(1);
    }

    /**
     * {@code DataAccessResourceFailureException} from the PRINCIPAL lookup — a broken database, not a
     * bad credential — must reach the caller as-is. A service that swallowed it into
     * {@link LoginRefusedException} would tell an operator "wrong password" when the truth is "the
     * database is unreachable", and would tell an attacker probing for infrastructure outages nothing
     * different from a routine miss.
     */
    @Test
    void aPrincipalRepositoryInfrastructureFailurePropagatesAndIsNotConvertedToALoginRefusal() {
        LocalLoginService service = new LocalLoginService(new FailingPrincipalRepository(), unusedSessionService(),
                new RecordingVerifier(), new UnlockedThrottleRepository(), LoginRateLimitPolicy.LOCAL_DEFAULT,
                LoginRateLimitPolicy.ADDRESS_DEFAULT, NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("acme", "infra-outage@example.test", "candidate".toCharArray()), ORIGIN))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .isNotInstanceOf(LoginRefusedException.class);
    }

    /**
     * OR-24: a refusal against a locked ADDRESS performs NO Argon2 verification at all — the lock
     * state this would hide is the caller's own, so there is nothing left to protect by paying the
     * same constant work as an unknown username or a wrong password (the PRE-OR-24 rule this test
     * used to prove). OR-23: the USERNAME dimension is never read or probed here at all — only the
     * address is, because the address is the only dimension {@code authenticate} still gates on.
     */
    @Test
    void aLockedAddressPerformsNoVerificationAndProbesOnlyTheAddressDimension() {
        RecordingVerifier verifier = new RecordingVerifier();
        LockedThrottleRepository throttles = new LockedThrottleRepository();
        LocalLoginService service = new LocalLoginService(new UnknownPrincipalRepository(), unusedSessionService(), verifier,
                throttles, LoginRateLimitPolicy.LOCAL_DEFAULT, LoginRateLimitPolicy.ADDRESS_DEFAULT,
                NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("acme", "locked@example.test", "candidate".toCharArray()), ORIGIN))
                .isInstanceOf(LoginRefusedException.class)
                .hasMessage("authentication failed");

        assertThat(verifier.verifications).as("OR-24: zero Argon2 calls on a locked-address refusal").isEqualTo(0);
        assertThat(throttles.probed).as("OR-23: only the address dimension is ever probed — never the username")
                .containsExactly(LocalLoginService.addressSubjectHash(ORIGIN.clientAddress()));
    }

    /**
     * The same obligation for the THROTTLE repository's {@code isLocked} check, which runs BEFORE
     * credential verification — a database outage there must not be reported as a lockout either.
     */
    @Test
    void aLoginThrottleRepositoryInfrastructureFailurePropagatesAndIsNotConvertedToALoginRefusal() {
        LocalLoginService service = new LocalLoginService(new UnknownPrincipalRepository(), unusedSessionService(),
                new RecordingVerifier(), new FailingThrottleRepository(), LoginRateLimitPolicy.LOCAL_DEFAULT,
                LoginRateLimitPolicy.ADDRESS_DEFAULT, NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("acme", "infra-outage@example.test", "candidate".toCharArray()), ORIGIN))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .isNotInstanceOf(LoginRefusedException.class);
    }

    /**
     * OR-125 (4), no oracle: an organization that names nothing is refused with the same work as a missing user — one
     * credential verification, the same message — and the failure is recorded on BOTH dimensions, the username one
     * under the unresolved subject (so a counter existing, or not, does not tell organizations apart).
     */
    @Test
    void anUnknownOrganizationVerifiesOnceFailsOnBothDimensionsUnderTheUnresolvedSubjectAndStillRunsTheUserLookup() {
        RecordingVerifier verifier = new RecordingVerifier();
        RecordingThrottleRepository throttles = new RecordingThrottleRepository();
        java.util.concurrent.atomic.AtomicBoolean lookedUp = new java.util.concurrent.atomic.AtomicBoolean();
        UnknownPrincipalRepository principals = new UnknownPrincipalRepository() {
            @Override
            public Optional<Principal> findLocalByUsername(UUID organizationId, String username) {
                // W5D-1: the lookup still RUNS (equal work), under the organization that matches nothing.
                assertThat(organizationId).isEqualTo(PrincipalRepository.NO_ORGANIZATION);
                lookedUp.set(true);
                return Optional.empty();
            }
        };
        LocalLoginService service = new LocalLoginService(principals, unusedSessionService(), verifier, throttles,
                LoginRateLimitPolicy.LOCAL_DEFAULT, LoginRateLimitPolicy.ADDRESS_DEFAULT, NO_OP_AUDIT_CONTEXT,
                NO_TRANSACTION, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("Nowhere", "Operator", "candidate".toCharArray()), ORIGIN))
                .isInstanceOf(LoginRefusedException.class)
                .hasMessage("authentication failed");

        assertThat(lookedUp).as("the user lookup runs even though the organization did not resolve").isTrue();
        assertThat(verifier.verifications).as("the same Argon2 work as an unknown user").isEqualTo(1);
        assertThat(throttles.failures).containsExactlyInAnyOrder(
                LocalLoginService.unresolvedLoginSubjectHash("nowhere", "operator"),
                LocalLoginService.addressSubjectHash(ORIGIN.clientAddress()));
    }

    /** OR-125 (4): a locked address is still refused before the organization is even looked up. */
    @Test
    void aLockedAddressIsRefusedBeforeTheOrganizationIsResolved() {
        UnknownPrincipalRepository principals = new UnknownPrincipalRepository() {
            @Override
            public Optional<UUID> resolveOrganization(String idOrName) {
                throw new AssertionError("no lookup against a locked address");
            }
        };
        LocalLoginService service = new LocalLoginService(principals, unusedSessionService(), new RecordingVerifier(),
                new LockedThrottleRepository(), LoginRateLimitPolicy.LOCAL_DEFAULT, LoginRateLimitPolicy.ADDRESS_DEFAULT,
                NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());

        assertThatThrownBy(() -> service.authenticate(
                new LocalCredentials("acme", "locked", "candidate".toCharArray()), ORIGIN))
                .isInstanceOf(LoginRefusedException.class);
    }

    private static final class RecordingThrottleRepository implements LoginThrottleRepository {
        private final java.util.List<String> failures = new java.util.ArrayList<>();

        @Override
        public boolean isLocked(String subjectHash, Instant now) {
            return false;
        }

        @Override
        public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
            failures.add(subjectHash);
        }

        @Override
        public void clearFailures(String subjectHash, Instant now) {
        }

        @Override
        public void probe(String subjectHash, Instant now) {
            throw new AssertionError("an unlocked subject must never be probed");
        }
    }

    private static final class FailingPrincipalRepository extends UnknownPrincipalRepository {
        @Override
        public Optional<UUID> resolveOrganization(String idOrName) {
            throw new DataAccessResourceFailureException("database unreachable");
        }

        @Override
        public Optional<Principal> findLocalByUsername(UUID organizationId, String username) {
            throw new DataAccessResourceFailureException("database unreachable");
        }
    }

    private static final class FailingThrottleRepository implements LoginThrottleRepository {
        @Override
        public boolean isLocked(String subjectHash, Instant now) {
            throw new DataAccessResourceFailureException("database unreachable");
        }

        @Override
        public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearFailures(String subjectHash, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void probe(String subjectHash, Instant now) {
            throw new UnsupportedOperationException();
        }
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

            @Override
            public Optional<UUID> principalIdFor(SessionId id) {
                throw new UnsupportedOperationException();
            }
        }, new UnknownPrincipalRepository(), NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());
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

    private static final class LockedThrottleRepository implements LoginThrottleRepository {
        private final java.util.List<String> probed = new java.util.ArrayList<>();

        @Override
        public boolean isLocked(String subjectHash, Instant now) {
            return true;
        }

        @Override
        public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
            throw new AssertionError("a locked subject must not record an additional failure — that "
                    + "would extend the lockout window on every probe of an already-locked account");
        }

        @Override
        public void clearFailures(String subjectHash, Instant now) {
            throw new AssertionError("a locked subject must never clear failures");
        }

        @Override
        public void probe(String subjectHash, Instant now) {
            probed.add(subjectHash);
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

        @Override
        public void probe(String subjectHash, Instant now) {
            throw new AssertionError("an unlocked subject must never be probed");
        }
    }

    private static class UnknownPrincipalRepository implements PrincipalRepository {
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
        public Optional<UUID> resolveOrganization(String idOrName) {
            return Optional.empty();
        }

        @Override
        public Optional<Principal> findLocalByUsername(UUID organizationId, String username) {
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
