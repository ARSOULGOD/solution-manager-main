package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Roles and role bindings (plan 013 T3.1, D-REG 5.4 U4 to U7, {@code OQ-6 (a)}): list the built-in roles, list and
 * grant and revoke assignments.
 *
 * <p><b>Granting is authorized at the granted scope, never above it.</b> {@code role.assign} must be held <i>at</i> the
 * scope being granted, so a principal bound at a sub-organization can grant inside it and finds the organization (and a
 * sibling sub-organization) refused or absent: it cannot escalate. The scope is canonicalized server-side through
 * {@code ScopeStore.canonicalize} <b>after</b> that check, inside the grant's transaction, and only when the named
 * nodes really are in that order (an environment of another sub-organization is absent, not a foreign key
 * violation). The organization is always the session's ({@code I5}); the install scope is never grantable here.
 *
 * <p><b>A grantor holds what it grants</b> (W3D-4): once the role is known, the caller must hold <i>every verb of that
 * role at the granted scope</i> (an {@link AccessDeniedException} otherwise, naming the first verb it lacks). Today only
 * the Administrator holds {@code role.assign} and it holds the whole catalog, so the rule refuses no one in v1; it keeps
 * a future intermediate role (D-REG {@code OQ-7 (c)}) from granting Operator standing it was never given.
 *
 * <p><b>Administrator standing is made and unmade only outside the API</b> ({@code OR-12}, {@code OQ-6 (a)}): granting
 * the Administrator role is {@link AssignmentInvalidException} (422) and revoking an Administrator binding is
 * {@link AssignmentConflictException} (409), whoever asks. The API therefore never creates a second administrator and
 * never removes the last one.
 *
 * <p><b>Reads.</b> {@link #listRoles} needs {@code role.view} at the organization (an <b>open owner question</b>,
 * W3D-2: {@code console-integration.md} 5.2 row 13 says install). {@link #list} needs it somewhere in
 * the organization and returns only the bindings whose scope some {@code role.view} grant of the caller reaches
 * downward, so an assignment outside the caller's reach is simply absent, never an error.
 *
 * <p><b>Revoking</b> needs {@code role.assign} at the binding's own scope; a binding that does not exist in the
 * session's organization, or whose scope is a retired instance (which grants nothing), is
 * {@link ResourceNotFoundException}. The revoked principal's live sessions are untouched: authorization reads the
 * bindings on every request, so the next request is evaluated without the grant.
 *
 * <p><b>Failures</b>: {@link AccessDeniedException}, {@link ResourceNotFoundException}, {@link AssignmentInvalidException},
 * {@link AssignmentConflictException}, {@link IllegalArgumentException} (the install scope named).
 */
public interface AssignmentAdministration {

    /** A grant's outcome: the assignment, and whether this call wrote it ({@code false}: an identical replay). */
    record Granted(AssignmentView assignment, boolean created) {

        public Granted {
            Objects.requireNonNull(assignment, "assignment");
        }
    }

    /** The three built-in roles, ordered by name. {@code role.view} at the organization. */
    List<RoleView> listRoles(PrincipalRef actor, UUID organizationId);

    /**
     * The organization's assignments the caller's {@code role.view} reaches, optionally of one principal. A principal
     * who is not in the organization yields an empty list (never distinguishable from "no assignments").
     */
    List<AssignmentView> list(PrincipalRef actor, UUID organizationId, Optional<UUID> principalId);

    /**
     * One assignment of the organization by id, or empty when there is none there (another organization's assignment is
     * exactly as absent). <b>No verb is checked and nothing is written</b>: this is the gateway's address resolution for
     * {@link #revoke}, whose {@code role.assign} must be evaluated at the binding's own scope BEFORE the handler runs
     * ({@code x-sm-scope: named}), so the scope has to be read to be authorized against. It reveals a scope to a
     * session only for an id it already holds; the caller authorizes at that scope and answers an unauthorized one
     * exactly as an absent one. {@link #revoke} still reads and authorizes for itself.
     */
    Optional<AssignmentView> find(UUID organizationId, UUID assignmentId);

    /** An identical grant ({@code role_binding_unique}) is a replay: the existing assignment, nothing written. */
    Granted grant(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID principalId, UUID roleId,
            ScopePath scope);

    void revoke(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID assignmentId);
}
