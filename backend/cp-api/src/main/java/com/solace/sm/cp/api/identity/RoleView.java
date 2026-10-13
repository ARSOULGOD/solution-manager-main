package com.solace.sm.cp.api.identity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A built-in role and the verbs it holds (plan 013 T3.1, D-REG 5.4 {@code Role}). Verbs are catalog ids, sorted. */
public record RoleView(UUID id, String name, List<String> permissions) {

    public RoleView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
