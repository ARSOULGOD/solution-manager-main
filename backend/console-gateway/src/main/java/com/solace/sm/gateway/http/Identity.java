package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Response model for {@code login} and {@code getIdentity}. */
public record Identity(UUID principalId,
                       String username, IdentitySource source, boolean mustRotate,
                       List<EffectivePermission> permissions, String csrfToken) {

    public Identity {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        Objects.requireNonNull(csrfToken, "csrfToken");
    }

    @Override
    public String toString() {
        return "Identity[principalId=" + principalId + ", username=" + username + ", source=" + source + ", mustRotate="
                + mustRotate + ", permissions=" + permissions + ", csrfToken=[REDACTED]]";
    }
}
