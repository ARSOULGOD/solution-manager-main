package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.Permission;

/**
 * One of {@code declareInstance}'s secondary verbs ({@code instance.bindTarget}, {@code target.use}) is not held where
 * it must be: {@code D10}'s two-sided rule. Rendered {@code 403 FORBIDDEN} naming WHICH verb is missing - a verb id is
 * a closed catalog value, not request content, and the caller is told exactly what to ask for.
 */
public final class SecondaryVerbDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Permission permission;

    public SecondaryVerbDeniedException(Permission permission) {
        super("secondary verb not held: " + permission.id());
        this.permission = permission;
    }

    public Permission permission() {
        return permission;
    }
}
