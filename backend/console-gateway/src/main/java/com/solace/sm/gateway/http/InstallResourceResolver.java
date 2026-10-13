package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code setEntitlement} ({@code x-sm-scope: install}) to the INSTALL-wide
 * resource, the one only a grant bound at install scope authorizes: an organization-bound Administrator cannot raise its
 * own quota (plan 013 OQ-4 (a)). {@code renameOrganization} is install-scoped too ({@code OR-125} (3)) but uses
 * {@link InstallOrganizationResolver}, which also checks the path id's shape. The verb is the operation's own ({@code ConsoleOperations}); this class
 * names only the resource.
 *
 * <p>{@code ResourceKind} has no install constant. {@code ROLE_DEFINITION} is install-wide ("Install-wide in v1: []"), but
 * the row must exist for the scope resolver to find it, so the anchor is V003's built-in Administrator, the same row
 * {@link EntitlementAdministration#INSTALL_ANCHOR_ROLE} gives the service. The service then also requires
 * {@code entitlement.set} at the NAMED organization (which is the session's: nothing in the request names one), so this
 * is the interceptor's coarse check and the service's is the exact one.
 *
 * <p>⛔ {@code I5}: nothing is read from the request.
 */
@Component
public final class InstallResourceResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.of(ResourceKind.ROLE_DEFINITION, EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                new TenantId(session.organizationId()));
    }
}
