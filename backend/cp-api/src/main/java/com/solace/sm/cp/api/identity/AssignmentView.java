package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Objects;
import java.util.UUID;

/** One role binding: a principal holds a role at a scope (plan 013 T3.1, D-REG 5.4 {@code Assignment}). */
public record AssignmentView(UUID assignmentId, UUID principalId, UUID roleId, ScopePath scope) {

    public AssignmentView {
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
    }
}
