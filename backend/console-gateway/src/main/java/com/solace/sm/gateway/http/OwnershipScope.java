package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import java.util.UUID;

/**
 * What a target's {@code ownershipScope} names: one of the three nodes a target can be owned at (an organization, a
 * sub-organization, an environment; {@code SM-D23}), as the single node {@code target.create} is evaluated at.
 *
 * <p>Both the pre-handler resolver and the orchestrator derive it from the same {@link ScopeRef} with this one method,
 * so the node that was authorized and the node that is acted on cannot be computed two ways.
 */
record OwnershipScope(ResourceKind kind, UUID id) {

    /**
     * @throws DeclareValidationException {@code ownershipScope}: a solution or an instance is not a place a target can
     *         be owned (a fixed field name; nothing from the request)
     */
    static OwnershipScope of(ScopeRef ref, UUID organizationId) {
        if (ref.solutionId() != null || ref.instanceId() != null) {
            throw new DeclareValidationException("ownershipScope");
        }
        if (ref.environmentId() != null) {
            return new OwnershipScope(ResourceKind.ENVIRONMENT, ref.environmentId());
        }
        if (ref.subOrganizationId() != null) {
            return new OwnershipScope(ResourceKind.SUB_ORGANIZATION, ref.subOrganizationId());
        }
        return new OwnershipScope(ResourceKind.ORGANIZATION, organizationId);
    }

    /** The resource {@code target.create} is authorized against; the tenant is the session's own ({@code I5}). */
    ResourceRef resource(UUID organizationId) {
        return ResourceRef.of(kind, id, new TenantId(organizationId));
    }
}
