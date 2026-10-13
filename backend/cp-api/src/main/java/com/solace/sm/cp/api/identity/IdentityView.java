package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.List;
import java.util.Objects;

/**
 * The current principal as the console renders it.
 *
 * <p>Carries no credential, no credential hash, no session token and no CSRF token.
 *
 * <p>{@code superAdmin} is true iff the principal is bound to the built-in Administrator role AT
 * THE INSTALL SCOPE specifically (frontend/backend-integration-auth-hierarchy.md D2) — NOT merely
 * whether {@code roleNames} contains {@code "Administrator"}, which is true for a binding at ANY
 * scope. {@code roleNames} stays display-only, as documented on {@code RoleBindingRepository}.
 */
public record IdentityView(PrincipalRef principal, String username, PrincipalSource source, boolean mustRotate,
                           List<EffectivePermissionView> permissions, List<String> roleNames,
                           boolean superAdmin) {

    public IdentityView {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        roleNames = List.copyOf(Objects.requireNonNull(roleNames, "roleNames"));
    }
}
