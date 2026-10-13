package com.solace.sm.cp.api.session;

import java.util.Optional;

/**
 * CP-owned session port for a gateway that depends only on {@code cp-api}.
 *
 * <p>Every method is evaluated against durable server-side state; nothing here trusts a value the
 * client could have forged. A token or CSRF value is never logged, and none of the types it
 * exchanges renders one through {@code toString()}.
 */
public interface SessionAuthentication {

    /**
     * Resolves a presented bearer token to its live session, touching its idle expiry.
     *
     * @return empty for an unknown, expired, revoked or deactivated-principal session — the four are
     *         deliberately not distinguished, so a caller cannot probe which state a token is in
     */
    Optional<Session> resolve(SessionToken token);

    /**
     * True only when {@code presented} is the CSRF token bound to {@code session}. A token minted
     * for any other session, including another session of the same principal, is refused.
     */
    boolean verifyCsrf(SessionId session, CsrfToken presented);

    /** The CSRF token bound to a live session, or empty once it is revoked or expired. */
    Optional<CsrfToken> csrfTokenFor(SessionId session);

    /** Revokes one session. Idempotent. The revocation is audited against {@code origin} ({@code OR-19}). */
    void revoke(SessionId session, com.solace.sm.cp.api.audit.RequestOrigin origin);
}
