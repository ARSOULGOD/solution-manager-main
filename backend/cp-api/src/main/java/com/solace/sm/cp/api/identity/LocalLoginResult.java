package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import java.util.Objects;

/** A newly established durable session and its session-bound CSRF token. */
public record LocalLoginResult(SessionToken sessionToken, CsrfToken csrfToken, Session session) {
    public LocalLoginResult {
        Objects.requireNonNull(sessionToken, "session token");
        Objects.requireNonNull(csrfToken, "csrf token");
        Objects.requireNonNull(session, "session");
    }
}
