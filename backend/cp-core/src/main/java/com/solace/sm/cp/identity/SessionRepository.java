package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable session persistence port. Only the SHA-256 bearer digest is stored; CSRF is derived per session. */
public interface SessionRepository {

    boolean createForActivePrincipal(Session session, String tokenHash);

    Optional<Session> resolveAndTouch(String tokenHash, Instant now, Duration idleTimeout);

    void revoke(SessionId id, Instant now);

    void revokeAllFor(PrincipalRef principal, Instant now);

    void revokeAllExceptFor(PrincipalRef principal, SessionId retainedSession, Instant now);

    /** Locks the current session row when called inside a transaction and confirms it is still usable. */
    boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now);

    Optional<String> activeTokenHash(SessionId id, Instant now);

    /**
     * The owning principal of a session, regardless of its revoked/expired/active-principal state —
     * unlike every other lookup above, which filters on liveness. Used only to attribute a
     * {@code LOGOUT} audit row ({@code OR-19}) to the right actor before revocation, including for an
     * ALREADY revoked session, since {@link com.solace.sm.cp.api.session.SessionAuthentication#revoke}
     * is documented idempotent.
     */
    Optional<UUID> principalIdFor(SessionId id);
}
