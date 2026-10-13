package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code User}: a local principal of the session's organization (plan 013 T3.2).
 *
 * <p>⛔ {@code I6}: there is no field for a credential or a credential hash, so no serializer, log line or
 * {@code toString} can carry one. There is no organization field either: every user listed is the session's
 * organization's ({@code I5}).
 */
public record User(UUID id, String username, IdentitySource source, UserStatus status, boolean mustRotate,
                   String createdAt) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
