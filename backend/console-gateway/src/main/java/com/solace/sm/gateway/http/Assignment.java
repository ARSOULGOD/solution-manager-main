package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/** Wire shape of console.yaml's {@code Assignment}: a principal holds a role at a scope. */
public record Assignment(UUID assignmentId, UUID principalId, UUID roleId, ScopePath scope) {

    public Assignment {
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
    }
}
