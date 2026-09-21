package com.solace.sm.cp.api.session;

import com.solace.sm.cp.api.authz.PrincipalRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Resolved server-side session state. It deliberately contains no bearer or CSRF token. */
public record Session(SessionId id, PrincipalRef principal, UUID organizationId, boolean mustRotate,
                      Instant createdAt, Instant lastUsedAt, Instant idleExpiresAt, Instant absoluteExpiresAt) {
    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(organizationId, "organization id");
        Objects.requireNonNull(createdAt, "created at");
        Objects.requireNonNull(lastUsedAt, "last used at");
        Objects.requireNonNull(idleExpiresAt, "idle expires at");
        Objects.requireNonNull(absoluteExpiresAt, "absolute expires at");
    }
}
