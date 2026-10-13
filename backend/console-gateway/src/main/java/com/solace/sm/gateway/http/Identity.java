package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Response model for {@code login} and {@code getIdentity}. {@code organizations} is the caller's
 * visible hierarchy (ruling {@code OR-116}) — always present, empty when the caller has no bindings
 * or while {@code mustRotate} is true.
 */
public record Identity(UUID principalId,
                       String username, IdentitySource source, boolean mustRotate,
                       List<EffectivePermission> permissions, List<VisibleOrganization> organizations,
                       String csrfToken) {

    public Identity {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        organizations = List.copyOf(Objects.requireNonNull(organizations, "organizations"));
        Objects.requireNonNull(csrfToken, "csrfToken");
    }

    @Override
    public String toString() {
        return "Identity[principalId=" + principalId + ", username=" + username + ", source=" + source + ", mustRotate="
                + mustRotate + ", permissions=" + permissions + ", organizations=" + organizations
                + ", csrfToken=[REDACTED]]";
    }
}
