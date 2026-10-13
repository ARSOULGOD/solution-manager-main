package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * What the hierarchy-write resolvers share: reading a path id, and the one check the services cannot make for
 * themselves.
 *
 * <p>⛔ {@code I5}. {@code HierarchyAdministration} takes the organization as an argument and binds it in every statement,
 * and it builds the {@link TenantId} it authorizes under from that same argument, so a service handed another
 * organization's id would authorize "that organization's administrator" in "that organization". The gateway therefore
 * refuses a path whose {@code organizationId} is not the session's before anything is authorized or called: {@code 404},
 * the answer for a node that does not exist, so a caller learns nothing about whether the other organization has it.
 */
final class HierarchyPath {

    private HierarchyPath() {
    }

    /** The path's {@code organizationId}, which must be the session's own; {@code 404} otherwise. */
    static UUID sessionOrganization(HttpServletRequest request, Session session) {
        UUID path = EnvironmentResolver.pathId(request, "organizationId", ResourceKind.ORGANIZATION);
        if (!path.equals(session.organizationId())) {
            throw new ResourceNotFoundException(ResourceKind.ORGANIZATION);
        }
        return path;
    }

    /** {@code kind} {@code id} under the session's tenant. */
    static ResourceRef node(ResourceKind kind, UUID id, Session session) {
        return ResourceRef.of(kind, id, new TenantId(session.organizationId()));
    }
}
