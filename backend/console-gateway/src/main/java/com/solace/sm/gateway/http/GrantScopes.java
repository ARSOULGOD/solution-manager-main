package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import java.util.UUID;

/**
 * The scope a role assignment is granted or revoked at, as the one resource {@code role.assign} is evaluated against
 * (plan 013 T3.2; D-REG section 4: "the granted scope", {@code x-sm-scope: named}).
 *
 * <p>Both {@link GrantedScopeResolver} (the body's {@code scope}) and {@link AssignmentResolver} (the stored binding's
 * scope) derive their resource with the same method here, as does the grant handler's check that it acts on the node
 * that was authorized, so the node authorized and the node acted on cannot be computed two ways.
 */
final class GrantScopes {

    private GrantScopes() {
    }

    /**
     * The {@link com.solace.sm.cp.api.authz.ScopePath} a {@link ScopeRef} names beneath the session's organization.
     *
     * @throws IllegalArgumentException the levels have a hole (a solution with no environment): a malformed request
     */
    static com.solace.sm.cp.api.authz.ScopePath pathOf(ScopeRef ref, UUID organizationId) {
        return new com.solace.sm.cp.api.authz.ScopePath(organizationId, ref.subOrganizationId(),
                ref.environmentId(), ref.solutionId(), ref.instanceId());
    }

    /** The deepest node of the scope, by kind and id: the one resource whose own path the authorization resolves. */
    static ResourceRef nodeAt(com.solace.sm.cp.api.authz.ScopePath scope, UUID organizationId) {
        TenantId tenant = new TenantId(organizationId);
        return switch (scope.depth()) {
            case 1 -> ResourceRef.of(ResourceKind.ORGANIZATION, scope.organization(), tenant);
            case 2 -> ResourceRef.of(ResourceKind.SUB_ORGANIZATION, scope.subOrganization(), tenant);
            case 3 -> ResourceRef.of(ResourceKind.ENVIRONMENT, scope.environment(), tenant);
            case 4 -> ResourceRef.of(ResourceKind.SOLUTION, scope.solution(), tenant);
            default -> ResourceRef.of(ResourceKind.INSTANCE, scope.instance(), tenant);
        };
    }
}
