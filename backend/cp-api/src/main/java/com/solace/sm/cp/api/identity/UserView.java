package com.solace.sm.cp.api.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A principal as the user-administration surface shows it (plan 013 T3.1, D-REG 5.4 {@code User}).
 *
 * <p>⛔ <b>Carries no credential and no credential hash</b>, by construction: there is no field for either, so no
 * serializer, log line or {@code toString} can leak one ({@code I6}).
 */
public record UserView(UUID id, String username, PrincipalSource source, Status status, boolean mustRotate,
                       Instant createdAt) {

    /** A principal is deactivated, never deleted; there is no reactivation over the API. */
    public enum Status {
        ACTIVE,
        DEACTIVATED
    }

    public UserView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
