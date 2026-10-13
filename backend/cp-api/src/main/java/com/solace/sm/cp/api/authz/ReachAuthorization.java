package com.solace.sm.cp.api.authz;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * S0 point 24 (plan 010 T1.3): authorization for list and fan-out operations whose items live at
 * many scopes, which no single path resource can authorize.
 *
 * <p><b>Why it is a second port and not a second method on {@link AuthorizationService}.</b>
 * {@code require} answers "may this principal do this to THIS resource". A list has no one resource: an
 * Operator bound at one environment must be able to list its organization's instances, and the
 * organization is exactly the resource the Operator does <i>not</i> reach. The honest question is two
 * questions, and this port asks them separately.
 *
 * <p>⛔ <b>Not an {@code isAllowed}.</b> The pre-check throws, and the filter returns a subset: an
 * unauthorized item is simply absent, so there is no {@code if (allowed)} with a forgotten else
 * ({@link AuthorizationService}'s javadoc is the reason that interface has no boolean sibling).
 *
 * <p>⚠️ <b>Reach is downward authorization, never navigation visibility.</b> An environment-bound
 * grant makes its ancestors <i>visible</i> (the hierarchy reads show them) but does not
 * <i>authorize</i> them; this port uses the second only, so a filtered list can never leak an
 * ancestor, a sibling, or another organization's row.
 */
public interface ReachAuthorization {

    /**
     * Passes only when {@code permission} is held at one or more grants inside the principal's own
     * organization (an install-scoped grant counts: it reaches every organization). The organization
     * is the principal's, resolved server-side ({@code I5}); there is no organization argument to
     * supply a different one.
     *
     * @throws AccessDeniedException the verb is held nowhere in the principal's organization, or the
     *                               principal's organization cannot be resolved
     */
    void requireHeldAnywhere(PrincipalRef principal, Permission permission);

    /**
     * The members of {@code ids} (all of one {@code kind}) that belong to the principal's organization
     * and at whose OWN scope {@code permission} is authorized by downward reach.
     *
     * <p>An id that does not exist, belongs to another organization, has no organization, or is of a
     * kind with no installed directory is absent from the result — never an error, and never
     * distinguishable from "not authorized" (the {@link ResourceNotFoundException} collapse). The
     * result is a subset of {@code ids}; callers keep their own ordering.
     */
    Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind,
            Collection<UUID> ids);
}
