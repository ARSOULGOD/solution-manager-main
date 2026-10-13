package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.RoleView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Roles and role bindings for the assignment-administration surface (plan 013 T3.1). Its JDBC adapter belongs in
 * {@code cp.persistence}. Every binding read names the organization through the <i>principal</i> it binds ({@code I5}):
 * a binding of another organization's principal is exactly as absent as a nonexistent one. A binding whose scope is a
 * retired instance grants nothing (V017) and is not shown by any read here. Every write must run in the caller's
 * transaction, after the audit context is set ({@code OR-25}).
 */
public interface AssignmentRepository {

    /** The built-in roles ordered by name, each with its verbs sorted. */
    List<RoleView> roles();

    Optional<RoleView> findRole(UUID roleId);

    /** The organization's bindings, optionally of one principal, ordered by principal then role then id. */
    List<AssignmentView> list(UUID organizationId, Optional<UUID> principalId);

    Optional<AssignmentView> find(UUID organizationId, UUID assignmentId);

    /**
     * Binds {@code roleId} to the principal at {@code scope}, canonicalizing the scope row when none exists.
     * {@code created} is {@code false} when {@code (principal, role, scope)} is already bound: the existing binding is
     * returned and nothing is written.
     */
    Bound bind(UUID assignmentId, UUID principalId, UUID roleId, ScopePath scope);

    /** @return whether a row was deleted */
    boolean unbind(UUID organizationId, UUID assignmentId);

    record Bound(AssignmentView assignment, boolean created) {
    }
}
