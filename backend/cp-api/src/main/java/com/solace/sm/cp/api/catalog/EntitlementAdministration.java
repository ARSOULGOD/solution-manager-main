package com.solace.sm.cp.api.catalog;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Entitlements over the API (plan 013 T2.2; D-REG 5.2 E1 and E2; {@code OR-121} OQ-4 (a)): which solutions an
 * organization may declare instances of, and how many. This changes {@code OR-48}'s "operator command only"; the
 * command ({@code EntitlementMain}) remains and is unchanged.
 *
 * <p><b>Reading</b> is {@code solution.view} at the organization. <b>Setting</b> is {@code entitlement.set} (HIGH)
 * evaluated at <b>install</b> scope on the session's own organization: an organization-bound Administrator cannot
 * raise its own quota, and an install-bound one can set the limit of the organization it belongs to and no other
 * ({@code I5}: no request field names an organization). Both methods call {@code AuthorizationService.require}
 * themselves, before any read of the entitlement or write. A set is authorized twice: at install (who may set) and at the
 * named organization, so an organization that is not the caller's own, or does not exist, is {@code 404}, never a write.
 *
 * <p><b>Lowering below use is refused.</b> A value below {@link Entitlement#declaredInstances()} is
 * {@link EntitlementRefusedException} with reason {@code BELOW_DECLARED} ({@code 409 ENTITLEMENT_REFUSED}); the count
 * is the same {@code countInstances} the declare path enforces, and a retired instance does not count (plan 012 T4).
 * The entitlement row is locked for the count and the write, so a concurrent declare cannot slip between them.
 *
 * <p>Setting the value the entitlement already holds is a no-op: nothing is written, and no audit row, so a replay
 * changes nothing. There is no delete: an entitlement is lowered, never removed. The solution's name is not required
 * to name a loaded catalog entry (the operator command does not require it either).
 */
public interface EntitlementAdministration {

    /** The widest limit the API accepts; the contract's {@code maximum}. */
    int MAX_INSTANCES_LIMIT = 1_000_000;

    /**
     * The shape of a solution name, the one {@code catalog_solution_name} and the operator command enforce; the
     * contract's {@code pattern} for the {@code {solution}} path parameter. A name outside it is {@code 400} at the
     * gateway, never a {@link IllegalArgumentException} from {@link #set}.
     */
    String SOLUTION_NAME_PATTERN = "^[a-z][a-z0-9-]{0,63}$";

    /** {@link #SOLUTION_NAME_PATTERN}, compiled. */
    Pattern SOLUTION_NAME = Pattern.compile(SOLUTION_NAME_PATTERN);

    /**
     * The one role whose row is the install-wide resource {@code entitlement.set} is evaluated against: V003's built-in
     * Administrator ({@code ResourceKind.ROLE_DEFINITION} resolves to the install scope whatever the role, but the row
     * must exist). Shared by the service and the gateway's resolver so the two authorize the same resource.
     */
    UUID INSTALL_ANCHOR_ROLE = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /**
     * One entitlement as the organization sees it.
     *
     * @param declaredInstances the instances of this solution the organization has declared and not retired
     */
    record Entitlement(String solution, int maxInstances, long declaredInstances, Instant setAt) {

        public Entitlement {
            Objects.requireNonNull(solution, "solution");
            Objects.requireNonNull(setAt, "setAt");
        }
    }

    /**
     * The organization's entitlements, by solution name.
     *
     * @throws AccessDeniedException     {@code solution.view} is not held at the organization
     * @throws ResourceNotFoundException the organization is not the caller's own or not visible to it
     */
    List<Entitlement> list(PrincipalRef actor, UUID organizationId);

    /**
     * Sets (or changes) the limit, audited {@code ENTITLEMENT_SET} with {@code actor} as the actor.
     *
     * @throws AccessDeniedException       {@code entitlement.set} is not held at install scope
     * @throws EntitlementRefusedException {@code maxInstances} is below the declared count
     * @throws IllegalArgumentException    the solution name is not a valid solution name, or {@code maxInstances} is
     *                                     not in {@code 1..}{@value #MAX_INSTANCES_LIMIT}
     */
    Entitlement set(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String solution, int maxInstances);
}
