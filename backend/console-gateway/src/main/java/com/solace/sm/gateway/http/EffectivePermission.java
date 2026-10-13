package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** One effective permission and the scopes at which it is held. */
public record EffectivePermission(String permission, List<ScopePath> scopes) {

    public EffectivePermission {
        Objects.requireNonNull(permission, "permission");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
    }
}
