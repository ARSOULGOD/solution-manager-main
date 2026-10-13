package com.solace.sm.cp.api.hierarchy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import java.util.Objects;
import java.util.UUID;

/**
 * The scope tree's writes (plan 013 T2.2, {@code BE-D57}; D-REG 5.3 H1 to H5): create a sub-organization or an
 * environment, rename an organization, a sub-organization or an environment. The reads are {@link HierarchyDirectory}.
 *
 * <p><b>Authorization is {@code hierarchy.edit}</b> ({@code OR-121}, STANDARD), evaluated at the <i>parent</i> of a
 * create and at the <i>node</i> of a rename; this port calls {@code AuthorizationService.require} itself, before any
 * write, so a caller who may not edit never learns whether a name is taken. A principal bound at a sub-organization
 * therefore creates environments in its own unit, and finds a sibling unit absent.
 *
 * <p><b>Names.</b> A name is 1 to 100 characters, with no control character, and not blank (the {@code V015} shape
 * check); surrounding whitespace is stripped. Uniqueness among siblings is the <b>database's</b>, compared
 * case-insensitively ({@code OR-123}): see {@link HierarchyNameConflictException}. No method pre-checks a name.
 *
 * <p><b>Idempotence.</b> A create is keyed by {@code (parent, name)}: asking again for the exact name that already
 * exists under the parent returns that node with {@code created = false} and writes nothing (no second audit row).
 * A name that differs only by case is a different request and is refused as a conflict.
 *
 * <p><b>No scope row is inserted by a create.</b> The canonical {@code scope} row of a node is minted on first use by
 * {@code ScopeStore.canonicalize}, as the target and declare paths do today (R14); a new environment is a valid
 * target {@code ownershipScope} at once.
 *
 * <p>⛔ The organization is always the session's ({@code I5}); the gateway passes {@code Session.organizationId()}.
 * Another organization's node is exactly as absent as a nonexistent one.
 *
 * <p><b>Failures</b> (every method): {@link AccessDeniedException} when {@code hierarchy.edit} is not held at the
 * parent (create) or the node (rename); {@link ResourceNotFoundException} when the organization, parent or node does
 * not exist, is outside every scope the caller holds, or is another organization's; {@link HierarchyNameConflictException}
 * when a sibling has the name (case-insensitively) and the call is not the identical replay of a create;
 * {@link IllegalArgumentException} when the name is not 1 to 100 characters, has a control character, or is blank.
 */
public interface HierarchyAdministration {

    /** A create's outcome: the node, and whether this call wrote it ({@code false}: an identical replay). */
    record Created(ScopeNode node, boolean created) {

        public Created {
            Objects.requireNonNull(node, "node");
        }
    }

    /** Top-level only ({@code SM-D32}, {@code OR-16} fix the depth). {@code hierarchy.edit} at the organization. */
    Created createSubOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String name);

    /** {@code hierarchy.edit} at the parent sub-organization. */
    Created createEnvironment(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID subOrganizationId,
            String name);

    /**
     * {@code hierarchy.edit} at <b>install scope</b> ({@code OR-125} (3)), then at the named organization (which must be
     * the caller's own tenant: another's is as absent as a nonexistent one). An organization-bound grant is refused
     * ({@link AccessDeniedException}) before anything is read, whatever organization it names, so the install-wide
     * name {@link HierarchyNameConflictException} is reachable only by install principals and is no cross-tenant
     * oracle. A rename to the node's own name writes nothing.
     */
    ScopeNode renameOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String name);

    /** {@code hierarchy.edit} at the sub-organization. */
    ScopeNode renameSubOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID subOrganizationId, String name);

    /** {@code hierarchy.edit} at the environment. */
    ScopeNode renameEnvironment(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID subOrganizationId,
            UUID environmentId, String name);
}
