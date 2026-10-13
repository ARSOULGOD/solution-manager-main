package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.List;
import java.util.Objects;

/** One catalog verb the principal holds, and every scope it holds it at. For rendering, never deciding. */
public record EffectivePermissionView(Permission permission, List<ScopePath> scopes) {

    public EffectivePermissionView {
        Objects.requireNonNull(permission, "permission");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
    }
}
