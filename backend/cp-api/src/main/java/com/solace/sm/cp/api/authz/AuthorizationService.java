package com.solace.sm.cp.api.authz;

/**
 * The <b>one</b> enforcement point.
 *
 * <p>⛔ <b>No controller-local role check, no {@code isAdmin}, no numeric role level, and no
 * production super-admin bypass.</b> The administrator is a built-in role with every catalog
 * permission bound — special by <i>grant</i>, not by code path. An {@code if (user.isAdmin())}
 * anywhere in the tree is a defect, and {@code AuthorizationBoundaryTest} fails the build on
 * one.
 *
 * <p><b>Why an interface in {@code cp-api}:</b> every caller — {@code cp-core}, the future
 * gateway, plan 007's controllers — names verbs, and none of them may import {@code cp-core}
 * internals ({@code I7}).
 */
public interface AuthorizationService {

    /**
     * Authorizes {@code principal} to perform {@code permission} on {@code resource}, or
     * throws.
     *
     * <p>⚠️ <b>Throws rather than returning a boolean, deliberately.</b> A boolean invites
     * {@code if (allowed) { … }} with no else, and a forgotten else is an open door. There is
     * no {@code isAllowed} sibling for the same reason.
     *
     * <p>⚠️ For a placement-targeted operation, pass its placement identity and tenant.
     * The implementation resolves the owning instance's scope from server-side state
     * ({@code OR-16}); callers never supply an authorization path.
     *
     * @throws AccessDeniedException    the verb is not held at the resource's scope
     * @throws ResourceNotFoundException the resource does not exist, or is outside every scope
     *                                   the caller holds — the two are not distinguished
     */
    void require(PrincipalRef principal, Permission permission, ResourceRef resource);
}
