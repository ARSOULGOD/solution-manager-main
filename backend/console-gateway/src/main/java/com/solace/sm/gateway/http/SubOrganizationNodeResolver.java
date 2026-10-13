package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code createEnvironment} (authorized at the PARENT) and {@code renameSubOrganization}
 * ({@code x-sm-scope: sub-organization}) to the SUB_ORGANIZATION the path names. The path's organization must be the
 * session's ({@link HierarchyPath}); whether the sub-organization exists under it, and is visible to the caller, is the
 * authorization service's answer ({@code 404}, indistinguishable from an absent one), and the service binds the pair again.
 */
@Component
public final class SubOrganizationNodeResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        HierarchyPath.sessionOrganization(request, session);
        return HierarchyPath.node(ResourceKind.SUB_ORGANIZATION,
                EnvironmentResolver.pathId(request, "subOrganizationId", ResourceKind.SUB_ORGANIZATION), session);
    }
}
