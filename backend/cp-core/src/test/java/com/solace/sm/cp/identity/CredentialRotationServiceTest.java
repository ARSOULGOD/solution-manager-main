package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.CredentialRotationRefusedException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Pure-fake unit coverage for {@link CredentialRotationService}'s locked-rotation-subject branch —
 * the rotation-side counterpart of {@code LocalLoginServiceTest}'s locked-address proof. Depends on
 * {@link CredentialVerifier} rather than the concrete {@link CredentialHasher} specifically so this
 * can prove {@code OR-24}'s "no Argon2 verification against a locked subject" without a real Argon2
 * hasher or a database.
 */
class CredentialRotationServiceTest {

    private static final RequestOrigin ORIGIN = new RequestOrigin("198.51.100.20", UUID.randomUUID());
    private static final AuditContext NO_OP_AUDIT_CONTEXT = (actor, operation, correlationId, clientAddress) -> { };
    private static final TransactionOperations NO_TRANSACTION = TransactionOperations.withoutTransaction();

    /**
     * OR-24: a refusal against a locked rotation subject performs NO Argon2 verification and no
     * further principal lookup — the lock state this would hide is the caller's own.
     */
    @Test
    void aLockedRotationSubjectPerformsNoVerificationAndProbesOnlyTheRotationSubject() {
        UUID principalId = UUID.randomUUID();
        RecordingVerifier verifier = new RecordingVerifier();
        LockedThrottleRepository throttles = new LockedThrottleRepository();
        CredentialRotationService rotation = new CredentialRotationService(
                new ActivePrincipalRepository(principalId), new UsableSessionRepository(), verifier, throttles,
                LoginRateLimitPolicy.LOCAL_DEFAULT, NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());
        Session session = sessionFor(principalId);

        assertThatThrownBy(() -> rotation.rotate(session, "current".toCharArray(), "replacement".toCharArray(), ORIGIN))
                .isInstanceOf(CredentialRotationRefusedException.class);

        assertThat(verifier.verifications).as("OR-24: zero Argon2 calls on a locked-subject refusal").isEqualTo(0);
        assertThat(throttles.probed).containsExactly(CredentialRotationService.rotationSubjectHash(principalId));
    }

    /**
     * Same fixture, unlocked: the ordinary wrong-credential path DOES pay exactly one verification —
     * this is the contrast that proves the locked branch above is the one skipping it, not a fake
     * that never counts at all.
     */
    @Test
    void anUnlockedRotationSubjectWithAWrongCurrentCredentialPaysExactlyOneVerification() {
        UUID principalId = UUID.randomUUID();
        RecordingVerifier verifier = new RecordingVerifier();
        UnlockedThrottleRepository throttles = new UnlockedThrottleRepository();
        CredentialRotationService rotation = new CredentialRotationService(
                new ActivePrincipalRepository(principalId), new UsableSessionRepository(), verifier, throttles,
                LoginRateLimitPolicy.LOCAL_DEFAULT, NO_OP_AUDIT_CONTEXT, NO_TRANSACTION, Clock.systemUTC());
        Session session = sessionFor(principalId);

        assertThatThrownBy(() -> rotation.rotate(session, "wrong current".toCharArray(), "replacement".toCharArray(), ORIGIN))
                .isInstanceOf(CredentialRotationRefusedException.class);

        assertThat(verifier.verifications).isEqualTo(1);
        assertThat(throttles.failuresRecordedFor).containsExactly(CredentialRotationService.rotationSubjectHash(principalId));
    }

    private static Session sessionFor(UUID principalId) {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        return new Session(new SessionId(UUID.randomUUID()), new PrincipalRef(principalId), UUID.randomUUID(), true,
                now, now, now.plusSeconds(1_800), now.plusSeconds(43_200));
    }

    private static final class RecordingVerifier implements CredentialVerifier {
        private int verifications;

        @Override
        public String hash(char[] credential) {
            throw new UnsupportedOperationException("neither test below reaches a successful rotation");
        }

        @Override
        public boolean verify(char[] credential, String credentialHash) {
            verifications++;
            return false;
        }
    }

    /** Always reports the rotation subject locked, and fails the test if a fresh failure is ever recorded. */
    private static final class LockedThrottleRepository implements LoginThrottleRepository {
        private final java.util.List<String> probed = new java.util.ArrayList<>();

        @Override
        public boolean isLocked(String subjectHash, Instant now) {
            return true;
        }

        @Override
        public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
            throw new AssertionError("a locked rotation subject must not record an additional failure");
        }

        @Override
        public void clearFailures(String subjectHash, Instant now) {
            throw new AssertionError("a locked rotation subject must never clear failures");
        }

        @Override
        public void probe(String subjectHash, Instant now) {
            probed.add(subjectHash);
        }
    }

    /** Always reports the rotation subject unlocked, and records which subjects saw a fresh failure. */
    private static final class UnlockedThrottleRepository implements LoginThrottleRepository {
        private final java.util.List<String> failuresRecordedFor = new java.util.ArrayList<>();

        @Override
        public boolean isLocked(String subjectHash, Instant now) {
            return false;
        }

        @Override
        public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
            failuresRecordedFor.add(subjectHash);
        }

        @Override
        public void clearFailures(String subjectHash, Instant now) {
        }

        @Override
        public void probe(String subjectHash, Instant now) {
            throw new AssertionError("an unlocked subject must never be probed");
        }
    }

    /** A single active, must-rotate LOCAL principal — never actually consulted past the credential check. */
    private static final class ActivePrincipalRepository implements PrincipalRepository {
        private final UUID principalId;

        private ActivePrincipalRepository(UUID principalId) {
            this.principalId = principalId;
        }

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
            return Optional.of(new Principal(principalId, UUID.randomUUID(), Source.LOCAL, "rotation@example.test",
                    "{argon2id}stored", Status.ACTIVE, true, Instant.parse("2026-01-01T00:00:00Z"), null));
        }

        @Override
        public Optional<UUID> resolveOrganization(String idOrName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Principal> findLocalByUsername(UUID organizationId, String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean rotateCredential(UUID id, String expectedCredentialHash, String replacementCredentialHash) {
            throw new UnsupportedOperationException("neither test below reaches a successful rotation");
        }

        @Override
        public void recordCredentialFailure(UUID id) {
            // The unlocked/wrong-credential test's own recordFailure path calls this too; it is
            // observed only through the throttle fake above, so this is deliberately a no-op.
        }

        @Override
        public void deactivate(UUID id) {
            throw new UnsupportedOperationException();
        }
    }

    /** A session that is always still active and lockable — never a boundary this test is about. */
    private static final class UsableSessionRepository implements SessionRepository {
        @Override
        public boolean createForActivePrincipal(Session session, String tokenHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Session> resolveAndTouch(String tokenHash, Instant now, java.time.Duration idleTimeout) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException("neither test below reaches a successful rotation");
        }

        @Override
        public boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now) {
            return true;
        }

        @Override
        public Optional<String> activeTokenHash(SessionId id, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> principalIdFor(SessionId id) {
            throw new UnsupportedOperationException();
        }
    }
}
