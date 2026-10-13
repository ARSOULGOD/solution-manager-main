package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/** Wire shape of console.yaml's {@code ScopeNode} — one visible node of the scope tree. */
public record ScopeNode(UUID id, String name) {

    public ScopeNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
