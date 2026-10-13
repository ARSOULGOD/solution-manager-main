package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Wire shape of console.yaml's {@code Role}: a built-in role and the verbs it holds (catalog ids, sorted). */
public record Role(UUID id, String name, List<String> permissions) {

    public Role {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
