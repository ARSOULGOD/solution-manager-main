package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Objects;
import java.util.Set;

/** One evaluated role binding and the permissions granted by its role. */
public record AuthorizationGrant(ScopePath scope, Set<Permission> permissions) {

    public AuthorizationGrant {
        Objects.requireNonNull(scope, "scope");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
