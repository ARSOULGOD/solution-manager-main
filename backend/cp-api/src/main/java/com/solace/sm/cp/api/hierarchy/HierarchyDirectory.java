package com.solace.sm.cp.api.hierarchy;

import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.session.Session;
import java.util.List;
import java.util.UUID;

/**
 * The three "authentication only" hierarchy reads — console-integration.md §5.2 rows 8-10,
 * {@code D11-AMENDMENT-PROPOSAL.md} §3.4.
 *
 * <p>⛔ <b>None of these three is a catalog verb.</b> Visibility here is prefix-comparability with
 * a held grant in EITHER direction — ancestors of a grant and descendants of it — never a
 * permission-gated allow/deny, and {@code AuthorizationService} is never consulted for it. The
 * gateway therefore classifies these operations {@code AUTHENTICATION_ONLY}, not {@code CATALOG}:
 * see {@code OperationClassification.AuthenticationOnly}.
 *
 * <p>⛔ <b>The organization is always {@code session.organizationId()}</b> ({@code I5}) — never a
 * path parameter taken as an authority claim. A path parameter that names a different organization
 * is refused with the identical {@link ResourceNotFoundException} a nonexistent one gets: D11 §3.4
 * collapses "does not exist" and "exists but out of scope" into one answer.
 */
public interface HierarchyDirectory {

    /** The caller's own organization, or empty if they hold no grant that makes it visible. */
    List<ScopeNode> listOrganizations(Session session);

    /**
     * @throws ResourceNotFoundException if {@code organizationId} is not the session's own
     *         organization, or that organization is not visible to the caller
     */
    List<ScopeNode> listSubOrganizations(Session session, UUID organizationId);

    /**
     * The organizations the caller can see, each with its visible sub-organizations — {@code
     * Identity.organizations} (ruling {@code OR-116}).
     *
     * <p>⛔ <b>Not a second rule.</b> It is exactly {@link #listOrganizations} followed by {@link
     * #listSubOrganizations} for each organization returned, so it is never wider than those two
     * reads and is empty when the caller holds no grant. It does not itself consult the
     * forced-rotation state; the caller (the identity endpoints) populates it only when the hierarchy
     * reads would answer, i.e. when the session is not {@code mustRotate}.
     */
    List<VisibleOrganization> visibleOrganizations(Session session);

    /**
     * @throws ResourceNotFoundException if {@code organizationId} is not the session's own
     *         organization, {@code subOrganizationId} does not resolve under it, or the resolved
     *         sub-organization scope is not visible to the caller
     */
    List<ScopeNode> listEnvironments(Session session, UUID organizationId, UUID subOrganizationId);
}
