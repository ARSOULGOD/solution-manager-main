package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.Session;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/** Atomically replaces a forced-rotation credential and revokes every other session. */
public final class CredentialRotationService {

    private final PrincipalRepository principals;
    private final SessionRepository sessions;
    private final CredentialHasher hasher;
    private final TransactionOperations transactions;
    private final Clock clock;

    public CredentialRotationService(PrincipalRepository principals, SessionRepository sessions, CredentialHasher hasher,
                                     TransactionOperations transactions, Clock clock) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void rotate(Session currentSession, char[] currentCredential, char[] replacementCredential) {
        Objects.requireNonNull(currentSession, "current session");
        if (!sessions.lockActiveFor(currentSession.id(), currentSession.principal(), clock.instant())) {
            throw new CredentialRotationRefusedException();
        }
        PrincipalRepository.Principal principal = principals.find(currentSession.principal().id())
                .filter(candidate -> candidate.source() == PrincipalRepository.Source.LOCAL)
                .filter(candidate -> candidate.status() == PrincipalRepository.Status.ACTIVE)
                .filter(PrincipalRepository.Principal::mustRotate)
                .orElseThrow(CredentialRotationRefusedException::new);
        if (!hasher.verify(currentCredential, principal.credentialHash())) {
            principals.recordCredentialFailure(principal.id());
            throw new CredentialRotationRefusedException();
        }
        if (hasher.verify(replacementCredential, principal.credentialHash())) {
            throw new CredentialRotationRefusedException();
        }
        String replacementHash = hasher.hash(replacementCredential);
        transactions.executeWithoutResult(status -> {
            if (!sessions.lockActiveFor(currentSession.id(), currentSession.principal(), clock.instant())) {
                throw new CredentialRotationRefusedException();
            }
            if (!principals.rotateCredential(principal.id(), principal.credentialHash(), replacementHash)) {
                throw new CredentialRotationRefusedException();
            }
            sessions.revokeAllExceptFor(new PrincipalRef(principal.id()), currentSession.id(), clock.instant());
        });
    }
}
